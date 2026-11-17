#!/system/bin/sh
# partbak - partition backup and restore for DFTB Companion.
# Reads/writes raw partitions through /dev/block/by-name. Backups are safe (read
# only); restore writes a partition and asks first. Runs as root.
# @Dev_DcS

MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
[ -d "$MODDIR/system/bin" ] && export PATH="$MODDIR/system/bin:$PATH"

DEST_DEFAULT=/data/adb/dftb/partitions

# partitions that carry device identity or anti-rollback state. Restoring the
# wrong image here can brick the phone or corrupt IMEI/keys, so they need --force
# and a second look. Matching is on the base name, slot suffix stripped.
GUARDED="modemst1 modemst2 fsg fsc nvram nvdata nvcfg persist persist_bak devinfo
  frp sec_efs efs sec_efs1 sec_efs2 mdm1m9kefs1 mdm1m9kefs2 mdm1m9kefs3 apdp msadp
  spunvm limits limits_cal keystore dpm devcfg cmnlib secdata rpmb vm-persist"

err() { printf 'x %s\n' "$*" >&2; }
die() { err "$*"; exit 1; }
need_root() { [ "$(id -u)" = 0 ] || die "run as root"; }

byname() {
  # PARTBAK_BYNAME overrides the lookup (odd devices, and the test harness)
  [ -n "${PARTBAK_BYNAME:-}" ] && [ -d "$PARTBAK_BYNAME" ] && { echo "$PARTBAK_BYNAME"; return 0; }
  for d in /dev/block/by-name /dev/block/bootdevice/by-name \
           /dev/block/mapper /dev/block/platform/*/by-name; do
    [ -d "$d" ] && { echo "$d"; return 0; }
  done
  return 1
}

cur_slot() { getprop ro.boot.slot_suffix 2>/dev/null; }     # "_a", "_b" or empty

# resolve a partition name (optionally slotted) to a readable block device
resolve_part() {
  _name=$1 _slot=$2
  _dir=$(byname) || { err "no by-name directory"; return 1; }
  case $_slot in
    a) _sfx=_a ;; b) _sfx=_b ;;
    current|'') _sfx=$(cur_slot) ;;
    *) _sfx=$_slot ;;
  esac
  for _c in "$_dir/$_name$_sfx" "$_dir/$_name"; do
    [ -e "$_c" ] && { readlink -f "$_c"; return 0; }
  done
  return 1
}

part_size() { blockdev --getsize64 "$1" 2>/dev/null || cat "/sys/class/block/${1##*/}/size" 2>/dev/null | awk '{print $1*512}'; }

is_guarded() {
  _b=$(echo "$1" | sed 's/_[ab]$//')
  for g in $GUARDED; do [ "$_b" = "$g" ] && return 0; done
  return 1
}

compressor() {   # echoes "CMD|EXT"
  case $1 in
    zstd) command -v zstd >/dev/null 2>&1 && { echo "zstd -q -T0 -3 -c|zst"; return; } ;;
    raw)  echo "cat|img"; return ;;
  esac
  command -v gzip >/dev/null 2>&1 && { echo "gzip -c|gz"; return; }
  echo "cat|img"
}

decompressor() {  # by file extension
  case $1 in
    *.zst) command -v zstd >/dev/null 2>&1 && echo "zstd -dc" || echo "" ;;
    *.gz)  echo "gzip -dc" ;;
    *.img) echo "cat" ;;
    *) echo "" ;;
  esac
}

usage() {
  cat <<EOF
partbak <command>

  list                              partitions and sizes
  list-backups [dir]                saved backups (default $DEST_DEFAULT)
  backup <name|all> [opts]          dump a partition to an image + checksum
  restore <file> [name] [opts]      write an image back to a partition (asks first)
  verify <file>                     re-check an image against its .sha256
  delete <file>                     remove an image and its sidecars

opts:
  --dest DIR        where backups go (default $DEST_DEFAULT)
  --slot a|b|current   which slot (default: current)
  --zstd|--gzip|--raw  compression (default zstd if present, else gzip)
  --force           allow restore to a guarded (identity) partition
  --yes             do not ask for confirmation (scripts / WebUI)
EOF
}

