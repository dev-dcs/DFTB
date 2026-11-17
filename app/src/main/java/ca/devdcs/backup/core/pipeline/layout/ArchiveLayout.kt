package ca.devdcs.backup.core.pipeline.layout

import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.StructureMode
import java.net.URLDecoder

/**
 * Decides where every [ca.devdcs.backup.core.pipeline.SourceItem] lands inside an archive (or a
 * RAW_COPY destination tree). Exists so the sources, the restore engine and the UI agree on one
 * stable layout:
 *
 * ```
 * partitions/<node>.img by-name partition images (boot_a.img, persist.img)
 * partitions/mapper/<node>.img device-mapper (logical) images, kept apart from same-named physical nodes
 * apps/<pkg>/manifest.json restore metadata
 * apps/<pkg>/base.apk, split_<name>.apk
 * apps/<pkg>/data.tar | data_de.tar | cache.tar | data_selinux.json
 * apps/<pkg>/external_data.tar | media.tar | obb.tar (privileged tier)
 * apps/<pkg>/external_data/... | media/... | obb/... (directly readable tree)
 * files/<root-label>/<relative> PRESERVE_HIERARCHY
 * files/<Category>/<name> CATEGORIZED (collision-safe renaming)
 * ```
 *
 * Pure: no Android types, so it is unit-tested on the JVM.
 */
class ArchiveLayout(val mode: StructureMode) {

    fun partitionImage(nodeName: String, logical: Boolean): String {
        val file = segment(nodeName) + ".img"
        return if (logical) "$PARTITIONS/mapper/$file" else "$PARTITIONS/$file"
    }

    fun appDirectory(packageName: String): String = "$APPS/${segment(packageName)}"

    fun appFile(packageName: String, fileName: String): String = appDirectory(packageName) + "/" + segment(fileName)

    fun appManifest(packageName: String): String = appFile(packageName, APP_MANIFEST)

    fun appBaseApk(packageName: String): String = appFile(packageName, "base.apk")

    fun appSplitApk(packageName: String, splitName: String): String = appFile(packageName, "split_" + segment(splitName) + ".apk")

    fun appArchive(packageName: String, part: AppArchivePart): String = appFile(packageName, part.fileName)

    /** Root of a directly-readable tree copy of [part] (external data / media / obb). */
    fun appTreeRoot(packageName: String, part: AppArchivePart): String = appFile(packageName, part.treeName)

    /** [relativePath] below [appTreeRoot], every segment sanitized. */
    fun appTreeEntry(packageName: String, part: AppArchivePart, relativePath: String): String =
        appTreeRoot(packageName, part) + "/" + relative(relativePath)

    /** A fresh, stateful allocator for one file-tree source run (collision tracking is per archive). */
    fun newFileAllocator(): FilePathAllocator = FilePathAllocator(mode)

    /** App-relative archive members, with their tar-form and tree-form names. */
    enum class AppArchivePart(val fileName: String, val treeName: String) {
        /** Credential-encrypted data dir (`/data/data/<pkg>` or `/data/user/N/<pkg>`). */
        CE_DATA("data.tar", "data"),
        /** Device-protected data dir (`/data/user_de/N/<pkg>`). */
        DE_DATA("data_de.tar", "data_de"),
        /** cache + code_cache only, when CACHE is selected without INTERNAL_DATA. */
        CACHE("cache.tar", "cache"),
        /** Per-file SELinux/ownership listing of the private data dirs. */
        SELINUX_LISTING("data_selinux.json", "data_selinux"),
        EXTERNAL_DATA("external_data.tar", "external_data"),
        MEDIA("media.tar", "media"),
        OBB("obb.tar", "obb"),
    }

