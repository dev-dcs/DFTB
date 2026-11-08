package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.StorageException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneErrorMapperTest {

    private fun jsonLog(level: String, msg: String) =
        """{"level":"$level","msg":"${msg.replace("\"", "\\\"")}","source":"x.go:1","time":"2024-01-01T00:00:00Z"}"""

    @Test
    fun `parses json log records and keeps raw lines`() {
        val records = RcloneErrorMapper.parseLog(listOf(jsonLog("ERROR", "boom"), "", "plain text", "{broken json"))
        assertEquals(listOf("error" to "boom", "raw" to "plain text", "raw" to "{broken json"), records.map { it.level to it.message })
    }

    @Test
    fun `object name is kept apart and never drives classification`() {
        val line = """{"level":"error","msg":"object not found","object":"reports/quota.txt"}"""
        val record = RcloneErrorMapper.parseLog(listOf(line)).single()
        assertEquals("object not found", record.message)
        assertEquals("reports/quota.txt: object not found", record.display)
        val e = RcloneErrorMapper.toException("cat", 1, listOf(line))
        assertEquals(Reason.NOT_FOUND, e.reason)
        assertTrue(e.message!!.contains("reports/quota.txt"))
    }

    @Test
    fun `exit codes 3 and 4 win over keywords`() {
        assertEquals(Reason.NOT_FOUND, RcloneErrorMapper.toException("cat", 4, listOf(jsonLog("error", "HTTP error 403 on quota folder"))).reason)
    }

    @Test
    fun `keyword classification`() {
        val cases = mapOf(
            "Failed to create file system: couldn't connect SSH: dial tcp 10.0.0.1:22: connect: connection refused" to Reason.NETWORK,
            "read tcp: i/o timeout" to Reason.NETWORK,
            "couldn't list: dial tcp: lookup example.invalid: no such host" to Reason.NETWORK,
            "HTTP error 401 (401 Unauthorized)" to Reason.AUTH,
            "googleapi: Error 403: The user does not have sufficient permissions" to Reason.AUTH,
            "oauth2: cannot fetch token: 400 Bad Request Response: {\"error\":\"invalid_grant\"}" to Reason.AUTH,
            "AccessDenied: Access Denied status code: 403" to Reason.AUTH,
            "directory not found" to Reason.NOT_FOUND,
            "error reading source root directory: object not found" to Reason.NOT_FOUND,
            "NoSuchBucket: The specified bucket does not exist" to Reason.NOT_FOUND,
            "Failed to copy: storage quota exceeded" to Reason.NO_SPACE,
            "HTTP 507 Insufficient Storage" to Reason.NO_SPACE,
            "didn't find section in config file \"nope\"" to Reason.MISCONFIGURED,
            "about is not supported: doesn't support About" to Reason.UNSUPPORTED,
        )
        cases.forEach { (msg, expected) -> assertEquals(msg, expected, RcloneErrorMapper.classify(msg)) }
    }

    @Test
    fun `status numbers inside other numbers do not count as auth`() {
        assertEquals(null, RcloneErrorMapper.classify("wrote 14013 bytes"))
    }

    @Test
    fun `error records win over notices`() {
        val e = RcloneErrorMapper.toException(
            "lsjson", 1,
            listOf(jsonLog("notice", "Config file \"x\" not found - using defaults"), jsonLog("error", "HTTP error 403")),
        )
        assertEquals(Reason.AUTH, e.reason)
        assertTrue(e.message!!.contains("HTTP error 403"))
        assertTrue(e.message!!.contains("exit 1"))
    }

    @Test
    fun `falls back to exit codes`() {
        assertEquals(Reason.NOT_FOUND, RcloneErrorMapper.toException("cat", 3, emptyList()).reason)
        assertEquals(Reason.NOT_FOUND, RcloneErrorMapper.toException("cat", 4, listOf(jsonLog("error", "something odd"))).reason)
        assertEquals(Reason.PROTOCOL, RcloneErrorMapper.toException("cat", 1, listOf(jsonLog("error", "something odd"))).reason)
        assertTrue(RcloneErrorMapper.isNotFound(3, emptyList()))
    }
}
