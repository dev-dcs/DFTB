package ca.devdcs.backup.core.pipeline.layout

import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import java.time.LocalDateTime
import java.util.Locale

/**
 * TWRP's on-disk backup conventions, verified against TeamWin android_bootable_recovery
 * (android-12.1 branch; OrangeFox inherits them):
 *
 * - Folder: `<storage>/TWRP/BACKUPS/<device_id>/<backup name>` (partitionmanager.cpp,
 * twrp-functions.cpp). device_id is `androidboot.serialno` (= ro.serialno) with everything but
 * `[A-Za-z0-9._-]` removed (data.cpp `sanitize_device_id`).
 * - Backup name: `Get_Current_Date()` = local time `%04d-%02d-%02d--%02d-%02d-%02d`, plus
 * `_<ro.build.display.id>` with every '.' removed, truncated to MAX_BACKUP_NAME_LEN (64), trailing
 * spaces stripped, spaces -> '_'; if a character outside TWRP's whitelist remains
 * (`Check_Backup_Name`: space, 0-9, A-Z, a-z, `-_.[]{}`) the date alone is used.
 * - Files: `<Backup_Name>.<file system>.win`; raw images use file system `emmc` (`boot.emmc.win`),
 * file-based backups the mounted fs (`data.ext4.win`, `data.f2fs.win`) (partition.cpp).
 * - Split: when the partition's total backup size exceeds MAX_ARCHIVE_SIZE = 1610612736 (1.5 GiB,
 * variables.h), twrpTar writes `<file>%i%02i` (thread id, archive index): `data.ext4.win000`,
 * `win001`, ... up to 100 archives per thread. It splits on FILE boundaries: before adding a regular
 * file whose size would push the current archive's file bytes over the limit it starts a new,
 * self-contained tar (twrpTar::tarList). Restore picks the split set when `<file>` is absent and
 * `<file>000` exists.
 * - Member names: a single archive stores names relative to the mount point (Strip_Root_Dir) and is
 * extracted under it; split archives store the full path (include_root_dir) and are extracted
 * with an empty prefix. Relative names `app/x` (single) and `data/app/x` (split) both resolve to
 * `/data/app/x`, so that is what this plan emits.
 * - Digest: SHA-256 sidecar `<file>.sha2` (TWRP also accepts `.sha256`, then `.md5`/`.md5sum`),
 * one per archive including every split part, content `<lowercase hex> <basename>\n`
 * (twrpDigestDriver Write_Digest). A missing digest only skips the check; a wrong one fails it.
 * - Tar dialect: libtar with GNU long names ('L'/'K') and pax 'x' headers carrying
 * `RHT.security.selinux=<context>` (block.c). Extended headers >= 512 bytes are ignored, so pax
 * `path=` long names are NOT understood. Archives must be uncompressed and unencrypted unless
 * made by TWRP itself. toybox tar `--selinux` (Android 13+) writes exactly `RHT.security.selinux`.
 * - `<name>.info` is TWRP's binary InfoManager file: optional on restore (falls back to file size /
 * tar scan), so it is not produced. `recovery.log` is a plain copy of TWRP's log, informational only.
 * - /data exclusions on data-media devices (partition.cpp Setup_Data_Media): see [DATA_EXCLUSIONS].
 * TWRP also skips block and character device nodes.
 */
object TwrpConventions {
    const val ROOT_FOLDER = "TWRP/BACKUPS"
    const val MAX_ARCHIVE_SIZE = 1_610_612_736L
    const val MAX_BACKUP_NAME_LEN = 64
    const val MAX_ARCHIVES_PER_THREAD = 100
    const val DIGEST_EXTENSION = ".sha2"
    const val IMAGE_FILE_SYSTEM = "emmc"
    const val LOG_FILE = "recovery.log"
    const val SHA256_HEX_LENGTH = 64

    /** Paths TWRP's own /data backup leaves out (absolute, matched as path prefixes). */
    val DATA_EXCLUSIONS: List<String> = listOf(
        "/data/media",
        "/data/data/com.google.android.music/files",
        "/data/per_boot",
        "/data/vendor/dumpsys",
        "/data/cache",
        "/data/misc/apexdata/com.android.art",
        "/data/extm",
        "/data/gsi",
        "/data/adb/ksu/modules.img",
        "/data/system/users/0/package-restrictions.xml",
        "/data/system/storage.xml",
    )

    fun currentDate(time: LocalDateTime): String = String.format(
        Locale.US, "%04d-%02d-%02d--%02d-%02d-%02d",
        time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second,
    )

