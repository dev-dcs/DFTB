package ca.devdcs.backup.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Severity of a [LogLine]; ordinal order matches android.util.Log priorities. */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** One captured log record. */
data class LogLine(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null,
    /** Monotonic id assigned by [LogBuffer] on append (0 = not yet buffered); stable list key. */
    val seq: Long = 0L,
)

/**
 * In-memory ring buffer of recent log lines, observable from Compose.
 *
 * This is what the live progress terminal renders and what the "export diagnostics"
 * action serialises. Bounded so a multi-hour backup cannot grow the heap without limit.
 */
@Singleton
class LogBuffer @Inject constructor() : DftbLog.Sink {

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val nextSeq = AtomicLong(1L)

    override fun write(line: LogLine) {
        val stamped = line.copy(seq = nextSeq.getAndIncrement())
        _lines.update { current ->
            if (current.size >= CAPACITY) current.drop(current.size - CAPACITY + 1) + stamped else current + stamped
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    companion object {
        const val CAPACITY = 2_000
    }
}
