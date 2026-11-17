#!/system/bin/sh
# DFTB Companion - post-fs-data. Runs early, blocks boot: keep it quick. Just makes
# sure the root-owned state tree is there with tight perms. Idempotent.

mkdir -p /data/adb/dftb/journal /data/adb/dftb/partitions 2>/dev/null
chmod 0700 /data/adb/dftb /data/adb/dftb/journal /data/adb/dftb/partitions 2>/dev/null