    /** TWRP's Auto_Generate_Backup_Name. */
    fun backupName(time: LocalDateTime, buildDisplayId: String?): String {
        val date = currentDate(time)
        val id = buildDisplayId?.replace(".", "")
        if (id.isNullOrEmpty()) return date
        var name = "${date}_$id"
        if (name.length > MAX_BACKUP_NAME_LEN) name = name.substring(0, MAX_BACKUP_NAME_LEN)
        name = name.trimEnd(' ').replace(' ', '_')
        return if (isValidBackupName(name)) name else date
    }

    fun isValidBackupName(name: String): Boolean =
        name.length <= MAX_BACKUP_NAME_LEN && name.all { c ->
            c == ' ' || c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z' || c in "-_.[]{}"
        }

    /** data.cpp sanitize_device_id: keep only alphanumerics and `-._`. */
    fun deviceId(serial: String): String = serial.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._" }

    fun imageFileName(backupName: String): String = "$backupName.$IMAGE_FILE_SYSTEM.win"

    fun tarFileName(backupName: String, fileSystem: String): String = "$backupName.$fileSystem.win"

    /** Split part name for thread 0, as twrpTar formats `%i%02i`. */
    fun splitFileName(baseFileName: String, index: Int): String = String.format(Locale.US, "%s%d%02d", baseFileName, 0, index)

    fun digestFileName(fileName: String): String = fileName + DIGEST_EXTENSION

    /** Exact sidecar content TWRP compares against. */
    fun digestLine(sha256Hex: String, fileName: String): String = sha256Hex.lowercase(Locale.US) + "  " + fileName + "\n"

    /** Byte length of [digestLine] for a SHA-256 digest (file names are ASCII by construction). */
    fun digestLineLength(fileName: String): Long = (SHA256_HEX_LENGTH + 2 + fileName.toByteArray(Charsets.UTF_8).size + 1).toLong()

    /** TWRP restore splits names at the first two dots, so a backup name must not contain '.'. */
    fun isValidPartitionBackupName(name: String): Boolean = name.isNotEmpty() && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
}

/** Type of one inventory entry of a filesystem partition. */
enum class InventoryType { FILE, DIRECTORY, SYMLINK, FIFO, OTHER }

/** One filesystem object found under a mount point (absolute path). */
data class InventoryEntry(val path: String, val type: InventoryType, val sizeBytes: Long)

/** A raw partition to image as `<backupName>.emmc.win`. */
data class NandroidImageInput(val partition: ResolvedPartition, val sizeBytes: Long?)

/** A mounted filesystem to tar (TWRP only restores `/data`-style file backups this way). */
data class NandroidFilesystemInput(
    /** TWRP backup name: the mount point without '/', e.g. `data`. */
    val backupName: String,
    val mountPoint: String,
    /** `ext4` or `f2fs` as reported by /proc/mounts. */
    val fileSystem: String,
    /** Walk order (directories before their contents). */
    val inventory: List<InventoryEntry>,
    /** Extra absolute paths to leave out besides [TwrpConventions.DATA_EXCLUSIONS]. */
    val extraExclusions: List<String> = emptyList(),
)

data class NandroidRequest(
    val serial: String,
    val buildDisplayId: String?,
    val time: LocalDateTime,
    val images: List<NandroidImageInput>,
    val filesystems: List<NandroidFilesystemInput>,
    /** Optional plain-text log stored as `recovery.log`; null = none. */
    val logText: String? = null,
)

/** How tar member names are formed for one volume. */
enum class TarNameStyle {
    /** Single archive: relative to the mount point (`app/x`), extracted under it. */
    RELATIVE_TO_MOUNT,
    /** Split part: relative to `/` (`data/app/x`), extracted with an empty prefix. */
    RELATIVE_TO_ROOT,
}

/** What one plan entry's bytes are. */
sealed interface NandroidContent {
    /** Exact raw image of [partition]; [sizeBytes] when known. */
    data class RawImage(val partition: ResolvedPartition, val sizeBytes: Long?) : NandroidContent

    /**
     * One self-contained tar. [baseDirectory] is the `tar -C` dir; [members] are relative to it
     * and listed with `--no-recursion` in this order. [regularFileBytes] is the payload estimate.
     */
    data class TarVolume(
        val backupName: String,
        val mountPoint: String,
        val volumeIndex: Int,
        val nameStyle: TarNameStyle,
        val baseDirectory: String,
        val members: List<String>,
        val regularFileBytes: Long,
    ) : NandroidContent

    /** `<file>.sha2` for the entry named [ofFileName]; content known only after it is written. */
    data class DigestSidecar(val ofFileName: String) : NandroidContent