    companion object {
        const val PARTITIONS = "partitions"
        const val APPS = "apps"
        const val FILES = "files"
        const val APP_MANIFEST = "manifest.json"

        /** Folder for files whose extension belongs to no [FileCategory]. */
        const val OTHER_FOLDER = "Other"

        /** Display folder of each category in CATEGORIZED mode. */
        fun categoryFolder(category: FileCategory?): String = when (category) {
            FileCategory.IMAGES -> "Images"
            FileCategory.VIDEOS -> "Videos"
            FileCategory.AUDIO -> "Audio"
            FileCategory.DOCUMENTS -> "Documents"
            FileCategory.ARCHIVES -> "Archives"
            FileCategory.BINARIES -> "Binaries"
            FileCategory.DATABASES -> "Databases"
            FileCategory.CONFIGS -> "Configs"
            null -> OTHER_FOLDER
        }

        /**
         * One safe path segment: never empty, `.` or `..`, no `/`, `\` or NUL/control characters
         * (they would escape the archive root or break FAT/SAF destinations).
         */
        fun segment(raw: String): String {
            val cleaned = buildString(raw.length) {
                for (c in raw) append(if (c == '/' || c == '\\' || c.code < 0x20 || c.code == 0x7f) '_' else c)
            }
            return when (cleaned) {
                "" -> "_"
                "." -> "_."
                ".." -> "_.."
                else -> cleaned
            }
        }

        /** Sanitize every segment of a '/'-separated relative path; empty segments are dropped. */
        fun relative(path: String): String {
            val parts = path.split('/').filter { it.isNotEmpty() }
            return if (parts.isEmpty()) "_" else parts.joinToString("/") { segment(it) }
        }

        /**
         * Short human label for a scan root: the last path segment of a filesystem path, or the
         * last segment of a SAF tree/document id (`primary:DCIM/Camera` -> `Camera`).
         */
        fun rootLabel(rootLocation: String): String {
            val trimmed = rootLocation.trimEnd('/')
            val raw = if (trimmed.startsWith("content://")) {
                val lastSegment = trimmed.substringAfterLast('/')
                val decoded = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
                decoded.substringAfterLast(':').substringAfterLast('/').ifEmpty { decoded.substringBefore(':') }
            } else {
                trimmed.substringAfterLast('/')
            }
            return segment(raw.ifEmpty { "root" })
        }
    }
}

/**
 * Hands out unique archive paths for files from a scan. Collisions are detected case-insensitively
 * (FAT, exFAT and most SAF providers fold case), so `a.jpg` and `A.jpg` never overwrite each other:
 * the later one becomes `A (2).jpg`. Not thread-safe; one instance per source run.
 */
class FilePathAllocator internal constructor(private val mode: StructureMode) {

    private val usedFiles = HashSet<String>()
    private val usedDirs = HashSet<String>()
    private val rootLabels = HashMap<String, String>()
    private val usedLabels = HashSet<String>()

    /** Archive path for one scanned file; [category] is only used in CATEGORIZED mode. */
    fun allocate(rootLocation: String, relativePath: String, category: FileCategory?): String {
        val desired = when (mode) {
            StructureMode.PRESERVE_HIERARCHY -> "${ArchiveLayout.FILES}/${labelFor(rootLocation)}/${ArchiveLayout.relative(relativePath)}"
            StructureMode.CATEGORIZED -> {
                val name = ArchiveLayout.segment(relativePath.substringAfterLast('/'))
                "${ArchiveLayout.FILES}/${ArchiveLayout.categoryFolder(category)}/$name"
            }
        }
        return claim(desired)
    }

    /** Parent directories of [path] (outermost first), excluding the archive root. */
    fun parentsOf(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.subList(0, it).joinToString("/") }
    }

    private fun labelFor(rootLocation: String): String = rootLabels.getOrPut(rootLocation) {
        val base = ArchiveLayout.rootLabel(rootLocation)
        var candidate = base
        var n = 2
        while (!usedLabels.add(candidate.lowercase())) candidate = "$base-${n++}"
        candidate
    }

    /**
     * Resolve [desired] segment by segment: a directory segment that collides with an already
     * allocated file is renamed (consistently for every later file below it), then the file name
     * itself is numbered until it collides with neither a file nor a directory.
     */
    private fun claim(desired: String): String {
        val parts = desired.split('/')
        var prefix = ""
        for (i in 0 until parts.size - 1) {
            val original = parts[i]
            val key = "$prefix/$original"
            val chosen = dirRenames.getOrPut(key) {
                var candidate = original
                var n = 2
                while (join(prefix, candidate).lowercase() in usedFiles) candidate = numbered(original, n++)
                candidate
            }
            prefix = join(prefix, chosen)
            usedDirs += prefix.lowercase()
        }
        val name = parts.last()
        var candidate = join(prefix, name)
        var n = 2
        while (candidate.lowercase() in usedFiles || candidate.lowercase() in usedDirs) candidate = join(prefix, numbered(name, n++))
        usedFiles += candidate.lowercase()
        return candidate
    }

    private val dirRenames = HashMap<String, String>()

    private fun join(prefix: String, segment: String) = if (prefix.isEmpty()) segment else "$prefix/$segment"

    companion object {
        /** `photo.jpg` -> `photo (2).jpg`; dot-files and extension-less names get the suffix at the end. */
        fun numbered(name: String, n: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        }
    }
}
