package ca.devdcs.backup.core.logging

import android.util.Log

/**
 * Project-wide logger. Writes to logcat with a `DFTB/` prefix and mirrors every record into an
 * installed [Sink] (the [LogBuffer]) so the UI can show a live tail without reading logcat.
 *
 * Verbose/debug records are dropped in release builds unless [install] is told otherwise.
 */
object DftbLog {

    fun interface Sink {
        fun write(line: LogLine)
    }

    private const val PREFIX = "DFTB/"

    @Volatile private var sink: Sink? = null
    @Volatile private var verbose: Boolean = true

    fun install(sink: Sink, verbose: Boolean) {
        this.sink = sink
        this.verbose = verbose
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message, null)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (!verbose && level < LogLevel.INFO) return
        val fullTag = PREFIX + tag
        when (level) {
            LogLevel.VERBOSE -> Log.v(fullTag, message, throwable)
            LogLevel.DEBUG -> Log.d(fullTag, message, throwable)
            LogLevel.INFO -> Log.i(fullTag, message, throwable)
            LogLevel.WARN -> Log.w(fullTag, message, throwable)
            LogLevel.ERROR -> Log.e(fullTag, message, throwable)
        }
        sink?.write(
            LogLine(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                throwable = throwable?.let { Log.getStackTraceString(it) },
            ),
        )
    }
}
