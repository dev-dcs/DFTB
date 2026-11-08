package ca.devdcs.backup.core.nativebridge

/** Result of lstat(2) plus the SELinux label. Constructed from JNI: keep the order in sync. */
data class NativeFileStat(
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val modifiedAtMillis: Long,
    val inode: Long,
    val device: Long,
    val selinuxContext: String,
) {
    val isDirectory: Boolean get() = (mode and S_IFMT) == S_IFDIR
    val isRegularFile: Boolean get() = (mode and S_IFMT) == S_IFREG
    val isSymlink: Boolean get() = (mode and S_IFMT) == S_IFLNK
    val isBlockDevice: Boolean get() = (mode and S_IFMT) == S_IFBLK
    val permissionBits: Int get() = mode and PERMISSION_MASK
    val octalPermissions: String get() = "%04o".format(permissionBits)

    private companion object {
        const val S_IFMT = 0b1111_000_000_000_000
        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
        const val S_IFLNK = 0xA000
        const val S_IFBLK = 0x6000
        const val PERMISSION_MASK = 0b111_111_111_111
    }
}
