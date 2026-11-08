package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.DestinationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RcloneTargetTest {

    @Test
    fun `named remote joins base path`() {
        val target = RcloneTarget.named("gdrive", "/Backups/phone/")
        assertEquals("gdrive:Backups/phone", target.spec(""))
        assertEquals("gdrive:Backups/phone/2024/a.tar", target.spec("/2024/a.tar"))
        assertEquals("gdrive:", RcloneTarget.named("gdrive", null).spec("/"))
        assertTrue(target.environment.isEmpty())
    }

    @Test
    fun `named remote rejects an invalid name`() {
        try {
            RcloneTarget.named("bad:name", null)
            fail()
        } catch (e: StorageException) {
            assertEquals(StorageException.Reason.MISCONFIGURED, e.reason)
        }
    }

    @Test
    fun `connection string quoting`() {
        assertEquals("AWS", RcloneTarget.quoteParam("AWS"))
        assertEquals("\"https://minio.local:9000\"", RcloneTarget.quoteParam("https://minio.local:9000"))
        assertEquals("\"a,b\"", RcloneTarget.quoteParam("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", RcloneTarget.quoteParam("say \"hi\""))
        assertEquals("\"\"", RcloneTarget.quoteParam(""))
    }

    @Test
    fun `s3 on the fly keeps secrets out of the spec`() {
        val config = DestinationConfig(
            basePath = "my-bucket/phone",
            options = mapOf("provider" to "Wasabi", "region" to "eu-central-1", "endpoint" to "s3.eu-central-1.wasabisys.com", "rclone.no_check_bucket" to "true"),
        )
        val target = RcloneTargets.s3(config, accessKeyId = "AKID", secretAccessKey = "s3cr3tValue")
        val spec = target.spec("daily/a.tar")
        assertEquals(":s3,provider=Wasabi,region=eu-central-1,endpoint=s3.eu-central-1.wasabisys.com,no_check_bucket=true:my-bucket/phone/daily/a.tar", spec)
        assertFalse(spec.contains("s3cr3tValue"))
        assertFalse(spec.contains("AKID"))
        assertEquals(mapOf("RCLONE_S3_ACCESS_KEY_ID" to "AKID", "RCLONE_S3_SECRET_ACCESS_KEY" to "s3cr3tValue"), target.environment)
        assertFalse(target.toString().contains("s3cr3tValue"))
    }

    @Test
    fun `s3 defaults provider from endpoint presence and host fallback`() {
        val aws = RcloneTargets.s3(DestinationConfig(basePath = "b"), "k", "s")
        assertEquals(":s3,provider=AWS:b", aws.spec(""))
        val other = RcloneTargets.s3(DestinationConfig(host = "https://nas.lan", port = 9000, basePath = "b"), "k", "s")
        assertEquals(":s3,provider=Other,endpoint=\"https://nas.lan:9000\":b", other.spec(""))
    }

    @Test
    fun `s3 requires bucket and keys`() {
        expectReason(StorageException.Reason.MISCONFIGURED) { RcloneTargets.s3(DestinationConfig(basePath = "/"), "k", "s") }
        expectReason(StorageException.Reason.AUTH) { RcloneTargets.s3(DestinationConfig(basePath = "b"), null, "s") }
        expectReason(StorageException.Reason.AUTH) { RcloneTargets.s3(DestinationConfig(basePath = "b"), "k", "") }
    }

    @Test
    fun `secret-looking passthrough options go to env, never the spec or toString`() {
        val target = RcloneTargets.s3(
            DestinationConfig(basePath = "b", options = mapOf("rclone.sse_customer_key" to "CUSTKEY", "rclone.session_token" to "TOK", "rclone.sse_kms_key_id" to "kms1")),
            "k", "s",
        )
        val spec = target.spec("")
        assertFalse(spec.contains("CUSTKEY"))
        assertFalse(spec.contains("TOK"))
        assertTrue(spec.contains("sse_kms_key_id=kms1"))
        assertEquals("CUSTKEY", target.environment["RCLONE_S3_SSE_CUSTOMER_KEY"])
        assertEquals("TOK", target.environment["RCLONE_S3_SESSION_TOKEN"])
        assertFalse(target.toString().contains("kms1"))
        expectReason(StorageException.Reason.MISCONFIGURED) {
            RcloneTargets.s3(DestinationConfig(basePath = "b", options = mapOf("rclone.Bad-Key" to "x")), "k", "s")
        }
    }

    @Test
    fun `mega uses env for user and obscured pass`() {
        val target = RcloneTargets.mega(DestinationConfig(basePath = "Backups"), "me@example.com", "OBSCURED")
        assertEquals(":mega:Backups/x", target.spec("x"))
        assertEquals(mapOf("RCLONE_MEGA_USER" to "me@example.com", "RCLONE_MEGA_PASS" to "OBSCURED"), target.environment)
    }

    @Test
    fun `root script sources env, redirects and preserves exit code`() {
        val script = RootRcloneScript.build(
            binary = "/data/adb/modules/dftb/system/bin/rclone",
            args = listOf("rcat", "--config", "/data/user/0/app/files/rclone/rclone.conf", "it's:x"),
            envFile = "/f/env-1",
            logFile = "/f/log-1",
            redirect = RootRcloneScript.Redirect.StdinFrom("/c/fifo"),
            repairDirs = listOf("/data/user/0/app/files/rclone"),
            appUid = 10123,
        )
        assertTrue(script.startsWith(". '/f/env-1' && rm -f '/f/env-1' && '/data/adb/modules/dftb/system/bin/rclone' 'rcat' "))
        assertTrue(script.contains("'it'\\''s:x' 2>>'/f/log-1' <'/c/fifo'; rc=\$?"))
        assertTrue(script.contains("chown -R 10123:10123 '/data/user/0/app/files/rclone'"))
        assertTrue(script.contains("chcon -R \"\$ctx\" '/data/user/0/app/files/rclone'"))
        assertTrue(script.endsWith("; exit \$rc"))
    }

    @Test
    fun `detached script reports status, records pid and kill targets the group`() {
        val inner = RootRcloneScript.build("/bin/rclone", listOf("cat"), "/f/env", "/f/log", RootRcloneScript.Redirect.StdoutTo("/c/fifo"), listOf("/d"), 1, statusFile = "/f/status")
        assertTrue(inner.endsWith("; printf '%s\\n' \"\$rc\" > '/f/status'"))
        assertFalse(inner.contains("exit \$rc"))
        assertTrue(inner.contains("chown -R 1:1 '/d'"))
        val launcher = RootRcloneScript.detached(inner, "/f/pid")
        assertTrue(launcher.startsWith("setsid sh -c '"))
        assertTrue(launcher.endsWith(" </dev/null >/dev/null 2>&1 & echo \$! > '/f/pid'"))
        assertTrue(RootRcloneScript.kill("/f/pid").contains("kill -9 -- -\"\$p\""))
        assertEquals("true", RootRcloneScript.repair(emptyList(), 1))
    }

    @Test
    fun `upload tuning flags per backend`() {
        val s3 = listOf("--s3-chunk-size", "64M", "--s3-upload-concurrency", "2")
        assertEquals(s3, RcloneTargets.s3(DestinationConfig(basePath = "b"), "k", "s").uploadFlags)
        assertEquals(s3, RcloneTarget.named("aws", "b", "s3").uploadFlags)
        assertEquals(listOf("--b2-chunk-size", "96M", "--b2-upload-concurrency", "2"), RcloneTarget.named("bb", null, "b2").uploadFlags)
        assertTrue(RcloneTarget.named("gd", null, "drive").uploadFlags.isEmpty())
        assertTrue(RcloneTarget.named("x", null).uploadFlags.isEmpty())
        assertTrue(RcloneTargets.mega(DestinationConfig(), "u", "p").uploadFlags.isEmpty())
    }

    @Test
    fun `env file exports quoted values and validates names`() {
        assertEquals("export A='x'\\''y'\nexport B_2=''\n", RootRcloneScript.envFileContent(linkedMapOf("A" to "x'y", "B_2" to "")))
        try {
            RootRcloneScript.envFileContent(mapOf("BAD;rm" to "x"))
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun expectReason(reason: StorageException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: StorageException) {
            assertEquals(reason, e.reason)
        }
    }
}