    data class Log(val text: String) : NandroidContent
}

/** One file of the TWRP folder. [archivePath] = plan folder + "/" + [fileName]. */
data class NandroidEntry(
    val fileName: String,
    val archivePath: String,
    val content: NandroidContent,
)

enum class NandroidWarningKind {
    /** A logical (device-mapper) partition: TWRP images dynamic partitions differently per device. */
    LOGICAL_PARTITION_SKIPPED,
    /** TWRP keeps one copy per name; only the active slot is restorable. */
    INACTIVE_SLOT_SKIPPED,
    /** Two selections map to the same TWRP backup name. */
    DUPLICATE_NAME_SKIPPED,
    /** The name has characters TWRP's file-name parser cannot round-trip. */
    INVALID_NAME_SKIPPED,
    /** Device/char/socket nodes are not archived (TWRP skips them too). */
    SPECIAL_FILES_SKIPPED,
    /** Names containing a newline cannot be passed to tar's file list. */
    UNLISTABLE_NAMES_SKIPPED,
    /** More than 100 split archives would be needed; TWRP cannot restore that. */
    TOO_MANY_ARCHIVES,
    /** Restoring this partition needs the recovery's fstab to list it. */
    RECOVERY_MAY_NOT_LIST,
}

data class NandroidWarning(val kind: NandroidWarningKind, val subject: String, val count: Int = 1)

/**
 * A complete TWRP/OrangeFox-restorable backup folder: every file to create, in write order (each
 * archive is followed by its `.sha2`, so the digest of the bytes just written is always available).
 */
data class NandroidPlan(
    /** Archive-relative folder: `TWRP/BACKUPS/<device_id>/<backup name>`. */
    val folder: String,
    val backupName: String,
    val entries: List<NandroidEntry>,
    val warnings: List<NandroidWarning>,
) {
    val isEmpty: Boolean get() = entries.none { it.content is NandroidContent.RawImage || it.content is NandroidContent.TarVolume }
}

/**
 * Turns resolved partitions and filesystem inventories into a [NandroidPlan] following
 * [TwrpConventions]. Exists so the twrpCompatible output is decided in one pure, tested place,
 * leaving the byte producers (`NandroidSource`) free of layout rules.
 */
object NandroidPlanner {

