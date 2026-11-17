#!/usr/bin/env bash
# pack.sh - build the flashable DFTB Companion zip from this repo.
#
# The tools are the ones the app already ships: scripts/fetch_prebuilts.sh puts
# rclone, busybox, zstd and nsenter into app/src/main/jniLibs/<abi>/ and this
# script picks them up from there, so app and module always carry the same
# binaries. The module version is the app version (app/build.gradle.kts).
#
#   scripts/fetch_prebuilts.sh           once, to fill jniLibs
#   companion/pack.sh                    arm64 zip -> companion/out/
#   companion/pack.sh --abis arm64-v8a,x86_64
# @Dev_DcS
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
JNI=$ROOT/app/src/main/jniLibs
OUT=$HERE/out
ABIS=arm64-v8a

while [ $# -gt 0 ]; do case $1 in
  --abis) ABIS=$2; shift 2 ;;
  -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
  *) echo "pack: unknown arg $1" >&2; exit 2 ;;
esac; done

die() { printf 'xx %s\n' "$*" >&2; exit 1; }
say() { printf '>> %s\n' "$*" >&2; }

ver=$(sed -n 's/^val appVersionName = "\([0-9.]*\)".*/\1/p' "$ROOT/app/build.gradle.kts")
[ -n "$ver" ] || die "could not read appVersionName from app/build.gradle.kts"
IFS=. read -r ma mi pa <<< "$ver"
code=$((10#$ma * 10000 + 10#$mi * 100 + 10#$pa))

stage=$(mktemp -d); trap 'rm -rf "$stage"' EXIT
cp -a "$HERE/module/." "$stage/"
sed -i "s/^version=.*/version=v$ver/; s/^versionCode=.*/versionCode=$code/" "$stage/module.prop"
find "$stage" -name '*.sh' -exec sed -i 's/\r$//' {} +

# jniLibs name -> name on the phone
for abi in ${ABIS//,/ }; do
  src=$JNI/$abi
  [ -f "$src/librclone.so" ] || die "no tools for $abi in app/src/main/jniLibs - run scripts/fetch_prebuilts.sh first"
  mkdir -p "$stage/tools/$abi"
  for pair in librclone.so:rclone libbusybox.so:busybox libzstd_bin.so:zstd libnsenter.so:nsenter; do
    lib=${pair%%:*} name=${pair##*:}
    if [ -f "$src/$lib" ]; then
      install -m 0755 "$src/$lib" "$stage/tools/$abi/$name"
    else
      say "$abi: $name not built, leaving it out"
    fi
  done
  say "$abi: $(ls "$stage/tools/$abi" | tr '\n' ' ')"
done

mkdir -p "$OUT"
zip="$OUT/DFTB-Companion-v$ver.zip"
rm -f "$zip"
( cd "$stage" && zip -qr9X "$zip" . )
say "built ${zip#"$ROOT"/} ($(( $(stat -c %s "$zip") / 1024 / 1024 )) MiB)"
