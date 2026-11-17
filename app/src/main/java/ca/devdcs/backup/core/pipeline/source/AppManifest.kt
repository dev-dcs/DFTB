package ca.devdcs.backup.core.pipeline.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Everything a restore needs to reinstall an app and put its data back exactly: identity and
 * signing (to refuse a data restore onto a differently-signed package), the APK set, uid and data
 * dir ownership/labels, granted permissions, and which archive member holds which component.
 * Written as `apps/<pkg>/manifest.json`.
 */
@Serializable
data class AppManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    val uid: Int,
    val userId: Int,
    val kind: String,
    val installerPackageName: String?,
    /** SHA-256 (lowercase hex) of each current signing certificate. */
    val signingCertSha256: List<String>,
    /** Older certificates in the rotation lineage (APK signature scheme v3), oldest first. */
    val pastSigningCertSha256: List<String>,
    val multipleSigners: Boolean,
    val splitNames: List<String>,
    val apkFiles: List<String>,
    val grantedPermissions: List<String>,
    val deniedPermissions: List<String>,
    val ceDataDir: DataDirInfo?,
    val deDataDir: DataDirInfo?,
    /** True when data tars carry per-file pax `RHT.security.selinux` labels (toybox --selinux). */
    val tarHasSelinuxLabels: Boolean,
    val components: List<ManifestComponent>,
    val createdAtMillis: Long,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Owner and label of one private data dir; fields null when the shell could not read them. */
@Serializable
data class DataDirInfo(
    val path: String,
    val uid: Int?,
    val gid: Int?,
    val seLinuxContext: String?,
)

/** One captured component and where it is stored. */
@Serializable
data class ManifestComponent(
    /** [ca.devdcs.backup.domain.model.AppComponent] storage name (`apk`, `internal_data`, ...). */
    val component: String,
    /** `tar` (member is a tar stream), `tree` (directory of plain files) or `file`. */
    val form: String,
    val archivePath: String,
    /** Device path the data came from (restore target). */
    val sourcePath: String,
    /** For tars: the `-C` directory member names are relative to. */
    val tarBaseDirectory: String? = null,
)

/** Shared JSON settings for manifests: stable, human-readable, defaults included. */
internal val ManifestJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Converts a streamed `stat -c '%a %u %g %C %n'` listing into a JSON document without holding it in
 * memory (an app with 200k files must not need 200k objects):
 * `{"format":1,"entries":[{"path":..,"mode":"771","uid":..,"gid":..,"context":..},...],"unparsed":N}`.
 * Lines that do not parse (names containing a newline) are counted, never guessed.
 */
class StatListingJsonInputStream(source: InputStream) : InputStream() {

    private val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
    private var buffer: ByteArray = HEADER.toByteArray(Charsets.UTF_8)
    private var position = 0
    private var first = true
    private var unparsed = 0
    private var finished = false
    private var done = false

    override fun read(): Int {
        if (!fill()) return -1
        return buffer[position++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, buffer.size - position)
        System.arraycopy(buffer, position, b, off, n)
        position += n
        return n
    }

    private fun fill(): Boolean {
        while (position >= buffer.size) {
            if (done) return false
            if (finished) {
                done = true
                return false
            }
            val line = reader.readLine()
            if (line == null) {
                finished = true
                buffer = "\n],\"unparsed\":$unparsed}\n".toByteArray(Charsets.UTF_8)
                position = 0
                continue
            }
            if (line.isEmpty()) continue
            val parsed = AppDataScripts.parseStatLine(line)
            if (parsed == null) {
                unparsed++
                continue
            }
            val json = buildString {
                if (!first) append(",")
                append("\n{\"path\":").append(JsonPrimitive(parsed.path))
                append(",\"mode\":\"").append(parsed.mode).append('"')
                append(",\"uid\":").append(parsed.uid)
                append(",\"gid\":").append(parsed.gid)
                append(",\"context\":").append(if (parsed.context == null) "null" else JsonPrimitive(parsed.context).toString())
                append('}')
            }
            first = false
            buffer = json.toByteArray(Charsets.UTF_8)
            position = 0
        }
        return true
    }

    override fun close() = reader.close()

    private companion object {
        const val HEADER = "{\"format\":1,\"entries\":["
    }
}