    /** Names TWRP fstabs commonly carry for raw partitions; others get a RECOVERY_MAY_NOT_LIST warning. */
    val COMMON_RECOVERY_NAMES: Set<String> = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "recovery", "dtbo", "vbmeta",
        "vbmeta_system", "vbmeta_vendor", "efs", "persist", "modem", "bluetooth", "dsp", "persistbak",
        "modemst1", "modemst2", "fsg", "fsc", "nvdata", "nvram", "protect1", "protect2", "nvcfg", "logo",
    )

    fun plan(request: NandroidRequest, maxArchiveSize: Long = TwrpConventions.MAX_ARCHIVE_SIZE): NandroidPlan {
        val backupName = TwrpConventions.backupName(request.time, request.buildDisplayId)
        val deviceId = TwrpConventions.deviceId(request.serial).ifEmpty { "unknown" }
        val folder = "${TwrpConventions.ROOT_FOLDER}/${ArchiveLayout.segment(deviceId)}/${ArchiveLayout.segment(backupName)}"
        val entries = ArrayList<NandroidEntry>()
        val warnings = ArrayList<NandroidWarning>()
        val usedNames = HashSet<String>()

        fun add(fileName: String, content: NandroidContent) {
            entries += NandroidEntry(fileName, "$folder/$fileName", content)
        }

        fun addWithDigest(fileName: String, content: NandroidContent) {
            add(fileName, content)
            add(TwrpConventions.digestFileName(fileName), NandroidContent.DigestSidecar(fileName))
        }

        for (image in request.images) {
            val partition = image.partition.partition
            val name = partition.baseName.lowercase(Locale.US)
            when {
                partition.source == PartitionSource.MAPPER || partition.isLogical -> {
                    warnings += NandroidWarning(NandroidWarningKind.LOGICAL_PARTITION_SKIPPED, partition.name)
                    continue
                }
                !partition.isActiveSlot -> {
                    warnings += NandroidWarning(NandroidWarningKind.INACTIVE_SLOT_SKIPPED, partition.name)
                    continue
                }
                !TwrpConventions.isValidPartitionBackupName(name) -> {
                    warnings += NandroidWarning(NandroidWarningKind.INVALID_NAME_SKIPPED, partition.name)
                    continue
                }
                !usedNames.add(name) -> {
                    warnings += NandroidWarning(NandroidWarningKind.DUPLICATE_NAME_SKIPPED, partition.name)
                    continue
                }
            }
            if (name !in COMMON_RECOVERY_NAMES) warnings += NandroidWarning(NandroidWarningKind.RECOVERY_MAY_NOT_LIST, name)
            addWithDigest(TwrpConventions.imageFileName(name), NandroidContent.RawImage(image.partition, image.sizeBytes))
        }

        for (fs in request.filesystems) {
            val name = fs.backupName.lowercase(Locale.US)
            if (!TwrpConventions.isValidPartitionBackupName(name) || !TwrpConventions.isValidPartitionBackupName(fs.fileSystem)) {
                warnings += NandroidWarning(NandroidWarningKind.INVALID_NAME_SKIPPED, fs.backupName)
                continue
            }
            if (!usedNames.add(name)) {
                warnings += NandroidWarning(NandroidWarningKind.DUPLICATE_NAME_SKIPPED, fs.backupName)
                continue
            }
            val volumes = planVolumes(fs, maxArchiveSize, warnings) ?: continue
            if (volumes.isEmpty()) continue
            val baseFile = TwrpConventions.tarFileName(name, fs.fileSystem)
            if (volumes.size == 1) {
                addWithDigest(baseFile, volumes.single())
            } else {
                volumes.forEachIndexed { index, volume -> addWithDigest(TwrpConventions.splitFileName(baseFile, index), volume) }
            }
        }

        request.logText?.let { add(TwrpConventions.LOG_FILE, NandroidContent.Log(it)) }
        return NandroidPlan(folder, backupName, entries, warnings)
    }

    /** Filter [fs]'s inventory and cut it into TWRP-sized volumes; null when TWRP could not restore it. */
    internal fun planVolumes(fs: NandroidFilesystemInput, maxArchiveSize: Long, warnings: MutableList<NandroidWarning>): List<NandroidContent.TarVolume>? {
        val mount = fs.mountPoint.trimEnd('/').ifEmpty { "/" }
        val exclusions = (TwrpConventions.DATA_EXCLUSIONS.filter { it == mount || it.startsWith("$mount/") } + fs.extraExclusions)
            .map { it.trimEnd('/') }
        var special = 0
        var unlistable = 0
        val kept = ArrayList<InventoryEntry>(fs.inventory.size)
        for (entry in fs.inventory) {
            val path = entry.path.trimEnd('/')
            if (path == mount || !path.startsWith("$mount/")) continue
            if (exclusions.any { path == it || path.startsWith("$it/") }) continue
            if (entry.type == InventoryType.OTHER) {
                special++
                continue
            }
            if ('\n' in path || '\r' in path) {
                unlistable++
                continue
            }
            kept += entry.copy(path = path)
        }
        if (special > 0) warnings += NandroidWarning(NandroidWarningKind.SPECIAL_FILES_SKIPPED, fs.backupName, special)
        if (unlistable > 0) warnings += NandroidWarning(NandroidWarningKind.UNLISTABLE_NAMES_SKIPPED, fs.backupName, unlistable)

        if (kept.isEmpty()) return emptyList()
        val total = kept.sumOf { if (it.type == InventoryType.FILE) it.sizeBytes else 0L }
        val split = total > maxArchiveSize
        val groups = ArrayList<MutableList<InventoryEntry>>()
        val groupBytes = ArrayList<Long>()
        groups.add(ArrayList())
        groupBytes += 0L
        for (entry in kept) {
            val size = if (entry.type == InventoryType.FILE) entry.sizeBytes else 0L
            if (split && size > 0 && groupBytes.last() > 0 && groupBytes.last() + size > maxArchiveSize) {
                groups.add(ArrayList())
                groupBytes += 0L
            }
            groups.last() += entry
            groupBytes[groupBytes.lastIndex] = groupBytes.last() + size
        }
        if (groups.size > TwrpConventions.MAX_ARCHIVES_PER_THREAD) {
            warnings += NandroidWarning(NandroidWarningKind.TOO_MANY_ARCHIVES, fs.backupName, groups.size)
            return null
        }
        val style = if (split) TarNameStyle.RELATIVE_TO_ROOT else TarNameStyle.RELATIVE_TO_MOUNT
        val base = if (split) "/" else mount
        return groups.mapIndexed { index, group ->
            NandroidContent.TarVolume(
                backupName = fs.backupName,
                mountPoint = mount,
                volumeIndex = index,
                nameStyle = style,
                baseDirectory = base,
                members = group.map { if (split) it.path.removePrefix("/") else it.path.removePrefix("$mount/") },
                regularFileBytes = groupBytes[index],
            )
        }
    }
}
