package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import kotlinx.coroutines.CoroutineDispatcher
import java.io.OutputStream

/**
 * RAW_COPY and DD_IMAGE: the artifact *is* the bytes of one file or stream (a partition image,
 * a single copied file), with no container. Compression is applied by the caller around [out]
 * per PipelineOptions.compressionCodec, so this writer only enforces "exactly one byte item"
 * and copies it verbatim.
 */
internal class SingleItemArchiveWriter(
    override val format: ArchiveFormat,
    out: OutputStream,
    io: CoroutineDispatcher,
) : BaseArchiveWriter(out, io) {

    init {
        require(format == ArchiveFormat.RAW_COPY || format == ArchiveFormat.DD_IMAGE) { "Not a single-item format: $format" }
    }

    private var writtenPath: String? = null

    override suspend fun writeItem(item: SourceItem, path: String) {
        writtenPath?.let {
            throw ArchiveArgumentException(
                ArchiveErrorKind.SINGLE_ITEM_ONLY,
                "${format.name} holds exactly one item; already wrote '$it', refusing '$path'",
            )
        }
        val sink: (ByteArray, Int, Int) -> Unit = { b, o, l -> out.write(b, o, l) }
        when (item) {
            is SourceItem.File -> item.open().use { StreamCopy.copyExactly(it, item.sizeBytes, path, sink) }
            is SourceItem.Stream -> item.open().use { input ->
                val exact = item.expectedSizeBytes?.takeIf { item.sizeIsExact }
                if (exact != null) StreamCopy.copyExactly(input, exact, path, sink) else StreamCopy.copyAll(input, sink)
            }
            is SourceItem.Directory, is SourceItem.Symlink -> throw ArchiveArgumentException(
                ArchiveErrorKind.SINGLE_ITEM_ONLY,
                "${format.name} accepts one File or Stream item, not a ${item::class.simpleName} ('$path')",
            )
        }
        writtenPath = path
    }

    override suspend fun finishArchive() {
        if (writtenPath == null) {
            throw ArchiveArgumentException(ArchiveErrorKind.SINGLE_ITEM_ONLY, "${format.name} finished without its one item")
        }
    }

    override fun release(completed: Boolean) = Unit
}
