#!/system/bin/sh
# Action button in the root manager - quick status without opening the WebUI.
MODDIR=${0%/*}
export PATH="$MODDIR/system/bin:$PATH"

echo "DFTB Companion $(sed -n 's/^version=//p' "$MODDIR/module.prop")"
echo
echo "Tools:"
for t in rclone busybox nsenter zstd tar; do
  if command -v "$t" >/dev/null 2>&1; then echo "  $t  $(command -v "$t")"; else echo "  $t  (missing)"; fi
done
echo
j=$(ls /data/adb/dftb/journal/*.json 2>/dev/null | wc -l)
b=$(ls /data/adb/dftb/partitions/*.img* 2>/dev/null | wc -l)
echo "Journal entries: $j"
echo "Partition backups: $b in /data/adb/dftb/partitions"
sh "$MODDIR/libexec/update-check.sh" --quiet 2>/dev/null
