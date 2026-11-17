#!/system/bin/sh
# DFTB Companion - uninstall. Removes only the module's own boot/journal state.
# Partition backups under /data/adb/dftb/partitions are left in place on purpose -
# they are the user's data; remove them by hand if you want them gone.

rm -f /data/adb/dftb/resume.trigger /data/adb/dftb/service.log 2>/dev/null
rm -rf /data/adb/dftb/journal 2>/dev/null
# only drop the state root if nothing valuable is left in it
rmdir /data/adb/dftb/partitions 2>/dev/null
rmdir /data/adb/dftb 2>/dev/null
exit 0
