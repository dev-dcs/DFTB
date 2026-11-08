package ca.devdcs.backup.core.exec.root

import ca.devdcs.backup.core.exec.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pins the shell-line translation done by [RootScriptBuilder]. Because the output is fed verbatim
 * to a root shell, a regression here is a privilege-escalation / command-injection risk, so every
 * quoting and prefix-ordering rule is asserted explicitly.
 */
class RootScriptBuilderTest {

    @Test
    fun `plain argv is single-quoted token by token`() {
        val script = RootScriptBuilder.build(Command(argv = listOf("id", "-u")))
        assertEquals("'id' '-u'", script)
    }

    @Test
    fun `embedded single quote is escaped`() {
        assertEquals("""'it'\''s'""", RootScriptBuilder.shellQuote("it's"))
    }

    @Test
    fun `dollar and spaces are neutralised by quoting`() {
        val script = RootScriptBuilder.build(Command(argv = listOf("echo", "\$HOME is here")))
        assertEquals("'echo' '\$HOME is here'", script)
    }

    @Test
    fun `environment entries become a quoted env prefix`() {
        val script = RootScriptBuilder.build(
            Command(argv = listOf("tar", "-c"), environment = linkedMapOf("LANG" to "C", "TZ" to "UTC")),
        )
        assertEquals("env 'LANG=C' 'TZ=UTC' 'tar' '-c'", script)
    }

    @Test
    fun `working directory wraps the line in a cd guard`() {
        val script = RootScriptBuilder.build(
            Command(argv = listOf("ls"), workingDirectory = "/data/local/tmp"),
        )
        assertEquals("cd '/data/local/tmp' && 'ls'", script)
    }

    @Test
    fun `redirect error stream appends 2 to 1`() {
        val script = RootScriptBuilder.build(
            Command(argv = listOf("dd", "if=/dev/block/x"), redirectErrorStream = true),
        )
        assertEquals("'dd' 'if=/dev/block/x' 2>&1", script)
    }

    @Test
    fun `global mount namespace prepends nsenter`() {
        val script = RootScriptBuilder.build(
            Command(argv = listOf("cat", "/proc/mounts"), requireGlobalMountNamespace = true),
        )
        assertEquals("nsenter --target 1 --mount 'cat' '/proc/mounts'", script)
    }

    @Test
    fun `stdin redirect path is quoted and placed before the stderr fold`() {
        val script = RootScriptBuilder.build(
            Command(argv = listOf("openssl", "enc"), redirectErrorStream = true),
            stdinRedirectPath = "/data/user/0/ca.devdcs.backup/cache/dftb-stdin-1.bin",
        )
        assertEquals(
            "'openssl' 'enc' < '/data/user/0/ca.devdcs.backup/cache/dftb-stdin-1.bin' 2>&1",
            script,
        )
    }

    @Test
    fun `all features compose in the documented order`() {
        val script = RootScriptBuilder.build(
            Command(
                argv = listOf("sh", "-c", "tar -c ."),
                environment = mapOf("LANG" to "C"),
                workingDirectory = "/data",
                redirectErrorStream = true,
                requireGlobalMountNamespace = true,
            ),
            stdinRedirectPath = "/cache/in.bin",
        )
        assertEquals(
            "cd '/data' && nsenter --target 1 --mount env 'LANG=C' 'sh' '-c' 'tar -c .' < '/cache/in.bin' 2>&1",
            script,
        )
    }

    // The supervised wrapper is executed by a real POSIX sh below (the JVM host's /bin/sh), because
    // its job-control, `wait` and exit-status semantics are what keep the shared root shell usable.

    @Test
    fun `supervised script preserves the child's exit status and output`() {
        val pidFile = File.createTempFile("dftb-pid-", "").apply { delete() }
        val script = RootScriptBuilder.buildSupervised(
            Command(argv = listOf("sh", "-c", "echo hello; exit 7")),
            stdinRedirectPath = null,
            pidFilePath = pidFile.absolutePath,
        )
        val (code, out) = runSh("$script; echo \"rc=\$?\"")
        assertEquals(0, code)
        assertEquals(listOf("hello", "rc=7"), out)
        assertFalse("pid file must be removed once the child ends", pidFile.exists())
    }

    @Test
    fun `supervised child cannot read the shell's own stdin`() {
        val pidFile = File.createTempFile("dftb-pid-", "").apply { delete() }
        val script = RootScriptBuilder.buildSupervised(
            Command(argv = listOf("cat")),
            stdinRedirectPath = null,
            pidFilePath = pidFile.absolutePath,
        )
        // If the child inherited the shell's stdin it would consume the trailing echo command.
        val (_, out) = runSh("$script\necho after", feedScriptOnStdin = true)
        assertEquals(listOf("after"), out)
    }

    @Test
    fun `killing the recorded pid ends the wait so the shell is free again`() {
        val pidFile = File.createTempFile("dftb-pid-", "").apply { delete() }
        val script = RootScriptBuilder.buildSupervised(
            Command(argv = listOf("sleep", "30")),
            stdinRedirectPath = null,
            pidFilePath = pidFile.absolutePath,
        )
        val process = ProcessBuilder("/bin/sh", "-c", "$script; echo \"rc=\$?\"").start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!(pidFile.exists() && pidFile.readText().isNotBlank()) && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        val pid = pidFile.readText().trim()
        ProcessBuilder("/bin/sh", "-c", "kill -9 -- -$pid 2>/dev/null; kill -9 $pid 2>/dev/null").start().waitFor()
        assertTrue("shell must return promptly after the kill", process.waitFor(5, TimeUnit.SECONDS))
        val out = process.inputStream.bufferedReader().readLines()
        assertEquals(listOf("rc=137"), out)
    }

    private fun runSh(script: String, feedScriptOnStdin: Boolean = false): Pair<Int, List<String>> {
        val process = if (feedScriptOnStdin) {
            ProcessBuilder("/bin/sh").redirectErrorStream(true).start().also { p ->
                p.outputStream.bufferedWriter().use { it.write(script + "\n") }
            }
        } else {
            ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to process.inputStream.bufferedReader().readLines()
    }
}
