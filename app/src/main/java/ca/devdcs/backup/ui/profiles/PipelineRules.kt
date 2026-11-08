package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.pipeline.engine.ArtifactSpecResolver
import ca.devdcs.backup.core.pipeline.engine.TransferPlanner
import ca.devdcs.backup.core.pipeline.engine.TransferResolution
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.TargetSelection
import ca.devdcs.backup.domain.model.TransferMode
import ca.devdcs.backup.ui.mapping.ULTRA_COMPRESSION_THRESHOLD

/**
 * Which kinds of artifacts a selection produces: partitions become raw images (codec applies),
 * apps / folders / categories become trees (archive format applies).
 */
data class TargetShape(val hasPartitions: Boolean, val hasTrees: Boolean) {
    companion object {
        fun of(targets: TargetSelection): TargetShape = TargetShape(
            hasPartitions = targets.partitions.isNotEmpty(),
            hasTrees = targets.apps.isNotEmpty() || targets.paths.isNotEmpty() || targets.categories.isNotEmpty(),
        )
    }
}

/** Coarse wording of a compression level, mapped to the shared `compression_level_*` strings. */
enum class LevelTier { STORE, FAST, BALANCED, MAXIMUM, ULTRA }

/** Why an option cannot be chosen right now. */
enum class OptionBlock {
    /** TWRP-compatible output must be plain: uncompressed, unencrypted, unmasked. */
    TWRP_FORCES_PLAIN,

    /** Filename masking hides names only when the manifest is encrypted too. */
    MASK_NEEDS_ENCRYPTION,

    /** Remote compression needs an SSH/SFTP destination. */
    REMOTE_COMPRESS_NEEDS_SSH,

    /** The SSH storage session exposes no remote command channel yet, so the engine refuses it. */
    REMOTE_COMPRESS_NOT_AVAILABLE,
}

/**
 * The option compatibility matrix of the profile wizard, as pure functions over the domain enums
 * and the engine's own pure resolvers ([ArtifactSpecResolver], [TransferPlanner]). Exists so the
 * wizard only offers combinations the engine actually runs, and so every rule is unit-tested
 * instead of being re-derived in composables.
 */
object PipelineRules {

    /** Formats offered for app / file trees. DD_IMAGE is partition-only (trees would be substituted). */
    val TREE_FORMATS: List<ArchiveFormat> = listOf(
        ArchiveFormat.TAR_ZST,
        ArchiveFormat.TAR_GZ,
        ArchiveFormat.TAR_XZ,
        ArchiveFormat.TAR_BZ2,
        ArchiveFormat.TAR,
        ArchiveFormat.ZIP,
        ArchiveFormat.SEVEN_ZIP,
        ArchiveFormat.RAW_COPY,
    )

    /** Encryption modes the engine implements; others fail with ENCRYPTION_UNSUPPORTED. */
    val SUPPORTED_ENCRYPTION: Set<EncryptionMode> = setOf(EncryptionMode.NONE, EncryptionMode.AES_256_GCM)

    /** Key choices a profile may pick; [KeyChoice.STORED_SECRET] only when it already had one. */
    fun keyChoices(hasStoredSecret: Boolean): List<KeyChoice> = buildList {
        add(KeyChoice.NONE)
        add(KeyChoice.PASSPHRASE)
        add(KeyChoice.VAULT_KEY)
        if (hasStoredSecret) add(KeyChoice.STORED_SECRET)
    }

    /**
     * The tree format a stored [format] behaves as. DD_IMAGE on a tree is archived by the engine as
     * tar compressed with the profile codec, so the wizard shows (and saves) exactly that.
     */
    fun treeFormatOf(format: ArchiveFormat, codec: CompressionCodec): ArchiveFormat =
        if (format == ArchiveFormat.DD_IMAGE) ArtifactSpecResolver.tarFormatFor(codec) else format

    /** True when the archive-format picker matters (trees selected, or nothing selected yet). */
    fun showsArchiveFormat(shape: TargetShape): Boolean = shape.hasTrees || !shape.hasPartitions

    /** True when the stream codec matters: partition images and RAW_COPY files use it. */
    fun showsCodec(shape: TargetShape, options: PipelineOptions): Boolean =
        shape.hasPartitions || (showsArchiveFormat(shape) && options.archiveFormat == ArchiveFormat.RAW_COPY)

    /** Highest meaningful level for trees in [format] (0 = not tunable, e.g. plain TAR). */
    fun treeMaxLevel(format: ArchiveFormat, codec: CompressionCodec): Int = when {
        ArtifactSpecResolver.isTarFamily(format) -> ArtifactSpecResolver.impliedCodec(format).maxLevel
        format == ArchiveFormat.ZIP || format == ArchiveFormat.SEVEN_ZIP -> format.maxCompressionLevel
        // RAW_COPY files and DD_IMAGE are compressed with the stream codec.
        else -> codec.maxLevel
    }

