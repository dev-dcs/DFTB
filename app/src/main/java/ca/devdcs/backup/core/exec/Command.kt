package ca.devdcs.backup.core.exec

/**
 * A single command to run through a [CommandExecutor].
 *
 * Expressed as an explicit argv list rather than a shell string so the executor controls quoting;
 * an executor that must hand the command to `sh -c` is responsible for escaping [argv]. [stdin]
 * lets a pipeline feed bytes to a tool (e.g. `openssl` or `zstd`) without a temp file.
 */
data class Command(
    val argv: List<String>,
    /** Extra environment entries layered onto the executor's base environment. */
    val environment: Map<String, String> = emptyMap(),
    /** Absolute working directory, or null to inherit the executor's default. */
    val workingDirectory: String? = null,
    /** Bytes written to the process's stdin, or null for no input. */
    val stdin: ByteArray? = null,
    /** Fold stderr into stdout (tools that only report progress on stderr). */
    val redirectErrorStream: Boolean = false,
    /** Kill the command after this many ms; null = the executor's default timeout. */
    val timeoutMillis: Long? = null,
    /**
     * Require the global (init) mount namespace via `nsenter -t 1 -m`. Root tier only; ignored by
     * other tiers. Needed for consistent snapshots of `/data` and block partitions (Tier 4).
     */
    val requireGlobalMountNamespace: Boolean = false,
) {
    init {
        require(argv.isNotEmpty()) { "Command argv must not be empty" }
    }

    /** A shell-ready string purely for logging; never fed back to a shell. */
    fun displayString(): String = argv.joinToString(" ") { arg ->
        if (arg.any { it.isWhitespace() || it in "\"'$`\\" }) "'" + arg.replace("'", "'\\''") + "'" else arg
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Command) return false
        return argv == other.argv &&
            environment == other.environment &&
            workingDirectory == other.workingDirectory &&
            stdin.contentEqualsNullable(other.stdin) &&
            redirectErrorStream == other.redirectErrorStream &&
            timeoutMillis == other.timeoutMillis &&
            requireGlobalMountNamespace == other.requireGlobalMountNamespace
    }

    override fun hashCode(): Int {
        var result = argv.hashCode()
        result = 31 * result + environment.hashCode()
        result = 31 * result + (workingDirectory?.hashCode() ?: 0)
        result = 31 * result + (stdin?.contentHashCode() ?: 0)
        result = 31 * result + redirectErrorStream.hashCode()
        result = 31 * result + (timeoutMillis?.hashCode() ?: 0)
        result = 31 * result + requireGlobalMountNamespace.hashCode()
        return result
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && this.contentEquals(other)
}
