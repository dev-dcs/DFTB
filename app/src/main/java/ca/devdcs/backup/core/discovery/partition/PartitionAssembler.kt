package ca.devdcs.backup.core.discovery.partition

/**
 * Turns the parsed scan sections into a [PartitionScanOutcome]. Pure and deterministic so the
 * whole "raw shell output -> model" path is unit-testable against captured vendor fixtures.
 *
 * Merging rules:
 * - by-name roots are de-duplicated by partition name; `/dev/block/by-name` wins, then
 * `/dev/block/bootdevice/by-name`, then platform roots in listing order. Disagreeing targets are
 * reported as [PartitionScanWarningKind.BY_NAME_CONFLICT].
 * - `readlink -f` targets are preferred; the `ls -l` target fills in when readlink printed nothing.
 * - Logical partitions come from /dev/block/mapper links, then sysfs `dm/name`, then `dmctl`
 * (matched to dm-N through /proc/partitions major:minor).
 * - Sizes come from sysfs (512-byte sectors), else /proc/partitions (1 KiB blocks).
 * - A `_a`/`_b` suffix is treated as a slot only on A/B devices or when the counterpart exists.
 */
object PartitionAssembler {

    /**
     * Assemble the outcome. [scriptErrors] are stderr lines; [exitCode] the script's status.
     * Returns [PartitionScanUnavailable] only when no partition at all was found.
     */
    fun assemble(
        sections: PartitionScanSections,
        scriptErrors: List<String> = emptyList(),
        exitCode: Int = 0,
    ): PartitionScanOutcome {
        val warnings = ArrayList<PartitionScanWarning>()
        val slotProps = PartitionParsers.parseSlotProperties(PartitionParsers.parseProperties(sections.properties))

        val proc = PartitionParsers.parseProcPartitions(sections.procPartitions)
        val procByName = proc.associateBy { it.name }
        val sysSizes = PartitionParsers.parseSysfsSizes(sections.sysfsSizes)

        val byName = mergeByName(sections, warnings)
        val logical = collectLogical(sections, proc, slotProps, warnings)

        if (byName.isEmpty()) warnings += PartitionScanWarning(PartitionScanWarningKind.NO_BY_NAME_DIRECTORY)

        val all = byName.map { it to PartitionSource.BY_NAME } + logical.map { it to PartitionSource.MAPPER }
        if (all.isEmpty()) {
            return PartitionScanUnavailable(
                PartitionUnavailableReason.NO_PARTITIONS_FOUND,
                scriptErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")?.take(MAX_DETAIL),
            )
        }

        val namesBySource = all.groupBy({ it.second }, { it.first.name.lowercase() }).mapValues { it.value.toSet() }
        val partitions = all.map { (link, source) ->
            buildPartition(link, source, slotProps, namesBySource[source].orEmpty(), sysSizes, procByName)
        }.sortedWith(compareBy<DiscoveredPartition>({ it.source.ordinal }, { it.name.lowercase() }))

        if (slotProps.abDevice && slotProps.activeSlot == null) {
            warnings += PartitionScanWarning(PartitionScanWarningKind.ACTIVE_SLOT_UNKNOWN, slotProps.slotSuffix)
        }
        val unsized = partitions.filter { it.sizeBytes == null }.map { it.name }
        if (unsized.isNotEmpty()) warnings += PartitionScanWarning(PartitionScanWarningKind.SIZES_INCOMPLETE, unsized.joinToString(", "))
        val unresolved = partitions.filter { it.realDevice == null }.map { it.name }
        if (unresolved.isNotEmpty()) warnings += PartitionScanWarning(PartitionScanWarningKind.UNRESOLVED_LINKS, unresolved.joinToString(", "))
        if (!sections.complete || exitCode != 0 || scriptErrors.any { it.isNotBlank() }) {
            val detail = buildList {
                if (!sections.complete) add("output truncated")
                if (exitCode != 0) add("exit $exitCode")
                addAll(scriptErrors.filter { it.isNotBlank() })
            }.joinToString("\n").take(MAX_DETAIL)
            warnings += PartitionScanWarning(PartitionScanWarningKind.SCRIPT_PARTIAL, detail)
        }

        return PartitionScanResult(
            abDevice = slotProps.abDevice,
            activeSlot = slotProps.activeSlot,
            dynamicPartitions = slotProps.dynamicPartitions,
            virtualAb = slotProps.virtualAb,
            partitions = partitions,
            warnings = warnings,
        )
    }

