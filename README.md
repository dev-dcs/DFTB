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
```

The prebuilts are pinned and checksum-verified, and are not committed to git. SDK levels and the NDK version are set in `gradle.properties`.

For a signed release, copy `keystore.properties.example` to `keystore.properties`, or export the `DFTB_*` variables. CI takes them from the repo secrets.

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
