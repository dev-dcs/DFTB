#!/system/bin/sh
# update-check - asks the repo's update.json whether a newer module is out.
# No API token, no git. Prints a line, or JSON for the WebUI with --json.
# @Dev_DcS

MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
PROP=$MODDIR/module.prop
STATE=/data/adb/dftb/update.state

url=$(sed -n 's/^updateJson=//p' "$PROP")
cur=$(sed -n 's/^versionCode=//p' "$PROP")
curname=$(sed -n 's/^version=//p' "$PROP")

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -m 20 "$1" 2>/dev/null && return 0
  fi
  for bb in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox "$MODDIR/system/bin/busybox"; do
    [ -x "$bb" ] && "$bb" wget -q -T 20 -O - "$1" 2>/dev/null && return 0
  done
  return 1
}

json=$(fetch "$url") || { echo "could not reach update server"; exit 0; }

get() { printf '%s' "$json" | sed -n "s/.*\"$1\" *: *\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1; }
rcode=$(get versionCode); rname=$(get version); rzip=$(get zipUrl)
[ -n "$rcode" ] || { echo "update server gave no versionCode"; exit 0; }

if [ "$rcode" -gt "${cur:-0}" ] 2>/dev/null; then
  avail=1
else
  avail=0
fi

# remember the result for the WebUI / action button
{ echo "available=$avail"; echo "latest=$rname"; echo "latestCode=$rcode"; echo "zip=$rzip"; echo "checkedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$STATE" 2>/dev/null

case ${1:-} in
  --json)
    printf '{"available":%s,"current":"%s","latest":"%s","zipUrl":"%s"}\n' "$avail" "$curname" "$rname" "$rzip"
    ;;
  --quiet)
    [ "$avail" = 1 ] && echo "update available: $rname"
    ;;
  --boot)
    # once a day at most, and only to tell you something is new
    if [ "$avail" = 1 ]; then
      stamp=$(date +%Y%m%d)
      [ "$(cat /data/adb/dftb/.notified 2>/dev/null)" = "$stamp" ] && exit 0
      echo "$stamp" > /data/adb/dftb/.notified
      cmd notification post -S bigtext -t "DFTB Companion $rname" dftbc "A newer module is available" >/dev/null 2>&1 || true
    fi
    ;;
  *)
    if [ "$avail" = 1 ]; then echo "update available: $rname (you have $curname)"; else echo "up to date ($curname)"; fi
    ;;
esac
