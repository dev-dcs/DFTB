package ca.devdcs.backup.core.nativebridge

/** Result of statvfs(2). Constructed from JNI: keep the parameter order and types in sync. */
data class NativeFsStats(
    val totalBytes: Long,
    val freeBytes: Long,
    val availableBytes: Long,
    val blockSize: Long,
    val totalInodes: Long,
    val freeInodes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}