# ---- commands -----------------------------------------------------------------

cmd_list() {
  _dir=$(byname) || die "no by-name directory on this device"
  printf '%-24s %-14s %s\n' NAME SIZE DEVICE
  for p in "$_dir"/*; do
    [ -e "$p" ] || continue
    _dev=$(readlink -f "$p"); _sz=$(part_size "$_dev")
    printf '%-24s %-14s %s\n' "${p##*/}" "$(fmt_size "$_sz")" "$_dev"
  done | sort
}

fmt_size() { awk -v b="${1:-0}" 'BEGIN{s="B K M G T";n=split(s,u," ");for(i=1;i<=n&&b>=1024;i++)b/=1024;printf (b==int(b)?"%d%s":"%.1f%s"),b,u[i?i:1]}'; }

one_backup() {
  _name=$1 _dest=$2 _mode=$3 _slot=$4
  _dev=$(resolve_part "$_name" "$_slot") || { err "$_name: not found on slot ${_slot:-current}"; return 1; }
  _sz=$(part_size "$_dev")
  _c=$(compressor "$_mode"); _ccmd=${_c%|*}; _cext=${_c##*|}
  _base="$_dest/${_name}-$(date +%Y%m%d-%H%M%S)"
  _img="$_base.$_cext"
  printf '%s  %s -> %s ... ' "$_name" "$(fmt_size "$_sz")" "${_img##*/}"
  # dd straight into the compressor (mksh has no process substitution, so the
  # integrity check is on the stored image; verify re-reads it).
  if ! dd if="$_dev" bs=4M 2>/dev/null | eval "$_ccmd" >"$_img" 2>/dev/null; then
    err "dd failed for $_name"; rm -f "$_img"; return 1
  fi
  sha256sum "$_img" | awk '{print $1}' > "$_img.sha256"
  cat > "$_base.json" <<JSON
{
  "name": "$_name",
  "slot": "${_slot:-current}",
  "device": "$_dev",
  "rawBytes": ${_sz:-0},
  "image": "${_img##*/}",
  "imageSha256": "$(cat "$_img.sha256")",
  "compression": "$_cext",
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "fingerprint": "$(getprop ro.build.fingerprint)"
}
JSON
  echo "ok"
}

cmd_backup() {
  need_root
  _what=$1; shift
  _dest=$DEST_DEFAULT _mode=zstd _slot=current
  while [ $# -gt 0 ]; do case $1 in
    --dest) _dest=$2; shift ;; --slot) _slot=$2; shift ;;
    --zstd) _mode=zstd ;; --gzip) _mode=gzip ;; --raw) _mode=raw ;;
    --yes) ;; *) err "backup: unexpected $1"; return 1 ;;
  esac; shift; done
  mkdir -p "$_dest" || die "cannot create $_dest"
  if [ "$_what" = all ]; then
    _dir=$(byname) || die "no by-name directory"
    _rc=0
    for p in "$_dir"/*; do
      [ -e "$p" ] || continue
      _n=${p##*/}
      # skip the obviously huge userdata-type partitions on "all"
      case $_n in userdata|super|cache|metadata) echo "skip $_n (use its name explicitly)"; continue ;; esac
      one_backup "$_n" "$_dest" "$_mode" "" || _rc=1
    done
    return $_rc
  fi
  one_backup "$_what" "$_dest" "$_mode" "$_slot"
}

