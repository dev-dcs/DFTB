package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.rclone.RcloneConfigBuilder.Problem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneConfigBuilderTest {

    private val obscure: suspend (String) -> String = { "OBS(" + it + ")" }

    private val token = """{"access_token":"ya29.a0","token_type":"Bearer","refresh_token":"1//0g","expiry":"2026-10-19T12:00:00.123456789+02:00"}"""

    private fun build(guide: ProviderGuide, name: String, values: Map<String, String>) =
        runBlocking { RcloneConfigBuilder.build(guide, name, values, obscure) }

    @Test
    fun `aws section has type first, fixed options and default region`() {
        val result = build(ProviderGuides.S3_AWS, "aws", mapOf("access_key_id" to " AKID ", "secret_access_key" to "SECRET"))
        result as RcloneConfigBuilder.Result.Success
        assertEquals(
            "[aws]\ntype = s3\nprovider = AWS\nenv_auth = false\naccess_key_id = AKID\nsecret_access_key = SECRET\nregion = us-east-1\n",
            result.iniText,
        )
        assertTrue(RcloneIni.parse(result.iniText).isValid)
    }

    @Test
    fun `obscured fields go through the obscurer`() {
        val result = build(ProviderGuides.MEGA, "mega", mapOf("user" to "me@example.com", "pass" to "hunter2"))
        result as RcloneConfigBuilder.Result.Success
        assertEquals("OBS(hunter2)", result.section.options["pass"])
        assertEquals("me@example.com", result.section.options["user"])
    }

    @Test
    fun `oauth token pasted with rclone markers is normalized to one line`() {
        val pasted = "Paste the following into your remote machine --->\n$token\n<---End paste\n"
        val result = build(ProviderGuides.GOOGLE_DRIVE, "gdrive", mapOf("token" to pasted))
        result as RcloneConfigBuilder.Result.Success
        val stored = result.section.options["token"]!!
        assertFalse(stored.contains('\n'))
        assertTrue(stored.startsWith("{\"access_token\":\"ya29.a0\""))
        assertEquals("drive", result.section.options["scope"])
        assertTrue(result.warnings.isEmpty())
        assertEquals(stored, RcloneIni.parse(result.iniText).section("gdrive")!!.options["token"])
    }

    @Test
    fun `token without refresh token warns`() {
        val result = build(ProviderGuides.DROPBOX, "db", mapOf("token" to """{"access_token":"x","token_type":"bearer"}"""))
        result as RcloneConfigBuilder.Result.Success
        assertEquals(listOf(RcloneConfigBuilder.Warning.TOKEN_WITHOUT_REFRESH), result.warnings)
    }

    @Test
    fun `invalid token is reported with its problem`() {
        val result = build(ProviderGuides.BOX, "box", mapOf("token" to """{"token_type":"bearer"}"""))
        result as RcloneConfigBuilder.Result.Failure
        val error = result.errors.single()
        assertEquals("token", error.key)
        assertEquals(Problem.TOKEN_INVALID, error.problem)
        assertEquals(OAuthTokenValidator.Problem.MISSING_ACCESS_TOKEN, error.tokenProblem)
    }

    @Test
    fun `field validation errors`() {
        val errors = RcloneConfigBuilder.validate(
            ProviderGuides.WEBDAV_NEXTCLOUD,
            "-bad",
            mapOf("url" to "cloud.example.com", "user" to "", "pass" to "x", "bogus" to "1"),
        )
        val byKey = errors.associate { it.key to it.problem }
        assertEquals(Problem.INVALID_REMOTE_NAME, byKey[RcloneConfigBuilder.REMOTE_NAME_KEY])
        assertEquals(Problem.NOT_A_URL, byKey["url"])
        assertEquals(Problem.REQUIRED, byKey["user"])
        assertEquals(Problem.UNKNOWN_FIELD, byKey["bogus"])
        assertFalse(byKey.containsKey("pass"))
    }

    @Test
    fun `choice, number, email and line breaks are checked`() {
        assertEquals(Problem.NOT_A_CHOICE, RcloneConfigBuilder.validate(ProviderGuides.PCLOUD, "p", mapOf("token" to token, "hostname" to "x.pcloud.com")).single().problem)
        assertEquals(Problem.NOT_A_NUMBER, RcloneConfigBuilder.validate(ProviderGuides.SFTP, "s", mapOf("host" to "h", "user" to "u", "pass" to "p", "port" to "22a")).single().problem)
        assertEquals(Problem.NOT_AN_EMAIL, RcloneConfigBuilder.validate(ProviderGuides.MEGA, "m", mapOf("user" to "nope", "pass" to "p")).single().problem)
        assertEquals(Problem.LINE_BREAK, RcloneConfigBuilder.validate(ProviderGuides.B2, "b", mapOf("account" to "a\nb", "key" to "k")).single().problem)
    }

    @Test
    fun `sftp needs a password or a key and escapes the pem`() {
        val missing = RcloneConfigBuilder.validate(ProviderGuides.SFTP, "s", mapOf("host" to "h", "user" to "u"))
        assertEquals(setOf("pass", "key_pem"), missing.filter { it.problem == Problem.NEED_ONE_OF }.map { it.key }.toSet())

        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\r\nAAAA\r\nBBBB\r\n-----END OPENSSH PRIVATE KEY-----\n"
        val result = build(ProviderGuides.SFTP, "s", mapOf("host" to "h", "user" to "u", "key_pem" to pem, "key_file_pass" to "pp"))
        result as RcloneConfigBuilder.Result.Success
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\\nAAAA\\nBBBB\\n-----END OPENSSH PRIVATE KEY-----", result.section.options["key_pem"])
        assertEquals("OBS(pp)", result.section.options["key_file_pass"])
        assertEquals("22", result.section.options["port"])
        assertFalse(result.section.options.containsKey("pass"))
        assertTrue(RcloneIni.parse(result.iniText).isValid)
    }

    @Test
    fun `r2 carries its fixed region and acl`() {
        val result = build(
            ProviderGuides.S3_R2, "r2",
            mapOf("endpoint" to "https://abc.r2.cloudflarestorage.com", "access_key_id" to "a", "secret_access_key" to "s"),
        )
        result as RcloneConfigBuilder.Result.Success
        assertEquals("Cloudflare", result.section.options["provider"])
        assertEquals("auto", result.section.options["region"])
        assertEquals("private", result.section.options["acl"])
    }
}
