#!/usr/bin/env bash
#
# fetch_prebuilts.sh — DFTB Phase 9 native prebuilts pipeline.
#
# Downloads / builds the third-party CLI tools DFTB drives at runtime and stages them into
# app/src/main/jniLibs/<abi>/ as pretend shared libraries (lib<tool>.so) so Android packages
# them and they land executable in applicationInfo.nativeLibraryDir (see NativeToolProvisioner).
# Packaging them under jniLibs is what satisfies the Android 10-17 W^X SELinux rule: binaries in
# app_data_file cannot be exec'd by the app/Shizuku uid, but a copy in nativeLibraryDir can.
#
#   rclone   -> librclone.so    (official static Go binary; verified against rclone's SHA256SUMS)
#   busybox  -> libbusybox.so   (Android NDK PIE build from the Magisk busybox-ndk repo, pinned commit)
#   zstd     -> libzstd_bin.so  (CLI compiled from the official source tarball with the Android NDK;
#                                named *_bin to never collide with zstd-jni's own libzstd-jni-*.so)
#
# The binaries are deliberately NOT committed to the repo; this script (or the `fetchPrebuilts`
# Gradle task) repopulates jniLibs on demand. Every download is pinned to an exact version/commit
# and checksummed with SHA-256; a mismatch aborts without staging anything.
#
# Usage:
#   scripts/fetch_prebuilts.sh [--tools rclone,busybox,zstd] [--abis arm64-v8a,armeabi-v7a,x86_64]
#                              [--verify-only] [--clean] [--force]
# Environment overrides: DFTB_TOOLS, DFTB_ABIS, DFTB_API (min Android API for the zstd build; 29),
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_HOME / ANDROID_SDK_ROOT (NDK discovery, zstd only).
#
# Idempotent: a staged lib with a matching .stamp (tool version/commit + abi) is left untouched
# unless --force is given. Compatible with macOS's stock bash 3.2 (no associative arrays) and Linux.

set -euo pipefail

# ---- pinned versions & checksums (authoritative upstream values) --------------------------------
RCLONE_VER="1.75.2"
# SHA-256 of each rclone release zip, copied from https://downloads.rclone.org/v1.75.2/SHA256SUMS
RCLONE_SHA_arm64="7e1e8d69654941b7b7df84ee74c5f7fb09cce7d5496947fb7bf16b55ff427d10"   # linux-arm64
RCLONE_SHA_armv7="757a5b233ad0e8cdc60749a2d451231d81be45c01304a4c04b4514e733275b73"   # linux-arm-v7
RCLONE_SHA_amd64="349ac8fba6ff65d6247043f1750cdcb518ec5d500ef91463a10d37c0ccdf3702"   # linux-amd64

# busybox-ndk prebuilt binaries, pinned to an exact commit so the raw file can never change under us.
BUSYBOX_REPO="Magisk-Modules-Repo/busybox-ndk"
BUSYBOX_COMMIT="ad6700620120e3c475df05b0f8bfa7a40e741ee0"
BUSYBOX_SHA_arm64="c629fce4b0dd3ba9775f851d0941e74582115f423258d3a79800f2bd11d30f5c"   # busybox-arm64
BUSYBOX_SHA_arm="66436dc1e97d22886ed2d35bd69a9cb82bb0fdba4e4267c5184cee1d39a2f5f4"     # busybox-arm   (armv7)
BUSYBOX_SHA_x86_64="262e1f93e426b6befe14bfacb8a19d466fb91a1eab813e07517cd9746f3c72a5"  # busybox-x86_64

# zstd CLI is cross-compiled from source (no official Android static binary exists).
ZSTD_VER="1.5.7"
ZSTD_TARBALL_SHA="eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3"      # zstd-1.5.7.tar.gz

# nsenter (from util-linux) is cross-compiled from source for the companion module (namespace entry).
NSENTER_VER="2.40.2"
NSENTER_TARBALL_SHA="7bec316b713a14c6be1a5721aa0e56a3b6170277329e6e1f1a56013cc91eece0"   # util-linux-2.40.2.tar.gz (kernel.org)

# ---- defaults -----------------------------------------------------------------------------------
TOOLS="${DFTB_TOOLS:-rclone,busybox,zstd,nsenter}"
ABIS="${DFTB_ABIS:-arm64-v8a,armeabi-v7a,x86_64}"
API="${DFTB_API:-29}"
VERIFY_ONLY=0
CLEAN=0
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tools) TOOLS="$2"; shift 2 ;;
    --abis) ABIS="$2"; shift 2 ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --clean) CLEAN=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "fetch_prebuilts: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# Repo root = parent of this script's scripts/ directory, so the task works from any CWD.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS="$REPO_ROOT/app/src/main/jniLibs"
