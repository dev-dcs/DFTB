#!/system/bin/sh
# DFTB Companion - install-time setup. Sourced by Magisk/KernelSU/APatch after the
# zip is unpacked to $MODPATH. POSIX sh only.

SKIPUNZIP=0

ui_print "- DFTB Companion $(sed -n 's/^version=//p' "$MODPATH/module.prop")"

# the zip carries the tools per ABI under tools/<abi>; keep the one for this phone
case "$ARCH" in
  arm64) abi=arm64-v8a ;;
  arm)   abi=armeabi-v7a ;;
  x64)   abi=x86_64 ;;
  *) abort "! unsupported architecture '$ARCH'" ;;
esac
[ -d "$MODPATH/tools/$abi" ] || abort "! this zip has no tools for $abi"
ui_print "- Arch: $ARCH ($abi)"
mkdir -p "$MODPATH/system/bin"
cp -f "$MODPATH/tools/$abi"/* "$MODPATH/system/bin/"
rm -rf "$MODPATH/tools"

# everything in system/bin runs, and the lifecycle + libexec scripts too
set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
for s in post-fs-data.sh service.sh uninstall.sh action.sh; do
  [ -f "$MODPATH/$s" ] && set_perm "$MODPATH/$s" 0 0 0755
done
[ -d "$MODPATH/libexec" ] && set_perm_recursive "$MODPATH/libexec" 0 0 0755 0755

# tar is just a busybox applet - link it so `command -v tar` resolves
if [ -x "$MODPATH/system/bin/busybox" ] && [ ! -e "$MODPATH/system/bin/tar" ]; then
  ln -s busybox "$MODPATH/system/bin/tar"
fi

# tell the user what actually made it into this build
have=""
for t in rclone busybox nsenter zstd tar; do
  [ -e "$MODPATH/system/bin/$t" ] && have="$have $t"
done
ui_print "- Tools:$have"
[ -e "$MODPATH/system/bin/zstd" ] || ui_print "- (no zstd in this build; partbak falls back to gzip)"
[ -e "$MODPATH/system/bin/nsenter" ] || ui_print "- (no nsenter; global-namespace calls run in place)"

# DFTB's root-owned state tree, so the first boot has somewhere to journal
mkdir -p /data/adb/dftb/journal
set_perm_recursive /data/adb/dftb 0 0 0700 0700 2>/dev/null

# where partition backups land by default
mkdir -p /data/adb/dftb/partitions
set_perm /data/adb/dftb/partitions 0 0 0700 2>/dev/null

ui_print "- Done. Reboot to activate."
