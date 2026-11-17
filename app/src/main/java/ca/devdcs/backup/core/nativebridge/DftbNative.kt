package ca.devdcs.backup.core.nativebridge

import ca.devdcs.backup.core.logging.DftbLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin side of libdftb_native.so (app/src/main/cpp/dftb_native.cpp).
 *
 * Loading is attempted once; every public helper degrades to `null`/`false` if the library is
 * absent so a missing ABI never crashes the app, it only disables the precise native paths.
 */
@Singleton
class DftbNative @Inject constructor() {

    val isLoaded: Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        true
    } catch (error: UnsatisfiedLinkError) {
        DftbLog.e(TAG, "Native library $LIBRARY_NAME unavailable", error)
        false
    }

    // --- JNI declarations -----------------------------------------------------------------------
    private external fun statVfs(path: String): NativeFsStats?
    private external fun lstat(path: String): NativeFileStat?
    private external fun blockDeviceSize(path: String): Long
    private external fun fsyncPath(path: String): Boolean
    private external fun access(path: String, mode: Int): Boolean
    private external fun chmod(path: String, mode: Int): Boolean
    private external fun currentUid(): Int
    private external fun libraryVersion(): String
    private external fun lastErrorMessage(): String

    // --- Safe wrappers --------------------------------------------------------------------------

    /** Filesystem totals for the mount containing [path], or null if unavailable. */
    fun filesystemStats(path: String): NativeFsStats? = guarded { statVfs(path) }

    /** lstat (does not follow symlinks) with SELinux context, or null if the path is unreadable. */
    fun fileStat(path: String): NativeFileStat? = guarded { lstat(path) }

    /** Byte size of a block device node, or null if unreadable / not a block device. */
    fun blockDeviceSizeBytes(path: String): Long? = guarded { blockDeviceSize(path).takeIf { it >= 0 } }

    /** Durably flush a finished file to storage. */
    fun fsync(path: String): Boolean = guarded { fsyncPath(path) } ?: false

    fun isExecutable(path: String): Boolean = guarded { access(path, X_OK) } ?: false
    fun isReadable(path: String): Boolean = guarded { access(path, R_OK) } ?: false
    fun isWritable(path: String): Boolean = guarded { access(path, W_OK) } ?: false

    /** chmod with an octal mode such as 0o755. */
    fun setMode(path: String, mode: Int): Boolean = guarded { chmod(path, mode) } ?: false

    fun processUid(): Int? = guarded { currentUid() }
    fun version(): String? = guarded { libraryVersion() }
    fun lastError(): String? = guarded { lastErrorMessage() }

    private inline fun <T> guarded(block: () -> T): T? {
        if (!isLoaded) return null
        return try {
            block()
        } catch (error: UnsatisfiedLinkError) {
            DftbLog.e(TAG, "Native symbol missing", error)
            null
        }
    }

    companion object {
        const val LIBRARY_NAME = "dftb_native"
        private const val TAG = "Native"
        const val R_OK = 4
        const val W_OK = 2
        const val X_OK = 1
    }
}
