package ca.devdcs.backup.core.restore.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class AppRestoreScriptsTest {

    @Test
    fun `install-create builds replace, user, pkg, downgrade, installer and size flags`() {
        assertEquals(
            "'pm' 'install-create' '-r' '--user' '0' '--pkg' 'com.x' '-S' '123'",
            AppRestoreScripts.installCreate("com.x", 0, 123, downgrade = false, installer = null),
        )
        assertEquals(
            "'pm' 'install-create' '-r' '--user' '10' '--pkg' 'com.x' '-d' '-i' 'com.android.vending'",
            AppRestoreScripts.installCreate("com.x", 10, null, downgrade = true, installer = "com.android.vending"),
        )
        // An installer that is not a package name is dropped, never passed to the shell.
        assertEquals("'pm' 'install-create' '-r' '--user' '0' '--pkg' 'com.x'", AppRestoreScripts.installCreate("com.x", 0, null, false, "x; rm -rf /"))
        try {
            AppRestoreScripts.installCreate("../x", 0, null, false, null)
            org.junit.Assert.fail("invalid package accepted")
        } catch (expected: IllegalArgumentException) {
            // refused
        }
    }

    @Test
    fun `install-write streams stdin through cat with pipefail and quotes the split name`() {
        val script = AppRestoreScripts.installWriteFromStdin(42, "split_config.arm64_v8a.apk", 999)
        assertTrue(script.startsWith("set -o pipefail 2>/dev/null; cat | "))
        assertTrue(script.endsWith("'pm' 'install-write' '-S' '999' '42' 'split_config.arm64_v8a.apk' '-'"))
        val staged = AppRestoreScripts.installWriteFromFile(7, "base.apk", 5, "/storage/emulated/0/Android/data/x/f 'q'")
        assertTrue(staged.contains("cat -- '/storage/emulated/0/Android/data/x/f '\\''q'\\''' | "))
    }

    @Test
    fun `pm output parsing`() {
        assertEquals(1234, AppRestoreScripts.parseSessionId(listOf("Success: created install session [1234]")))
        assertNull(AppRestoreScripts.parseSessionId(listOf("Error: something [12]")))
        assertTrue(AppRestoreScripts.isPmSuccess(0, listOf("Success")))
        assertFalse(AppRestoreScripts.isPmSuccess(0, listOf("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: x]")))
        assertFalse(AppRestoreScripts.isPmSuccess(1, listOf("Success")))
        assertEquals("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", AppRestoreScripts.pmFailure(listOf("", "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")))
    }

    @Test
    fun `package uid is the exact package for the requested user`() {
        val out = listOf("package:com.example.extra uid:10200", "package:com.example uid:10123,1010123")
        assertEquals(10123, AppRestoreScripts.parsePackageUid(out, "com.example", 0))
        assertEquals(1_010_123, AppRestoreScripts.parsePackageUid(out, "com.example", 10))
        // Only the per-user-0 uid listed: derive the user's uid from the appId.
        assertEquals(1_110_200, AppRestoreScripts.parsePackageUid(listOf("package:com.example.extra uid:10200"), "com.example.extra", 11))
        assertNull(AppRestoreScripts.parsePackageUid(out, "com.exam", 0))
        assertEquals(setOf(0, 10), AppRestoreScripts.parseUserIds(listOf("Users:", "\tUserInfo{0:Owner:c13} running", "\tUserInfo{10:Work profile:1030}")))
    }

    @Test
    fun `grant script quotes every permission and output is parsed`() {
        val script = AppRestoreScripts.grantScript("com.example", 0, listOf("android.permission.CAMERA", "a'b"))
        assertTrue(script.contains("'android.permission.CAMERA' 'a'\\''b'"))
        assertTrue(script.contains("'pm' 'grant' '--user' '0' 'com.example' \"\$__p\""))
        val outcome = AppRestoreScripts.parseGrantOutput(listOf("ok android.permission.CAMERA", "fail android.permission.READ_SMS java.lang.SecurityException: hard restricted"))
        assertEquals(listOf("android.permission.CAMERA"), outcome.granted)
        assertEquals("java.lang.SecurityException: hard restricted", outcome.failed["android.permission.READ_SMS"])
    }

    @Test
    fun `data dir scripts never touch lib and refuse symlinks`() {
        val wipe = AppRestoreScripts.wipeDirScript("/data/user/0/com.example")
        assertTrue(wipe.contains("if [ -L '/data/user/0/com.example' ]; then"))
        assertTrue(wipe.contains("! -name lib -exec rm -rf {} +"))
        val chown = AppRestoreScripts.chownDataDirScript("/data/data/com.x", Owner(10200, 10200), 20200)
        assertTrue(chown.contains("chown -h 10200:10200 '/data/data/com.x' || exit 1;"))
        assertTrue(chown.contains("-path '/data/data/com.x/lib' -prune"))
        assertTrue(chown.contains("find '/data/data/com.x/cache' -exec chown -h 10200:20200 {} +"))
        assertTrue(chown.contains("find '/data/data/com.x/code_cache' -exec chown -h 10200:20200 {} +"))
        assertEquals("'tar' '-xpf' '-' '-C' '/data/user_de/0'", AppRestoreScripts.extractFromStdin("/data/user_de/0"))
        assertEquals("'am' 'force-stop' '--user' '10' 'com.x'", AppRestoreScripts.forceStop("com.x", 10))
    }

    @Test
    fun `parsers of dir states, restorecon and stat`() {
        val states = AppRestoreScripts.parseDirStates(listOf("data /data/data/a b", "missing /data/user_de/0/a", "weird /x", "empty /y"))
        assertEquals(AppRestoreScripts.DirState.DATA, states["/data/data/a b"])
        assertEquals(AppRestoreScripts.DirState.MISSING, states["/data/user_de/0/a"])
        assertEquals(AppRestoreScripts.DirState.EMPTY, states["/y"])
        assertNull(states["/x"])
        assertTrue(AppRestoreScripts.parseRestorecon(listOf("missing")).toolMissing)
        val rc = AppRestoreScripts.parseRestorecon(listOf("ok /data/data/a", "fail /data/user_de/0/a"))
        assertEquals(setOf("/data/data/a"), rc.ok)
        assertEquals(setOf("/data/user_de/0/a"), rc.failed)
        val owners = AppRestoreScripts.parseStatOwner(listOf("1023 1078 u:object_r:media_rw_data_file:s0 /data/media/0/Android/data", "bad line"))
        assertEquals(Owner(1023, 1078), owners["/data/media/0/Android/data"]?.owner)
        assertEquals("u:object_r:media_rw_data_file:s0", owners["/data/media/0/Android/data"]?.context)
    }

    @Test
    fun `sink helper reads the data fifo as stdin and reports through the status fifo`() {
        val helper = PrivilegedSinkFactory.sinkHelper("tar -xpf - -C '/d'", "/c/d p", "/c/s")
        assertTrue(helper.startsWith("exec 3<'/c/d p' || exit 1; "))
        assertTrue(helper.contains("{ tar -xpf - -C '/d' ; } <&3 2>&1 3<&-"))
        assertTrue(helper.indexOf("exec 3<&-;") < helper.indexOf("> '/c/s'"))
    }

    // ---- scripts executed in a real POSIX shell --------------------------------------------

    private fun sh(script: String): List<String> {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readLines()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return out
    }

    @Test
    fun `wipe keeps lib and dir-state ignores lib and cache`() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = Files.createTempDirectory("dftb-wipe").toFile()
        try {
            val app = File(root, "com.e x")
            File(app, "lib").mkdirs()
            File(app, "cache").mkdirs()
            File(app, "databases").mkdirs()
            File(app, "databases/a.db").writeText("x")
            File(app, ".hidden").writeText("x")
            val missing = File(root, "missing").path
            assertEquals(
                mapOf(app.path to AppRestoreScripts.DirState.DATA, missing to AppRestoreScripts.DirState.MISSING),
                AppRestoreScripts.parseDirStates(sh(AppRestoreScripts.dataDirStateScript(listOf(app.path, missing)))),
            )
            sh(AppRestoreScripts.wipeDirScript(app.path))
            assertEquals(setOf("lib"), app.list()!!.toSet())
            assertEquals(
                AppRestoreScripts.DirState.EMPTY,
                AppRestoreScripts.parseDirStates(sh(AppRestoreScripts.dataDirStateScript(listOf(app.path))))[app.path],
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `symlink probe finds app-made links but ignores lib when asked`() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = Files.createTempDirectory("dftb-links").toFile()
        try {
            val app = File(root, "com.x").apply { mkdirs() }
            Files.createSymbolicLink(File(app, "lib").toPath(), root.toPath())
            File(app, "files").mkdirs()
            assertTrue(sh(AppRestoreScripts.findSymlinkScript(app.path, keepLib = true)).none { it.isNotBlank() })
            assertTrue(sh(AppRestoreScripts.findSymlinkScript(app.path, keepLib = false)).any { it.endsWith("/lib") })
            Files.createSymbolicLink(File(app, "files/d").toPath(), File("/").toPath())
            assertTrue(sh(AppRestoreScripts.findSymlinkScript(app.path, keepLib = true)).any { it.endsWith("/files/d") })
            assertTrue(sh(AppRestoreScripts.findSymlinkScript(File(root, "absent").path, keepLib = true)).none { it.isNotBlank() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `wipe refuses a symlinked data dir`() {
        assumeTrue(File("/bin/sh").canExecute())
        val root = Files.createTempDirectory("dftb-wipe-link").toFile()
        try {
            val target = File(root, "target").apply { mkdirs() }
            File(target, "keep").writeText("x")
            val link = File(root, "link")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val out = sh(AppRestoreScripts.wipeDirScript(link.path) + "; echo rc=\$?")
            assertTrue(out.any { it.contains("refusing symlinked data dir") })
            assertTrue(File(target, "keep").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