    private fun mergeByName(sections: PartitionScanSections, warnings: MutableList<PartitionScanWarning>): List<BlockLink> {
        val readlinks = PartitionParsers.parseLinkListing(sections.byNameLinks)
        val lsLinks = PartitionParsers.parseLsSection(sections.byNameLs).associateBy { it.linkPath }
        // readlink rows are authoritative; ls rows fill gaps and cover a missing readlink section.
        val combined = LinkedHashMap<String, BlockLink>()
        readlinks.forEach { link -> combined[link.linkPath] = if (link.realPath == null) link.copy(realPath = lsLinks[link.linkPath]?.realPath) else link }
        lsLinks.values.forEach { link -> combined.putIfAbsent(link.linkPath, link) }

        val dirOrder = combined.values.map { it.directory }.distinct()
        fun priority(dir: String): Int = when (dir) {
            PartitionScanScript.BY_NAME -> 0
            PartitionScanScript.BOOTDEVICE_BY_NAME -> 1
            else -> 2 + dirOrder.indexOf(dir)
        }

        val chosen = LinkedHashMap<String, BlockLink>()
        val conflicts = LinkedHashSet<String>()
        combined.values.sortedBy { priority(it.directory) }.forEach { link ->
            val existing = chosen[link.name]
            if (existing == null) {
                chosen[link.name] = link
            } else if (existing.realPath != null && link.realPath != null && existing.realPath != link.realPath) {
                conflicts += link.name
            } else if (existing.realPath == null && link.realPath != null) {
                chosen[link.name] = existing.copy(realPath = link.realPath)
            }
        }
        if (conflicts.isNotEmpty()) warnings += PartitionScanWarning(PartitionScanWarningKind.BY_NAME_CONFLICT, conflicts.joinToString(", "))
        return chosen.values.toList()
    }

    private fun collectLogical(
        sections: PartitionScanSections,
        proc: List<ProcPartition>,
        slotProps: SlotProperties,
        warnings: MutableList<PartitionScanWarning>,
    ): List<BlockLink> {
        val out = LinkedHashMap<String, BlockLink>()
        PartitionParsers.parseLinkListing(sections.mapperLinks).forEach { out.putIfAbsent(it.name, it) }

        val dmNames = PartitionParsers.parseDmNames(sections.dmNames)
        dmNames.forEach { (dev, name) ->
            if (name !in out) {
                val node = "/dev/block/$dev"
                out[name] = BlockLink("/dev/block", name, node, node)
            }
        }

        val procByDevNum = proc.associateBy { it.major to it.minor }
        PartitionParsers.parseDmctl(sections.dmctl).forEach { dm ->
            if (dm.name !in out) {
                val dev = procByDevNum[dm.major to dm.minor]?.name ?: "dm-${dm.minor}"
                val node = "/dev/block/$dev"
                out[dm.name] = BlockLink("/dev/block", dm.name, node, node)
            }
        }

        if (out.isEmpty() && slotProps.dynamicPartitions) {
            warnings += PartitionScanWarning(PartitionScanWarningKind.DM_NAMES_UNAVAILABLE)
        }
        return out.values.toList()
    }

    private fun buildPartition(
        link: BlockLink,
        source: PartitionSource,
        slotProps: SlotProperties,
        siblingNames: Set<String>,
        sysSizes: Map<String, Long>,
        procByName: Map<String, ProcPartition>,
    ): DiscoveredPartition {
        val (strippedBase, rawSlot) = PartitionParsers.splitSlot(link.name)
        val counterpart = (strippedBase + rawSlot.other().suffix).lowercase()
        val slotted = rawSlot != PartitionSlot.NONE && (slotProps.abDevice || counterpart in siblingNames)
        val slot = if (slotted) rawSlot else PartitionSlot.NONE
        val baseName = if (slotted) strippedBase else link.name

        val kernelName = link.realPath?.substringAfterLast('/')
        val size = kernelName?.let { sysSizes[it] ?: procByName[it]?.sizeBytes }

        val isMapper = source == PartitionSource.MAPPER || kernelName?.startsWith("dm-") == true
        val category = PartitionClassifier.category(baseName, isMapper)
        return DiscoveredPartition(
            name = link.name,
            baseName = baseName,
            slot = slot,
            blockDevice = link.linkPath,
            realDevice = link.realPath,
            sizeBytes = size,
            isLogical = isMapper || PartitionClassifier.isSuper(baseName),
            category = category,
            isActiveSlot = slot == PartitionSlot.NONE || slot == slotProps.activeSlot,
            risk = PartitionClassifier.risk(baseName, category, size),
            source = source,
        )
    }

    private const val MAX_DETAIL = 2000
}
