package ca.devdcs.backup.core.pipeline.source

import ca.devdcs.backup.core.exec.root.RootScriptBuilder

/**
 * Pure shell-script builders and parsers behind [RootByteStreams]. Kept Android-free so the
 * quoting (security-sensitive on a root shell) and the status protocol are unit-tested on the JVM.
 */
internal object PrivilegedStreamScripts {

    /** Helper stderr kept for error messages (a failing `tar` over thousands of files is chatty). */
    const val MAX_STDERR_CHARS = 4000

    /**
     * base64 line width for the Shizuku transport. A multiple of 4, so every full line decodes on its
     * own to 36 KiB; ~96 KiB per binder transaction stays far below the 1 MB buffer.
     */
    const val BASE64_LINE_WIDTH = 49_152

    fun q(arg: String): String = RootScriptBuilder.shellQuote(arg)

    /** argv -> one shell command line with every token quoted. */
    fun command(argv: List<String>): String {
        require(argv.isNotEmpty()) { "empty argv" }
        return argv.joinToString(" ") { q(it) }
    }

    /**
     * Body of the detached root helper: open the data FIFO for writing (blocks until the app opens
     * the read end), run [script] with stdout into it and stderr captured, close it (EOF for the app),
     * then report `rc\nstderr` through the status FIFO.
     */
    fun fifoHelper(script: String, dataFifo: String, statusFifo: String): String =
        "exec 3>${q(dataFifo)} || exit 1; " +
            "__err=\$( { $script ; } 2>&1 >&3 3>&- ); __rc=\$?; exec 3>&-; " +
            "printf '%s\\n%s\\n' \"\$__rc\" \"\$(printf '%s' \"\$__err\" | tail -c $MAX_STDERR_CHARS)\" > ${q(statusFifo)}"

    /**
     * Launch [helper] detached from the shared root shell, in its own session when `setsid` exists
     * (so the whole process tree is one killable group), and print its PID.
     */
    fun detachedLaunch(helper: String): String {
        val inner = q(helper)
        return "if command -v setsid >/dev/null 2>&1; then setsid sh -c $inner </dev/null >/dev/null 2>&1 & " +
            "else sh -c $inner </dev/null >/dev/null 2>&1 & fi; echo \"\$!\""
    }

    /** Kill the helper's process group (setsid case), falling back to the PID itself. */
    fun killScript(pid: Int): String {
        require(pid > 1) { "refusing to kill pid $pid" }
        return "kill -9 -$pid 2>/dev/null || kill -9 $pid 2>/dev/null; true"
    }

    /** Shizuku transport: binary-safe text lines; `pipefail` keeps [script]'s exit status. */
    fun base64Pipeline(script: String): String =
        "set -o pipefail; { $script ; } | base64 -w $BASE64_LINE_WIDTH"

    /** The helper PID from the launch command's stdout. */
    fun parsePid(stdout: List<String>): Int? = stdout.asReversed().firstNotNullOfOrNull { it.trim().toIntOrNull() }?.takeIf { it > 1 }

    /** `rc\nstderr...` -> (rc, stderr); rc -1 when unparseable or empty. */
    fun parseStatus(text: String): Pair<Int, String> {
        if (text.isBlank()) return -1 to ""
        val code = text.substringBefore('\n').trim().toIntOrNull() ?: -1
        return code to text.substringAfter('\n', "").trim()
    }

    /** `cat` of one file (the `--` stops a name starting with '-' being read as an option). */
    fun catFile(path: String): String = "cat -- ${q(path)}"
}
