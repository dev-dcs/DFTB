package ca.devdcs.backup.core.storage.rclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneIniTest {

    private val sample = """
        # my remotes
        [gdrive]
        type = drive
        scope = drive
        token = {"access_token":"abc","token_type":"Bearer"}

        ; second
        [s3 backup]
        type = s3
        provider = AWS
        secret_access_key = topsecret
        access_key_id = AKIA
    """.trimIndent()

    @Test
    fun `parses sections and options in order`() {
        val doc = RcloneIni.parse(sample)
        assertTrue(doc.issues.toString(), doc.isValid)
        assertTrue(doc.warnings.isEmpty())
        assertEquals(listOf("gdrive", "s3 backup"), doc.sections.map { it.name })
        assertEquals("drive", doc.section("gdrive")!!.type)
        assertEquals("""{"access_token":"abc","token_type":"Bearer"}""", doc.section("gdrive")!!.options["token"])
        assertEquals(listOf("type", "provider", "secret_access_key", "access_key_id"), doc.section("s3 backup")!!.options.keys.toList())
    }

    @Test
    fun `value keeps everything after the first equals sign`() {
        val doc = RcloneIni.parse("[a]\ntype = http\nurl = https://x/?a=b=c\n")
        assertEquals("https://x/?a=b=c", doc.section("a")!!.options["url"])
    }

    @Test
    fun `duplicate section is an error`() {
        val doc = RcloneIni.parse("[a]\ntype = s3\n[a]\ntype = b2\n")
        assertFalse(doc.isValid)
        val issue = doc.errors.single()
        assertEquals(RcloneIniIssue.Code.DUPLICATE_SECTION, issue.code)
        assertEquals(3, issue.line)
        assertEquals("s3", doc.section("a")!!.type)
    }

    @Test
    fun `unknown type is only a warning`() {
        val doc = RcloneIni.parse("[x]\ntype = futurecloud\n")
        assertTrue(doc.isValid)
        assertEquals(RcloneIniIssue.Code.UNKNOWN_TYPE, doc.warnings.single().code)
    }

    @Test
    fun `missing type, orphan key, malformed line and bad header are errors`() {
        val doc = RcloneIni.parse("orphan = 1\n[ok]\nnot a pair\n[bad\n[-dash]\ntype = s3\n")
        val codes = doc.errors.map { it.code }.toSet()
        assertTrue(RcloneIniIssue.Code.KEY_OUTSIDE_SECTION in codes)
        assertTrue(RcloneIniIssue.Code.MALFORMED_LINE in codes)
        assertTrue(RcloneIniIssue.Code.MISSING_TYPE in codes)
        assertTrue(RcloneIniIssue.Code.INVALID_SECTION_NAME in codes)
        assertEquals(1, doc.errors.first { it.code == RcloneIniIssue.Code.KEY_OUTSIDE_SECTION }.line)
    }

    @Test
    fun `duplicate key warns and last value wins`() {
        val doc = RcloneIni.parse("[a]\ntype = s3\nregion = x\nregion = y\n")
        assertTrue(doc.isValid)
        assertEquals(RcloneIniIssue.Code.DUPLICATE_KEY, doc.warnings.single().code)
        assertEquals("y", doc.section("a")!!.options["region"])
    }

    @Test
    fun `empty text is valid and empty`() {
        val doc = RcloneIni.parse("")
        assertTrue(doc.isValid)
        assertTrue(doc.sections.isEmpty())
    }

    @Test
    fun `remote name rules`() {
        assertTrue(RcloneIni.isValidRemoteName("my-remote_1.x+y@z"))
        assertTrue(RcloneIni.isValidRemoteName("with space"))
        assertFalse(RcloneIni.isValidRemoteName(""))
        assertFalse(RcloneIni.isValidRemoteName("-lead"))
        assertFalse(RcloneIni.isValidRemoteName(" lead"))
        assertFalse(RcloneIni.isValidRemoteName("trail "))
        assertFalse(RcloneIni.isValidRemoteName("a:b"))
        assertFalse(RcloneIni.isValidRemoteName("a/b"))
    }

    @Test
    fun `toRemote masks secrets but keeps plain values`() {
        val remote = RcloneIni.toRemote(RcloneIni.parse(sample).section("s3 backup")!!)!!
        assertEquals("s3", remote.type)
        assertEquals("AWS", remote.maskedOptions["provider"])
        assertEquals("AKIA", remote.maskedOptions["access_key_id"])
        assertEquals(RcloneIni.MASK, remote.maskedOptions["secret_access_key"])
        assertFalse(remote.maskedOptions.containsKey("type"))
        val drive = RcloneIni.toRemote(RcloneIni.parse(sample).section("gdrive")!!)!!
        assertEquals(RcloneIni.MASK, drive.maskedOptions["token"])
    }

    @Test
    fun `toRemote of a typeless section is null`() {
        assertNull(RcloneIni.toRemote(RcloneSection("x", mapOf("a" to "b"))))
    }

    @Test
    fun `render puts type first and round-trips`() {
        val section = RcloneSection("r", linkedMapOf("provider" to "AWS", "type" to "s3"))
        val text = RcloneIni.render(section)
        assertEquals("[r]\ntype = s3\nprovider = AWS\n", text)
        assertEquals(section.options, RcloneIni.parse(text).section("r")!!.options)
    }

    @Test
    fun `upsert replaces an existing section and preserves the rest`() {
        val updated = RcloneIni.upsertSection(sample, RcloneSection("gdrive", linkedMapOf("type" to "drive", "scope" to "drive.file")))
        val doc = RcloneIni.parse(updated)
        assertTrue(doc.isValid)
        assertEquals(listOf("gdrive", "s3 backup"), doc.sections.map { it.name })
        assertEquals(mapOf("type" to "drive", "scope" to "drive.file"), doc.section("gdrive")!!.options)
        assertEquals("topsecret", doc.section("s3 backup")!!.options["secret_access_key"])
        assertTrue(updated.startsWith("# my remotes\n"))
        assertTrue(updated.contains("; second"))
    }

    @Test
    fun `upsert appends a new section`() {
        val updated = RcloneIni.upsertSection(sample, RcloneSection("new", linkedMapOf("type" to "b2")))
        assertEquals(listOf("gdrive", "s3 backup", "new"), RcloneIni.parse(updated).sections.map { it.name })
        assertEquals("[n]\ntype = b2\n", RcloneIni.upsertSection("", RcloneSection("n", mapOf("type" to "b2"))))
    }

    @Test
    fun `upsert of the last section keeps a valid file`() {
        val updated = RcloneIni.upsertSection(sample, RcloneSection("s3 backup", linkedMapOf("type" to "s3", "provider" to "Minio")))
        val doc = RcloneIni.parse(updated)
        assertTrue(doc.isValid)
        assertEquals(mapOf("type" to "s3", "provider" to "Minio"), doc.section("s3 backup")!!.options)
    }

    @Test
    fun `remove drops only the named section`() {
        val removed = RcloneIni.removeSection(sample, "gdrive")
        val doc = RcloneIni.parse(removed)
        assertEquals(listOf("s3 backup"), doc.sections.map { it.name })
        assertEquals(sample, RcloneIni.removeSection(sample, "absent"))
        assertEquals("", RcloneIni.removeSection("[a]\ntype = s3\n", "a"))
    }
}
