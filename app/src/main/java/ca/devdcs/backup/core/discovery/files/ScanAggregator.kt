package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.FileCategory
import java.util.EnumMap
import java.util.PriorityQueue

/**
 * Keeps the [capacity] greatest elements by [comparator] in O(log capacity) per offer, so "largest
 * 20 files" over millions of entries needs constant memory. Not thread-safe (one per scan).
 */
class TopN<T>(private val capacity: Int, private val comparator: Comparator<in T>) {
    // Min-heap: the head is the smallest kept element, evicted when a greater one arrives.
    private val heap = PriorityQueue<T>(maxOf(1, capacity), comparator)

    fun offer(item: T) {
        if (capacity <= 0) return
        if (heap.size < capacity) {
            heap.add(item)
        } else if (comparator.compare(item, heap.peek()) > 0) {
            heap.poll()
            heap.add(item)
        }
    }

    /** Kept elements, greatest first. */
    fun snapshot(): List<T> = heap.sortedWith(comparator.reversed())

    val size: Int get() = heap.size
}

/**
 * Pure accumulator behind [ScanSummary]: walkers feed it events, the scanner reads [progress] and
 * finally [summary]. Separated from I/O so aggregation, top-N and truncation are unit-testable.
 */
class ScanAggregator(
    private val topN: Int = ScanOptions.DEFAULT_TOP_N,
    private val collectFiles: Boolean = false,
    private val maxCollected: Int = ScanOptions.DEFAULT_MAX_COLLECTED,
) {
    private class MutableStat {
        var count = 0L
        var bytes = 0L
        fun add(size: Long) {
            count++
            bytes += size
        }
    }

    var dirsVisited: Long = 0
        private set
    var filesMatched: Long = 0
        private set
    var bytesMatched: Long = 0
        private set
    var skippedUnreadable: Long = 0
        private set
    var truncated: Boolean = false
        private set

    private val extensions = HashMap<String, MutableStat>()
    private val categories = EnumMap<FileCategory, MutableStat>(FileCategory::class.java)
    private val unclassified = MutableStat()
    private val largest = TopN(topN, compareBy<ScannedFile> { it.sizeBytes }.thenBy { it.location })
    private var oldest: ScannedFile? = null
    private var newest: ScannedFile? = null
    private val collected = ArrayList<ScannedFile>()

    fun onDirectory() {
        dirsVisited++
    }

    fun onUnreadable() {
        skippedUnreadable++
    }

    fun onMatch(file: ScannedFile) {
        filesMatched++
        bytesMatched += file.sizeBytes
        val extension = file.extension
        extensions.getOrPut(extension) { MutableStat() }.add(file.sizeBytes)
        val category = FileCategory.classify(extension)
        if (category == null) unclassified.add(file.sizeBytes) else categories.getOrPut(category) { MutableStat() }.add(file.sizeBytes)
        largest.offer(file)
        if (file.modifiedAtMillis > 0) {
            if (oldest.let { it == null || file.modifiedAtMillis < it.modifiedAtMillis }) oldest = file
            if (newest.let { it == null || file.modifiedAtMillis > it.modifiedAtMillis }) newest = file
        }
        if (collectFiles) {
            if (collected.size < maxCollected) collected.add(file) else truncated = true
        }
    }

    fun progress(currentPath: String?): ScanProgress = ScanProgress(dirsVisited, filesMatched, bytesMatched, currentPath)

    /** Collected files (empty unless collecting); the list is owned by the caller afterwards. */
    fun files(): List<ScannedFile> = if (collectFiles) collected.toList() else emptyList()

    fun summary(): ScanSummary = ScanSummary(
        matchedCount = filesMatched,
        matchedBytes = bytesMatched,
        dirsVisited = dirsVisited,
        skippedUnreadable = skippedUnreadable,
        byExtension = extensions.entries
            .map { ExtensionStat(it.key, it.value.count, it.value.bytes) }
            .sortedWith(compareByDescending<ExtensionStat> { it.count }.thenByDescending { it.bytes }.thenBy { it.extension })
            .take(topN),
        byCategory = categories.entries.associate { it.key to CategoryStat(it.value.count, it.value.bytes) },
        unclassified = CategoryStat(unclassified.count, unclassified.bytes),
        largestFiles = largest.snapshot(),
        oldest = oldest,
        newest = newest,
    )
}
