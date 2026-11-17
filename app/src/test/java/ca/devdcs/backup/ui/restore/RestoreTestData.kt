package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestEncryption
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec

/** Small manifest builders shared by the restore UI tests. */
internal object RestoreTestData {

    fun item(path: String, size: Long? = null, type: ManifestItem.ItemType = ManifestItem.ItemType.FILE, encrypted: Boolean = false) = ManifestItem(
        archivePath = path,
        type = type,
        sizeBytes = size,
        encryption = if (encrypted) ManifestEncryption("dftb-aesgcm-v1", emptyMap()) else null,
    )

    fun artifact(
        sourceId: String,
        kind: String,
        name: String?,
        items: List<ManifestItem>,
        layout: String = "archive",
        stored: Long = 100L,
        source: Long = 0L,
        encrypted: Boolean = false,
    ) = ManifestArtifact(
        sourceId = sourceId,
        kind = kind,
        name = name,
        storedName = (name ?: sourceId) + ".bin",
        masked = false,
        layout = layout,
        format = if (layout == "raw_stream") ArchiveFormat.DD_IMAGE else ArchiveFormat.TAR,
        codec = CompressionCodec.NONE,
        compressionLevel = 0,
        sizeBytes = stored,
        sourceBytes = source,
        digests = emptyMap(),
        encryption = if (encrypted) ManifestEncryption("dftb-aesgcm-v1", emptyMap()) else null,
        checksumFile = "checksums.sha256",
        items = items,
    )

    fun app(pkg: String, dataBytes: Long = 50L) = artifact(
        sourceId = "apps:$pkg",
        kind = "app",
        name = pkg,
        items = listOf(item("apps/$pkg/manifest.json", 1L), item("apps/$pkg/base.apk", dataBytes)),
    )

    fun partition(node: String, size: Long = 4096L, logical: Boolean = false) = artifact(
        sourceId = "partitions:$node",
        kind = "partition",
        name = node,
        layout = "raw_stream",
        source = size,
        items = listOf(item(if (logical) "partitions/mapper/$node.img" else "partitions/$node.img", size, ManifestItem.ItemType.STREAM)),
    )

    fun files(id: String, name: String, vararg sizes: Long) = artifact(
        sourceId = "files:$id",
        kind = "files",
        name = name,
        items = sizes.mapIndexed { i, s -> item("files/root/f$i", s) },
    )

    fun nandroid(name: String) = artifact(
        sourceId = "nandroid:$name",
        kind = "nandroid",
        name = name,
        layout = "file_tree",
        items = listOf(item("TWRP/BACKUPS/serial/$name/boot.emmc.win", 10L)),
    )

    fun unit(id: String, kind: RestoreUnitKind, bytes: Long = 10L, nandroid: Boolean = false, critical: Boolean = false) = RestoreUnitInfo(
        id = id,
        kind = kind,
        name = id,
        artifactCount = 1,
        storedBytes = bytes,
        restoreBytes = bytes,
        encrypted = false,
        nandroid = nandroid,
        criticalIdentity = critical,
    )
}
