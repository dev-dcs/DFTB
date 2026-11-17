# DFTB Companion

Root module for DFTB. Flash it from Magisk, KernelSU or APatch.

by @Dev_DcS

## What it does

**Tools.** `rclone`, `busybox`, `zstd`, `nsenter`, plus `tar` linked from busybox, in `/system/bin`. They are the binaries the APK ships, taken from `app/src/main/jniLibs` at pack time.

**Reboot resume.** A late-boot service looks for jobs DFTB marked resume-after-reboot in `/data/adb/dftb/journal` and wakes the app to carry on.

**Partition backup.** `partbak` reads partitions through `/dev/block/by-name` into a compressed image with a checksum, and writes them back on request. Backing up is read-only. Restore asks for a typed confirmation, refuses an image larger than the partition, and keeps identity partitions (modem EFS, persist, frp, devinfo and the like) behind `--force`.

## WebUI

Status, Partitions, Backups, Settings. Opens from KernelSU and APatch directly, from Magisk through WebUI-X Portable or KsuWebUIStandalone.

## partbak

    partbak list
    partbak backup boot --zstd
    partbak backup all                 skips userdata, super, cache, metadata
    partbak list-backups
    partbak verify <image>
    partbak restore <image> boot
    partbak restore <image> modemst1 --force

Backups go to `/data/adb/dftb/partitions` unless `--dest` says otherwise. Uninstalling the module leaves them in place.

## Build

    scripts/fetch_prebuilts.sh         fills app/src/main/jniLibs
    companion/pack.sh                  arm64 zip in companion/out/
    companion/pack.sh --abis arm64-v8a,x86_64

The version comes from `appVersionName` in `app/build.gradle.kts`. CI packs the module on every build and attaches it to tagged releases, then rewrites `update.json` so installed copies see the new version.

## Layout

    module/              what gets flashed
      customize.sh post-fs-data.sh service.sh uninstall.sh action.sh
      libexec/           partbak.sh, update-check.sh
      webroot/           the WebUI
    pack.sh              builds the zip
    update.json          read by root managers