CACHE="${DFTB_PREBUILTS_CACHE:-$REPO_ROOT/.prebuilts-cache}"
mkdir -p "$CACHE"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/dftb-prebuilts.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

log()  { printf '[prebuilts] %s\n' "$*" >&2; }
die()  { printf '[prebuilts] ERROR: %s\n' "$*" >&2; exit 1; }

# SHA-256 that works on both macOS (shasum) and Linux (sha256sum).
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else die "need sha256sum or shasum on PATH"; fi
}

verify_sha() {  # file expected
  local got; got="$(sha256 "$1")"
  [ "$got" = "$2" ] || die "checksum mismatch for $(basename "$1"): expected $2, got $got"
}

# Confirm a staged file is a real ELF executable (first 4 bytes 0x7f 'E' 'L' 'F').
assert_elf() {
  local magic; magic="$(od -An -tx1 -N4 "$1" | tr -d ' \n')"
  [ "$magic" = "7f454c46" ] || die "$(basename "$1") is not an ELF binary (magic=$magic) — refusing to stage a corrupt tool"
}

download() {  # url dest
  log "download $(basename "$2")"
  if command -v curl >/dev/null 2>&1; then curl -fSL --retry 3 --connect-timeout 20 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else die "need curl or wget on PATH"; fi
}

# Stamp lets us skip already-staged, unchanged tools (idempotency).
stamp_matches() { [ -f "$1.stamp" ] && [ "$(cat "$1.stamp" 2>/dev/null)" = "$2" ]; }
write_stamp()   { printf '%s' "$2" > "$1.stamp"; }

has() { case ",$1," in *",$2,"*) return 0 ;; *) return 1 ;; esac; }

# ---- per-ABI asset maps (bash 3.2: functions instead of associative arrays) ---------------------
rclone_asset() { case "$1" in
  arm64-v8a) echo "linux-arm64" ;; armeabi-v7a) echo "linux-arm-v7" ;; x86_64) echo "linux-amd64" ;;
  *) die "rclone: unsupported ABI $1" ;; esac; }
rclone_sha() { case "$1" in
  arm64-v8a) echo "$RCLONE_SHA_arm64" ;; armeabi-v7a) echo "$RCLONE_SHA_armv7" ;; x86_64) echo "$RCLONE_SHA_amd64" ;; esac; }
busybox_asset() { case "$1" in
  arm64-v8a) echo "busybox-arm64" ;; armeabi-v7a) echo "busybox-arm" ;; x86_64) echo "busybox-x86_64" ;;
  *) die "busybox: unsupported ABI $1" ;; esac; }
busybox_sha() { case "$1" in
  arm64-v8a) echo "$BUSYBOX_SHA_arm64" ;; armeabi-v7a) echo "$BUSYBOX_SHA_arm" ;; x86_64) echo "$BUSYBOX_SHA_x86_64" ;; esac; }
zstd_triple() { case "$1" in   # NDK clang target triple (without the API suffix)
  arm64-v8a) echo "aarch64-linux-android" ;; armeabi-v7a) echo "armv7a-linux-androideabi" ;; x86_64) echo "x86_64-linux-android" ;;
  *) die "zstd: unsupported ABI $1" ;; esac; }

stage_file() {  # src dest-lib
  install -m 0755 "$src_bin" "$1" 2>/dev/null || { cp "$src_bin" "$1"; chmod 0755 "$1"; }
  assert_elf "$1"
}

# ---- rclone -------------------------------------------------------------------------------------
do_rclone() {
  local abi="$1" out="$JNILIBS/$1/librclone.so" key="rclone-$RCLONE_VER-$1"
  if [ "$FORCE" -eq 0 ] && stamp_matches "$out" "$key"; then log "rclone $abi up to date"; return; fi
  local asset sha zip; asset="$(rclone_asset "$abi")"; sha="$(rclone_sha "$abi")"
  zip="$CACHE/rclone-v$RCLONE_VER-$asset.zip"
  [ -f "$zip" ] && { sha256 "$zip" | grep -q "^$sha$" || rm -f "$zip"; }
  [ -f "$zip" ] || download "https://downloads.rclone.org/v$RCLONE_VER/rclone-v$RCLONE_VER-$asset.zip" "$zip"
  verify_sha "$zip" "$sha"
  rm -rf "$TMP/rclone-$abi"; mkdir -p "$TMP/rclone-$abi"
  unzip -q -o "$zip" -d "$TMP/rclone-$abi"
  local src_bin; src_bin="$TMP/rclone-$abi/rclone-v$RCLONE_VER-$asset/rclone"
  [ -f "$src_bin" ] || die "rclone binary not found in $zip"
  mkdir -p "$JNILIBS/$abi"; stage_file "$out"      # Go static binary: never stripped (corruption risk)
  write_stamp "$out" "$key"; log "staged $out"
}

