package ca.devdcs.backup.core.storage.webdav

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.domain.model.DestinationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class WebDavLogicTest {

    // --- URL building ---

    @Test
    fun `bare host defaults to https with base path`() {
        val url = WebDavUrls.baseUrl(DestinationConfig(host = "nas.local", basePath = "/dav/backups"))
        assertEquals("https://nas.local/dav/backups/", url.toString())
    }

    @Test
    fun `scheme option and port are honoured`() {
        val url = WebDavUrls.baseUrl(
            DestinationConfig(host = "192.168.1.5", port = 8080, options = mapOf("scheme" to "HTTP")),
        )
        assertEquals("http://192.168.1.5:8080/", url.toString())
    }

    @Test
    fun `full url host keeps its path and appends base path`() {
        val url = WebDavUrls.baseUrl(
            DestinationConfig(host = "https://cloud.example.com/remote.php/dav/files/me?x=1", basePath = "DFTB"),
        )
        assertEquals("https://cloud.example.com/remote.php/dav/files/me/DFTB/", url.toString())
    }

    @Test
    fun `segments are percent encoded`() {
        val base = WebDavUrls.baseUrl(DestinationConfig(host = "h", basePath = "My Backups"))
        assertEquals("https://h/My%20Backups/", base.toString())
        assertEquals("https://h/My%20Backups/a%23b/c%20d.tar", WebDavUrls.resolve(base, "a#b/c d.tar", collection = false).toString())
        assertEquals("https://h/My%20Backups/dir/", WebDavUrls.resolve(base, "/dir/", collection = true).toString())
        assertEquals(base, WebDavUrls.resolve(base, "", collection = true))
    }

    @Test
    fun `invalid configs are rejected`() {
        expectReason(Reason.MISCONFIGURED) { WebDavUrls.baseUrl(DestinationConfig(host = " ")) }
        expectReason(Reason.MISCONFIGURED) { WebDavUrls.baseUrl(DestinationConfig(host = "h", options = mapOf("scheme" to "ftp"))) }
        expectReason(Reason.MISCONFIGURED) { WebDavUrls.baseUrl(DestinationConfig(host = "h", basePath = "a/../b")) }
        expectReason(Reason.MISCONFIGURED) { WebDavUrls.baseUrl(DestinationConfig(host = "h", port = 70000)) }
    }

    @Test
    fun `hrefs map back to relative paths`() {
        val base = WebDavUrls.baseUrl(DestinationConfig(host = "h", basePath = "My Backups"))
        assertEquals("", WebDavUrls.relativePath(base, "/My%20Backups/"))
        assertEquals("a/c d.tar", WebDavUrls.relativePath(base, "/My%20Backups/a/c%20d.tar"))
        assertEquals("a", WebDavUrls.relativePath(base, "https://h/My%20Backups/a/"))
        assertNull(WebDavUrls.relativePath(base, "/Other/a"))
        assertNull(WebDavUrls.relativePath(base, "/"))
    }

    // --- PROPFIND parsing ---

    @Test
    fun `parses depth 1 multistatus with mixed prefixes`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/dav/backups/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:getlastmodified>Tue, 15 Oct 2024 08:30:00 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getcontentlength/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <D:response xmlns:D="DAV:">
                <D:href>/dav/backups/full%201.tar</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength>1048576</D:getcontentlength>
                    <D:getlastmodified>Wed, 16 Oct 2024 10:00:00 GMT</D:getlastmodified>
                    <oc:getcontentlength>999</oc:getcontentlength>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <response xmlns="DAV:">
                <href>/dav/backups/gone.tar</href>
                <status>HTTP/1.1 404 Not Found</status>
              </response>
            </d:multistatus>
        """.trimIndent()
        val resources = PropfindParser.parse(xml)
        assertEquals(2, resources.size)
        val dir = resources[0]
        assertEquals("/dav/backups/", dir.href)
        assertTrue(dir.isCollection)
        assertNull(dir.contentLength)
        assertEquals(1728981000000L, dir.lastModifiedMillis)
        val file = resources[1]
        assertEquals("/dav/backups/full%201.tar", file.href)
        assertFalse(file.isCollection)
        assertEquals(1048576L, file.contentLength)
        assertEquals(1729072800000L, file.lastModifiedMillis)
    }

    @Test
    fun `parses rfc 4331 quota and ignores negative values`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:quota-available-bytes>5000</d:quota-available-bytes>
                    <d:quota-used-bytes>1500</d:quota-used-bytes>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav2/</d:href>
                <d:propstat>
                  <d:prop><d:quota-available-bytes>-3</d:quota-available-bytes></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val resources = PropfindParser.parse(xml)
        assertEquals(5000L, resources[0].quotaAvailableBytes)
        assertEquals(1500L, resources[0].quotaUsedBytes)
        assertNull(resources[1].quotaAvailableBytes)
    }

    @Test
    fun `non multistatus and doctype documents are rejected`() {
        expectReason(Reason.PROTOCOL) { PropfindParser.parse("<html><body>login</body></html>") }
        expectReason(Reason.PROTOCOL) { PropfindParser.parse("not xml") }
        expectReason(Reason.PROTOCOL) {
            PropfindParser.parse(
                """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e "boom">]><d:multistatus xmlns:d="DAV:"/>""",
            )
        }
    }

    @Test
    fun `status line and date parsing`() {
        assertEquals(207, PropfindParser.statusCode("HTTP/1.1 207 Multi-Status"))
        assertEquals(-1, PropfindParser.statusCode("garbage"))
        assertEquals(0L, PropfindParser.parseHttpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(1000L, PropfindParser.parseHttpDate("1970-01-01T00:00:01Z"))
        assertNull(PropfindParser.parseHttpDate("yesterday"))
    }

    // --- status mapping ---

    @Test
    fun `http status maps to reasons`() {
        assertEquals(Reason.AUTH, WebDavErrors.reasonForStatus(401))
        assertEquals(Reason.PERMISSION, WebDavErrors.reasonForStatus(403))
        assertEquals(Reason.NOT_FOUND, WebDavErrors.reasonForStatus(404))
        assertEquals(Reason.NOT_FOUND, WebDavErrors.reasonForStatus(409))
        assertEquals(Reason.NO_SPACE, WebDavErrors.reasonForStatus(507))
        assertEquals(Reason.PROTOCOL, WebDavErrors.reasonForStatus(502))
        assertEquals(Reason.MISCONFIGURED, WebDavErrors.reasonForStatus(301))
        assertEquals(Reason.UNSUPPORTED, WebDavErrors.reasonForStatus(405))
        assertEquals(Reason.TIMEOUT, WebDavErrors.fromIo("x", SocketTimeoutException()).reason)
        assertEquals(Reason.NETWORK, WebDavErrors.fromIo("x", IOException("reset")).reason)
    }

    private fun expectReason(reason: Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected StorageException($reason)")
        } catch (e: StorageException) {
            assertEquals(reason, e.reason)
        }
    }
}
