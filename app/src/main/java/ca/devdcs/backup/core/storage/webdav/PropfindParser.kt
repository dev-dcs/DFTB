package ca.devdcs.backup.core.storage.webdav

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/** One `<D:response>` of a PROPFIND multistatus, with only the properties DFTB uses. */
internal data class DavResource(
    /** Raw (percent-encoded) href as the server sent it. */
    val href: String,
    val isCollection: Boolean,
    val contentLength: Long?,
    val lastModifiedMillis: Long?,
    /** RFC 4331; null when absent or reported as negative ("unknown/unlimited", e.g. Nextcloud -3). */
    val quotaAvailableBytes: Long?,
    val quotaUsedBytes: Long?,
)

/**
 * Minimal namespace-aware parser for RFC 4918 `207 Multi-Status` bodies.
 *
 * Exists because servers differ wildly in prefixes (`D:`, `d:`, default namespace) and in how they
 * split properties across `propstat` blocks; matching on the `DAV:` namespace URI and honouring
 * only `200` propstats handles all of them. Uses javax.xml DOM, present on Android and the JVM,
 * so it is unit-testable without Robolectric. DOCTYPEs are refused to rule out entity expansion.
 */
internal object PropfindParser {

    private const val DAV = "DAV:"

    fun parse(xml: String): List<DavResource> = parse(InputSource(StringReader(xml)))

    fun parse(stream: InputStream): List<DavResource> = parse(InputSource(stream))

    private fun parse(source: InputSource): List<DavResource> {
        val document = try {
            newFactory().newDocumentBuilder().apply { setErrorHandler(QuietErrors) }.parse(source)
        } catch (e: SAXException) {
            throw StorageException(Reason.PROTOCOL, "Malformed WebDAV response: ${e.message}", e)
        } catch (e: ParserConfigurationException) {
            throw StorageException(Reason.PROTOCOL, "XML parser unavailable: ${e.message}", e)
        } catch (e: IOException) {
            throw StorageException(Reason.NETWORK, "Reading WebDAV response failed: ${e.message}", e)
        }
        val root = document.documentElement ?: return emptyList()
        if (root.namespaceURI != DAV || root.localName != "multistatus") {
            throw StorageException(Reason.PROTOCOL, "Response is not a WebDAV multistatus document")
        }
        return root.children("response").mapNotNull(::parseResponse)
    }

    private fun parseResponse(response: Element): DavResource? {
        val href = response.children("href").firstOrNull()?.textContent?.trim()
        if (href.isNullOrEmpty()) return null
        // A response-level status (no propstat) reports a member error, e.g. 404 or 403.
        response.children("status").firstOrNull()?.let { if (statusCode(it.textContent) !in 200..299) return null }

        var collection = false
        var length: Long? = null
        var modified: Long? = null
        var available: Long? = null
        var used: Long? = null
        for (propstat in response.children("propstat")) {
            val status = propstat.children("status").firstOrNull()?.textContent
            if (status != null && statusCode(status) !in 200..299) continue
            for (prop in propstat.children("prop")) {
                for (property in prop.children().filter { it.namespaceURI == DAV }) {
                    when (property.localName) {
                        "resourcetype" -> if (property.children("collection").isNotEmpty()) collection = true
                        "getcontentlength" -> length = property.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                        "getlastmodified" -> modified = parseHttpDate(property.textContent)
                        "quota-available-bytes" -> available = property.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                        "quota-used-bytes" -> used = property.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                    }
                }
            }
        }
        return DavResource(
            href = href,
            isCollection = collection,
            contentLength = if (collection) null else length,
            lastModifiedMillis = modified,
            quotaAvailableBytes = available,
            quotaUsedBytes = used,
        )
    }

    /** Status code from a `HTTP/1.1 200 OK` line; -1 when unparseable. */
    fun statusCode(line: String?): Int =
        line?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1

    /** RFC 1123 date (what `getlastmodified` must be), tolerating ISO-8601 from lax servers. */
    fun parseHttpDate(text: String?): Long? {
        val value = text?.trim()?.ifEmpty { null } ?: return null
        return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .getOrNull()
    }

    private fun newFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        isExpandEntityReferences = false
        isIgnoringComments = true
        // Not every platform parser supports these; each refusal just leaves its safe default.
        for ((feature, value) in SECURE_FEATURES) {
            try {
                setFeature(feature, value)
            } catch (_: ParserConfigurationException) {
            } catch (_: UnsupportedOperationException) {
            }
        }
    }

    private val SECURE_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
    )

    /** Fails on errors without the default handler's stderr noise. */
    private object QuietErrors : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException): Unit = throw exception
        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }

    /** Direct element children; with [localName], only `DAV:` elements of that name. */
    private fun Element.children(localName: String? = null): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            if (localName == null || (element.namespaceURI == DAV && element.localName == localName)) out += element
        }
        return out
    }
}