# ---- busybox ------------------------------------------------------------------------------------
do_busybox() {
  local abi="$1" out="$JNILIBS/$1/libbusybox.so" key="busybox-$BUSYBOX_COMMIT-$1"
  if [ "$FORCE" -eq 0 ] && stamp_matches "$out" "$key"; then log "busybox $abi up to date"; return; fi
  local asset sha bin; asset="$(busybox_asset "$abi")"; sha="$(busybox_sha "$abi")"
  bin="$CACHE/$asset-$BUSYBOX_COMMIT"
  [ -f "$bin" ] && { sha256 "$bin" | grep -q "^$sha$" || rm -f "$bin"; }
  [ -f "$bin" ] || download "https://raw.githubusercontent.com/$BUSYBOX_REPO/$BUSYBOX_COMMIT/$asset" "$bin"
  verify_sha "$bin" "$sha"
  local src_bin="$bin"
  mkdir -p "$JNILIBS/$abi"; stage_file "$out"
  write_stamp "$out" "$key"; log "staged $out"
}

# ---- zstd (compiled from source with the Android NDK) -------------------------------------------
find_ndk() {
  for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    [ -n "$c" ] && [ -d "$c" ] && { echo "$c"; return; }
  done
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$sdk" ] && [ -d "$sdk/ndk" ] || continue
    # highest-versioned NDK installed
    local latest; latest="$(ls -1 "$sdk/ndk" 2>/dev/null | sort -V | tail -1)"
    [ -n "$latest" ] && { echo "$sdk/ndk/$latest"; return; }
  done
  die "zstd needs the Android NDK; set ANDROID_NDK_HOME or install it under \$ANDROID_HOME/ndk"
}

do_zstd() {
  local abi="$1" out="$JNILIBS/$1/libzstd_bin.so" key="zstd-$ZSTD_VER-api$API-$1"
  if [ "$FORCE" -eq 0 ] && stamp_matches "$out" "$key"; then log "zstd $abi up to date"; return; fi
  command -v make >/dev/null 2>&1 || die "zstd build needs 'make' on PATH"
  local tarball="$CACHE/zstd-$ZSTD_VER.tar.gz"
  [ -f "$tarball" ] && { sha256 "$tarball" | grep -q "^$ZSTD_TARBALL_SHA$" || rm -f "$tarball"; }
  [ -f "$tarball" ] || download "https://github.com/facebook/zstd/releases/download/v$ZSTD_VER/zstd-$ZSTD_VER.tar.gz" "$tarball"
  verify_sha "$tarball" "$ZSTD_TARBALL_SHA"

  local ndk host triple cc bindir srcdir
  ndk="$(find_ndk)"
  case "$(uname -s)" in Darwin) host="darwin-x86_64" ;; Linux) host="linux-x86_64" ;; *) die "unsupported build host $(uname -s)" ;; esac
  bindir="$ndk/toolchains/llvm/prebuilt/$host/bin"
  [ -d "$bindir" ] || die "NDK toolchain not found at $bindir"
  triple="$(zstd_triple "$abi")"
  cc="$bindir/${triple}${API}-clang"
  [ -x "$cc" ] || die "NDK clang not found: $cc"

  srcdir="$TMP/zstd-$ZSTD_VER-$abi"; rm -rf "$srcdir"; mkdir -p "$srcdir"
  tar -xzf "$tarball" -C "$srcdir" --strip-components=1
  log "compiling zstd $ZSTD_VER for $abi ($triple, api $API)"
  # Dynamic-PIE binary linked against bionic (present on every device): correct and portable on
  # Android, unlike a fragile fully-static bionic link. Optional codecs off so there are no host
  # library deps. The NDK clang defaults to PIE; we only disable zlib/lzma/lz4 autodetection.
  make -C "$srcdir/programs" -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)" zstd \
    CC="$cc" AR="$bindir/llvm-ar" RANLIB="$bindir/llvm-ranlib" \
    HAVE_ZLIB=0 HAVE_LZMA=0 HAVE_LZ4=0 \
    MOREFLAGS="-O2 -ffunction-sections -fdata-sections" \
    LDFLAGS="-Wl,--gc-sections" >/dev/null
  local src_bin="$srcdir/programs/zstd"
  [ -f "$src_bin" ] || die "zstd build produced no binary"
  mkdir -p "$JNILIBS/$abi"; stage_file "$out"
  write_stamp "$out" "$key"; log "staged $out"
}

