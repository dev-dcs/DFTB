package ca.devdcs.backup.core.exec

/**
 * The buffered outcome of a [Command]. Streaming callers use [CommandExecutor.stream] instead; this
 * is for short commands (probes, `id`, `getprop`, `command -v`) whose whole output fits in memory.
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val durationMillis: Long,
    /** False when the command could not be launched at all (no shell, bind failure, timeout). */
    val launched: Boolean = true,
) {
    val isSuccess: Boolean get() = launched && exitCode == EXIT_OK
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")

    companion object {
        const val EXIT_OK = 0
        /** Sentinel for "the command never ran" (executor unavailable / launch failure). */
        const val EXIT_NOT_LAUNCHED = -1
        /** Sentinel for a command killed by the executor's timeout. */
        const val EXIT_TIMEOUT = -2

        fun notLaunched(reason: String, durationMillis: Long = 0): CommandResult =
            CommandResult(EXIT_NOT_LAUNCHED, emptyList(), listOf(reason), durationMillis, launched = false)
    }
}

/** One line (or terminal exit) from [CommandExecutor.stream], for live progress plumbing. */
sealed interface CommandEvent {
    data class Stdout(val line: String) : CommandEvent
    data class Stderr(val line: String) : CommandEvent
    /** Final event of a stream; [exitCode] uses the same sentinels as [CommandResult]. */
    data class Exit(val exitCode: Int, val durationMillis: Long) : CommandEvent
}

/** Thrown for unrecoverable executor faults (binder death, su denied mid-run). */
class CommandException(message: String, cause: Throwable? = null) : Exception(message, cause)
