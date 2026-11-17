# DFTB — Don't Forget To Backup

Backup and disaster recovery for your own Android phone. It works without root, and does more once you give it Shizuku, root, or the companion module.

- Apps, files and partitions, with zstd/xz compression and AES-256-GCM encryption
- Save to local storage, USB OTG, SFTP, SMB, FTP or any rclone remote
- Scheduled backups, retention rules, resume after reboot
- Restores, including TWRP Nandroid backups
- Available in English, Français, Español, 中文, 日本語, Ελληνικά, العربية

## Build

JDK 21, Android SDK platform 37, NDK 27.1 and CMake 3.22.1.

```bash
scripts/fetch_prebuilts.sh      # rclone, busybox, zstd, nsenter -> app/src/main/jniLibs
./gradlew :app:assembleDebug
companion/pack.sh               # optional: the root module -> companion/out/
```

## Companion module

`companion/` is the root module (Magisk, KernelSU, APatch) that goes with the app. It is built from this repo, carries the app's version, and ships the same tool binaries as the APK.

- `rclone`, `busybox`, `zstd`, `nsenter` and `tar` on `PATH`
- wakes the app after a reboot to resume interrupted jobs
- partition backup and restore from a WebUI or with `partbak` in a shell
- update notice in the root manager through `companion/update.json`

Each tagged release attaches `DFTB-Companion-v<version>.zip` next to the APK. More in [companion/README.md](companion/README.md).

## Credits

Thanks to the projects that do the heavy lifting:

- [rclone](https://github.com/rclone/rclone), [busybox-ndk](https://github.com/Magisk-Modules-Repo/busybox-ndk), [zstd](https://github.com/facebook/zstd) and [zstd-jni](https://github.com/luben/zstd-jni)
- [libsu](https://github.com/topjohnwu/libsu) and [Magisk](https://github.com/topjohnwu/Magisk) by topjohnwu, [Shizuku](https://github.com/RikkaApps/Shizuku), [KernelSU](https://github.com/tiann/KernelSU), [APatch](https://github.com/bmax121/APatch)
- [sshj](https://github.com/hierynomus/sshj) and [smbj](https://github.com/hierynomus/smbj), [Apache Commons](https://commons.apache.org), [xz-java](https://github.com/tukaani-project/xz-java), [Bouncy Castle](https://github.com/bcgit/bc-java), [OkHttp](https://github.com/square/okhttp)

The app also has an in-app open-source licenses screen with the full list.

## License

GPL-3.0. See [LICENSE](LICENSE).

---

Made by **@Dev_DcS**