# ---- nsenter (compiled from util-linux with the Android NDK) ------------------------------------
do_nsenter() {
  local abi="$1" out="$JNILIBS/$1/libnsenter.so" key="nsenter-$NSENTER_VER-api$API-$1"
  if [ "$FORCE" -eq 0 ] && stamp_matches "$out" "$key"; then log "nsenter $abi up to date"; return; fi
  command -v make >/dev/null 2>&1 || die "nsenter build needs 'make' on PATH"
  local tarball="$CACHE/util-linux-$NSENTER_VER.tar.gz"
  [ -f "$tarball" ] && { sha256 "$tarball" | grep -q "^$NSENTER_TARBALL_SHA$" || rm -f "$tarball"; }
  [ -f "$tarball" ] || download "https://www.kernel.org/pub/linux/utils/util-linux/v${NSENTER_VER%.*}/util-linux-$NSENTER_VER.tar.gz" "$tarball"
  verify_sha "$tarball" "$NSENTER_TARBALL_SHA"

  local ndk host triple bindir srcdir
  ndk="$(find_ndk)"
  case "$(uname -s)" in Darwin) host="darwin-x86_64" ;; Linux) host="linux-x86_64" ;; *) die "unsupported build host $(uname -s)" ;; esac
  bindir="$ndk/toolchains/llvm/prebuilt/$host/bin"
  [ -d "$bindir" ] || die "NDK toolchain not found at $bindir"
  triple="$(zstd_triple "$abi")"   # same NDK target-triple mapping
  local cc="$bindir/${triple}${API}-clang"
  [ -x "$cc" ] || die "NDK clang not found: $cc"

  srcdir="$TMP/util-linux-$NSENTER_VER-$abi"; rm -rf "$srcdir"; mkdir -p "$srcdir"
  tar -xzf "$tarball" -C "$srcdir" --strip-components=1
  log "compiling nsenter (util-linux $NSENTER_VER) for $abi ($triple, api $API)"
  (
    cd "$srcdir"
    export CC="$cc" AR="$bindir/llvm-ar" RANLIB="$bindir/llvm-ranlib"
    # Build only nsenter: statically link util-linux's own libcommon; drop every optional dep so
    # there are no host-library deps (dynamic-PIE against bionic, which is present on every device).
    # --disable-year2038: 32-bit Android (armeabi-v7a) has a 32-bit time_t and util-linux's configure
    # otherwise refuses to build; nsenter does not depend on post-2038 timestamps. No-op on 64-bit ABIs.
    ./configure --host="$triple" --disable-shared --enable-static \
      --disable-nls --disable-all-programs --enable-nsenter --disable-year2038 \
      --without-tinfo --without-ncurses --without-ncursesw --without-python \
      --without-systemd --without-udev >configure.log 2>&1 || { tail -20 configure.log >&2; exit 1; }
    make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)" nsenter >build.log 2>&1 || { tail -20 build.log >&2; exit 1; }
  ) || die "nsenter build failed for $abi"
  local src_bin="$srcdir/nsenter"
  [ -f "$src_bin" ] || die "nsenter build produced no binary"
  mkdir -p "$JNILIBS/$abi"; stage_file "$out"
  write_stamp "$out" "$key"; log "staged $out"
}

# ---- drivers ------------------------------------------------------------------------------------
if [ "$CLEAN" -eq 1 ]; then
  log "cleaning staged prebuilts"
  for abi in $(echo "$ABIS" | tr ',' ' '); do
    rm -f "$JNILIBS/$abi/"librclone.so "$JNILIBS/$abi/"libbusybox.so "$JNILIBS/$abi/"libzstd_bin.so "$JNILIBS/$abi/"libnsenter.so
    rm -f "$JNILIBS/$abi/"librclone.so.stamp "$JNILIBS/$abi/"libbusybox.so.stamp "$JNILIBS/$abi/"libzstd_bin.so.stamp "$JNILIBS/$abi/"libnsenter.so.stamp
  done
  exit 0
fi

if [ "$VERIFY_ONLY" -eq 1 ]; then
  rc=0
  for abi in $(echo "$ABIS" | tr ',' ' '); do
    for lib in librclone.so libbusybox.so libzstd_bin.so libnsenter.so; do
      f="$JNILIBS/$abi/$lib"
      if [ -f "$f" ]; then assert_elf "$f"; log "ok $abi/$lib"; else log "missing $abi/$lib"; rc=1; fi
    done
  done
  exit $rc
fi

for abi in $(echo "$ABIS" | tr ',' ' '); do
  has "$TOOLS" rclone  && do_rclone  "$abi"
  has "$TOOLS" busybox && do_busybox "$abi"
  has "$TOOLS" zstd    && do_zstd    "$abi"
  has "$TOOLS" nsenter && do_nsenter "$abi"
done
log "done — staged into $JNILIBS"
