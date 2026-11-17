package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.core.logging.LogLine
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Device and build facts written at the top of an exported log, so a bug report is self-describing. */
data class LogExportHeader(
    val appName: String,
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val packageName: String,
    val device: String,
    val androidRelease: String,
    val apiLevel: Int,
    /** Storage key of the operating mode, or null when onboarding never chose one. */
    val operatingMode: String?,
    val exportedAtMillis: Long,
)

/**
 * Renders the in-memory LogBuffer as plain text for sharing or saving. Pure so the exact layout a
 * maintainer receives is unit-tested; timestamps are ISO-like with the zone offset because bug
 * reports cross time zones. The buffer already excludes secrets by design (DftbLog callers never
 * log them), so nothing is redacted here.
 */
object LogExportFormatter {

    private val lineTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val headerTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
    private val fileTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    fun levelLetter(level: LogLevel): Char = when (level) {
        LogLevel.VERBOSE -> 'V'
        LogLevel.DEBUG -> 'D'
        LogLevel.INFO -> 'I'
        LogLevel.WARN -> 'W'
        LogLevel.ERROR -> 'E'
    }

    fun format(lines: List<LogLine>, header: LogExportHeader, zone: ZoneId): String = buildString {
        append("# ").append(header.appName).append(" log export\n")
        append("# Exported: ").append(headerTime.format(Instant.ofEpochMilli(header.exportedAtMillis).atZone(zone))).append('\n')
        append("# App: ").append(header.appVersion).append(" (").append(header.versionCode).append(", ")
            .append(header.buildType).append(") ").append(header.packageName).append('\n')
        append("# Device: ").append(header.device).append(", Android ").append(header.androidRelease)
            .append(" (API ").append(header.apiLevel).append(")\n")
        append("# Operating mode: ").append(header.operatingMode ?: "not configured").append('\n')
        append("# Lines: ").append(lines.size).append('\n')
        append('\n')
        lines.forEach { line ->
            append(lineTime.format(Instant.ofEpochMilli(line.timestampMillis).atZone(zone)))
            append(' ').append(levelLetter(line.level)).append('/').append(line.tag).append(": ")
            appendIndentedContinuation(line.message)
            append('\n')
            line.throwable?.takeIf { it.isNotBlank() }?.let { trace ->
                trace.trimEnd().lineSequence().forEach { append("    ").append(it).append('\n') }
            }
        }
    }

    /** `dftb-log-20261020-140305.txt`: sortable, no characters any provider rejects. */
    fun fileName(nowMillis: Long, zone: ZoneId): String =
        "dftb-log-" + fileTime.format(Instant.ofEpochMilli(nowMillis).atZone(zone)) + ".txt"

    /** Multi-line messages keep every continuation line visibly attached to its record. */
    private fun StringBuilder.appendIndentedContinuation(message: String) {
        val parts = message.trimEnd().lines()
        parts.forEachIndexed { index, part ->
            if (index > 0) append('\n').append("    ")
            append(part)
        }
    }
}

/** Result of emptying a directory: what was freed and how many entries could not be removed. */
data class ClearOutcome(val freedBytes: Long, val failedEntries: Int)

/**
 * Size accounting and clearing for app cache directories. Uses NIO tree walks that never follow
 * symbolic links, so a link planted in the cache (root helpers create FIFOs and temp files there)
 * can neither inflate the total nor make a clear delete anything outside the directory.
 */
object CacheFiles {

    /** Total bytes of regular files under [root] (links counted as themselves); 0 when absent. */
    fun sizeOf(root: File): Long {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L
        var total = 0L
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return total
    }

    /** Deletes everything inside [root] but keeps [root] itself, so writers holding the path stay valid. */
    fun clearContents(root: File): ClearOutcome {
        val path = root.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return ClearOutcome(0L, 0)
        var freed = 0L
        var failed = 0
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val size = if (attrs.isRegularFile) attrs.size() else 0L
                    if (deleteQuietly(file)) freed += size else failed++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    failed++
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (dir != path && !deleteQuietly(dir)) failed++
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return ClearOutcome(freed, failed)
    }

    private fun deleteQuietly(path: Path): Boolean = try {
        Files.deleteIfExists(path)
        true
    } catch (error: IOException) {
        false
    } catch (error: SecurityException) {
        false
    }
}
