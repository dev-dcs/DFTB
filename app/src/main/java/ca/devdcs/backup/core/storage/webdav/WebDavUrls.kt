package ca.devdcs.backup.core.storage.webdav

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.core.storage.ftp.NetPaths
import ca.devdcs.backup.domain.model.DestinationConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL arithmetic for WebDAV destinations.
 *
 * Exists so that config-to-URL building and server-href-to-path mapping (the two places WebDAV
 * integrations usually get percent-encoding and trailing-slash rules wrong) live in one pure,
 * unit-tested object instead of inside network code.
 *
 * Rules: [DestinationConfig.host] is either a bare host (optionally `host:port/path`) combined with
 * `options["scheme"]` (`https` default, or `http`), or a full `http(s)://` URL. A non-null
 * [DestinationConfig.port] always overrides the port. [DestinationConfig.basePath] is appended.
 * The base URL always ends with '/', since it denotes a collection.
 */
internal object WebDavUrls {

    /** Destination option selecting `https` (default) or `http` when host has no scheme. */
    const val OPTION_SCHEME: String = "scheme"

    fun baseUrl(config: DestinationConfig): HttpUrl {
        val host = config.host?.trim().orEmpty()
        if (host.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "WebDAV server address is not set")
        val hasScheme = host.contains("://")
        val text = if (hasScheme) {
            host
        } else {
            val scheme = config.options[OPTION_SCHEME]?.trim()?.lowercase()?.ifEmpty { null } ?: "https"
            if (scheme != "https" && scheme != "http") {
                throw StorageException(Reason.MISCONFIGURED, "WebDAV scheme must be http or https")
            }
            "$scheme://$host"
        }
        val parsed = text.toHttpUrlOrNull()
            ?: throw StorageException(Reason.MISCONFIGURED, "Invalid WebDAV server address")
        val builder = parsed.newBuilder().query(null).fragment(null).encodedPath("/")
        config.port?.let { port ->
            if (port !in 1..65535) throw StorageException(Reason.MISCONFIGURED, "Port must be between 1 and 65535")
            builder.port(port)
        }
        val segments = parsed.pathSegments.filter { it.isNotEmpty() } + NetPaths.segments(config.basePath.orEmpty())
        for (segment in segments) {
            if (segment == "..") throw StorageException(Reason.MISCONFIGURED, "Path must not contain '..' segments")
            builder.addPathSegment(segment)
        }
        builder.addPathSegment("")
        return builder.build()
    }

    /** URL of session-relative [rel] under [base]; collections get a trailing slash. */
    fun resolve(base: HttpUrl, rel: String, collection: Boolean): HttpUrl {
        val segments = NetPaths.segments(rel)
        if (segments.isEmpty()) return base
        val builder = base.newBuilder()
        for (segment in segments) builder.addPathSegment(segment)
        if (collection) builder.addPathSegment("")
        return builder.build()
    }

    /**
     * Session-relative path of a multistatus `href` (absolute path or full URL, percent-encoded),
     * or null when it lies outside [base].
     */
    fun relativePath(base: HttpUrl, href: String): String? {
        val resolved = base.resolve(href.trim()) ?: return null
        val baseSegments = base.pathSegments.filter { it.isNotEmpty() }
        val hrefSegments = resolved.pathSegments.filter { it.isNotEmpty() }
        if (hrefSegments.size < baseSegments.size) return null
        if (hrefSegments.subList(0, baseSegments.size) != baseSegments) return null
        val rest = hrefSegments.drop(baseSegments.size)
        if (rest.any { it == "." || it == ".." || '/' in it }) return null
        return rest.joinToString("/")
    }
}