cmd_restore() {
  need_root
  _file=$1; shift
  [ -f "$_file" ] || die "no such image: $_file"
  _name='' _slot=current _force=0 _yes=0
  while [ $# -gt 0 ]; do case $1 in
    --slot) _slot=$2; shift ;; --force) _force=1 ;; --yes) _yes=1 ;;
    --*) err "restore: unknown $1"; return 1 ;;
    *) _name=$1 ;;
  esac; shift; done
  # derive the target name from the json sidecar if not given
  _json=${_file%.*}.json
  [ -z "$_name" ] && [ -f "$_json" ] && _name=$(sed -n 's/.*"name": *"\([^"]*\)".*/\1/p' "$_json")
  [ -n "$_name" ] || die "could not tell which partition; pass the name"

  _dev=$(resolve_part "$_name" "$_slot") || die "$_name not found on slot ${_slot:-current}"

  if is_guarded "$_name" && [ "$_force" != 1 ]; then
    die "$_name is a guarded (identity/critical) partition. Re-run with --force if you are certain."
  fi

  # size sanity: refuse if the decompressed image is larger than the partition
  _dec=$(decompressor "$_file"); [ -n "$_dec" ] || die "unknown image type: $_file"
  _psz=$(part_size "$_dev")
  _isz=$(eval "$_dec \"$_file\"" 2>/dev/null | wc -c)
  [ "$_isz" -gt 0 ] || die "image is empty or could not be decompressed"
  [ "$_isz" -le "$_psz" ] || die "image ($_isz B) is larger than $_name ($_psz B); refusing"

  echo "About to write $(fmt_size "$_isz") to $_name ($_dev)."
  is_guarded "$_name" && echo "!! $_name is a GUARDED partition - a wrong image here can brick the device."
  if [ "$_yes" != 1 ]; then
    printf 'type exactly "restore %s" to go on: ' "$_name"
    read -r _ans </dev/tty 2>/dev/null || read -r _ans
    [ "$_ans" = "restore $_name" ] || die "cancelled"
  fi
  if eval "$_dec \"$_file\"" 2>/dev/null | dd of="$_dev" bs=4M 2>/dev/null; then
    sync; echo "restored $_name"
  else
    die "write failed - partition may be partially written; do NOT reboot, reflash a known-good image"
  fi
}

cmd_verify() {
  _file=$1; [ -f "$_file" ] || die "no such image: $_file"
  [ -f "$_file.sha256" ] || die "no checksum sidecar for $_file"
  _want=$(cat "$_file.sha256"); _got=$(sha256sum "$_file" | awk '{print $1}')
  if [ "$_want" = "$_got" ]; then echo "ok: $_file"; else die "MISMATCH: $_file (image is corrupt)"; fi
}

cmd_list_backups() {
  _dir=${1:-$DEST_DEFAULT}
  [ -d "$_dir" ] || { echo "(none in $_dir)"; return 0; }
  for j in "$_dir"/*.json; do
    [ -f "$j" ] || continue
    _n=$(sed -n 's/.*"name": *"\([^"]*\)".*/\1/p' "$j")
    _img=$(sed -n 's/.*"image": *"\([^"]*\)".*/\1/p' "$j")
    _at=$(sed -n 's/.*"createdAt": *"\([^"]*\)".*/\1/p' "$j")
    _sz=$( [ -f "$_dir/$_img" ] && fmt_size "$(stat -c %s "$_dir/$_img" 2>/dev/null)" )
    printf '%-20s %-10s %s  %s\n' "$_n" "$_sz" "$_at" "$_img"
  done
}

cmd_delete() {
  need_root
  _file=$1; [ -f "$_file" ] || die "no such image: $_file"
  _base=${_file%.*}
  rm -f "$_file" "$_file.sha256" "$_base.json"
  echo "deleted ${_file##*/}"
}

case ${1:-} in
  ''|-h|--help) usage ;;
  list) cmd_list ;;
  list-backups) shift; cmd_list_backups "$@" ;;
  backup) shift; [ -n "$1" ] || die "backup <name|all>"; cmd_backup "$@" ;;
  restore) shift; [ -n "$1" ] || die "restore <file> [name]"; cmd_restore "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  delete) shift; cmd_delete "$@" ;;
  *) die "unknown command '$1' (partbak -h)" ;;
esac