    /** Slider range for the single compression level, or null when nothing selected is compressible. */
    fun levelRange(options: PipelineOptions, shape: TargetShape): IntRange? {
        val maxima = buildList {
            if (showsArchiveFormat(shape)) add(treeMaxLevel(options.archiveFormat, options.compressionCodec))
            if (shape.hasPartitions) add(options.compressionCodec.maxLevel)
        }
        val max = maxima.maxOrNull() ?: 0
        return if (max <= 0) null else 0..max
    }

    /** Level the engine really uses for trees at [level] (it clamps per format). */
    fun effectiveTreeLevel(options: PipelineOptions, level: Int = options.compressionLevel): Int =
        level.coerceIn(0, treeMaxLevel(options.archiveFormat, options.compressionCodec))

    /** Level the engine really uses for partition images at [level]. */
    fun effectiveImageLevel(options: PipelineOptions, level: Int = options.compressionLevel): Int =
        level.coerceIn(0, options.compressionCodec.maxLevel)

    /** Same thirds-of-the-range wording as the shared compression labels, for any [max]. */
    fun levelTier(level: Int, max: Int): LevelTier {
        if (level <= 0 || max <= 0) return LevelTier.STORE
        if (max >= ULTRA_THRESHOLD && level >= ULTRA_THRESHOLD) return LevelTier.ULTRA
        val third = (max / 3).coerceAtLeast(1)
        return when {
            level <= third -> LevelTier.FAST
            level <= third * 2 -> LevelTier.BALANCED
            else -> LevelTier.MAXIMUM
        }
    }

    /** Why [choice] cannot be picked, or null. */
    fun keyChoiceBlock(choice: KeyChoice, options: PipelineOptions): OptionBlock? =
        if (options.twrpCompatible && choice != KeyChoice.NONE) OptionBlock.TWRP_FORCES_PLAIN else null

    /** Why filename masking cannot be switched on, or null. */
    fun maskBlock(keyChoice: KeyChoice, options: PipelineOptions): OptionBlock? = when {
        options.twrpCompatible -> OptionBlock.TWRP_FORCES_PLAIN
        !keyChoice.encrypts -> OptionBlock.MASK_NEEDS_ENCRYPTION
        else -> null
    }

    /** Why format / codec / level are fixed, or null. */
    fun compressionBlock(options: PipelineOptions): OptionBlock? =
        if (options.twrpCompatible) OptionBlock.TWRP_FORCES_PLAIN else null

    /**
     * Why [mode] cannot run against [destinationType] (null type = no destination picked yet), using
     * the engine's own [TransferPlanner] so the wizard never disagrees with the backup job.
     */
    fun transferBlock(mode: TransferMode, destinationType: DestinationType?): OptionBlock? {
        if (destinationType == null) {
            return if (mode == TransferMode.REMOTE_COMPRESS) OptionBlock.REMOTE_COMPRESS_NEEDS_SSH else null
        }
        return when (val resolution = TransferPlanner.resolve(mode, destinationType)) {
            is TransferResolution.Unsupported ->
                if (resolution.forSsh) OptionBlock.REMOTE_COMPRESS_NOT_AVAILABLE else OptionBlock.REMOTE_COMPRESS_NEEDS_SSH
            else -> null
        }
    }

    /** True when LOCAL_STAGING behaves like a direct stream (local destinations write in place). */
    fun stagingIsDirect(destinationType: DestinationType?): Boolean =
        destinationType != null && TransferPlanner.isLocal(destinationType)

    /**
     * Applies the hard rules to [options] for [keyChoice]: TWRP output is forced plain (RAW_COPY, no
     * codec, level 0, no encryption, no masking), the encryption mode follows the key choice and
     * masking is dropped without encryption. The level is kept (clamped only on save) so switching
     * formats back and forth does not lose it.
     */
    fun normalize(options: PipelineOptions, keyChoice: KeyChoice): PipelineOptions {
        var result = options.copy(archiveFormat = treeFormatOf(options.archiveFormat, options.compressionCodec))
        var choice = keyChoice
        if (result.twrpCompatible) {
            choice = KeyChoice.NONE
            result = result.copy(
                archiveFormat = ArchiveFormat.RAW_COPY,
                compressionCodec = CompressionCodec.NONE,
                compressionLevel = 0,
                maskFilenames = false,
            )
        }
        return result.copy(
            encryptionMode = if (choice.encrypts) EncryptionMode.AES_256_GCM else EncryptionMode.NONE,
            maskFilenames = result.maskFilenames && choice.encrypts,
        )
    }

    /** [keyChoice] after the TWRP rule (TWRP output cannot be encrypted). */
    fun effectiveKeyChoice(keyChoice: KeyChoice, options: PipelineOptions): KeyChoice =
        if (options.twrpCompatible) KeyChoice.NONE else keyChoice

    /** Same threshold as ui/mapping's compressionLevelLabelRes: 0..19 scales call 16+ "ultra". */
    const val ULTRA_THRESHOLD = ULTRA_COMPRESSION_THRESHOLD
}
