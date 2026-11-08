package ca.devdcs.backup.core.discovery.partition

/**
 * Name-based classification of partitions into [PartitionCategory] and [PartitionRisk]. Vendor
 * layouts (Pixel/Tensor, Qualcomm, Samsung uppercase names, MediaTek) use well-known names, so a
 * curated table is far more reliable than heuristics; unknown names fall back to OTHER/SAFE.
 * Matching is case-insensitive on the slot-stripped base name.
 */
object PartitionClassifier {

    /** Above this size an unrecognised partition is flagged [PartitionRisk.HUGE]. */
    const val HUGE_THRESHOLD_BYTES: Long = 4L * 1024 * 1024 * 1024

    private val IDENTITY_EXACT = setOf(
        "persist", "persistbak", "efs", "efs_backup", "sec_efs", "cpefs", "modemst1", "modemst2",
        "fsg", "fsc", "nvdata", "nvram", "nvcfg", "proinfo", "modem_userdata", "frp", "persistent",
        "devinfo", "seccfg", "prodnv", "persdata", "steady", "keyrefuge", "keydata", "secdata", "ssd",
        "keystore", "storsec", "uefivarstore",
    )

    /**
     * Not identity data, but device-specific state (DDR training, Samsung bootloader params):
     * restoring another device's copy can stop the device booting, so it is never SAFE_TO_IMAGE.
     */
    private val DEVICE_SPECIFIC = setOf("ddr", "mdmddr", "param", "up_param")
    private val IDENTITY_PREFIX = listOf("protect", "mdm1m9kefs")

    private val USERDATA_EXACT = setOf("userdata", "data", "metadata", "cache")

    private val LOGICAL_EXACT = setOf(
        "super", "system", "system_ext", "product", "vendor", "vendor_dlkm", "system_dlkm", "odm",
        "odm_dlkm", "mi_ext", "prism", "optics",
    )

    private val MODEM_EXACT = setOf("modem", "radio", "md1img", "md1dsp", "md1rom", "md3img", "md_udc", "mdm_oem")
    private val MODEM_PREFIX = listOf("modem", "radio", "md1", "md3")

    private val BOOT_EXACT = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "recovery", "dtbo", "dtb", "abl",
        "aboot", "sbl1", "rpm", "tz", "hyp", "devcfg", "keymaster", "pvmfw", "lk", "lk2", "bootloader",
        "bl1", "bl2", "bl31", "pbl", "gsa", "ldfw", "tzsw", "uboot", "sboot", "up_param", "param",
        "imagefv", "qupfw", "shrm", "cpucp", "tee1", "tee2",
    )
    private val BOOT_PREFIX = listOf("vbmeta", "xbl", "preloader", "tee", "aop", "uefi", "cmnlib", "keymaster", "imagefv")

    private val FIRMWARE_EXACT = setOf(
        "dsp", "adsp", "cdsp", "bluetooth", "spunvm", "spss", "featenabler", "multiimgoem", "scp",
        "sspm", "mcupm", "gz", "dpm", "spmfw", "audio_dsp", "logo", "splash", "apusys", "gpueb", "vcp",
        "ccu", "mvpu", "ddr", "rtice", "fw", "dcp", "mdmddr",
    )
    private val FIRMWARE_PREFIX = listOf("connsys", "cam_vpu", "gz")

    private val HUGE_NAMES = setOf("userdata", "data", "super")

    /** Category for [baseName]; [isMapper] forces device-mapper nodes into SYSTEM_LOGICAL unless they are user data. */
    fun category(baseName: String, isMapper: Boolean = false): PartitionCategory {
        val n = baseName.lowercase()
        return when {
            n in IDENTITY_EXACT || IDENTITY_PREFIX.any(n::startsWith) -> PartitionCategory.PERSIST_IDENTITY
            n in USERDATA_EXACT -> PartitionCategory.USERDATA
            isMapper || n in LOGICAL_EXACT -> PartitionCategory.SYSTEM_LOGICAL
            n in MODEM_EXACT || MODEM_PREFIX.any(n::startsWith) -> PartitionCategory.MODEM
            n in BOOT_EXACT || BOOT_PREFIX.any(n::startsWith) -> PartitionCategory.BOOT_CHAIN
            n in FIRMWARE_EXACT || FIRMWARE_PREFIX.any(n::startsWith) -> PartitionCategory.FIRMWARE
            else -> PartitionCategory.OTHER
        }
    }

    /** Risk for a partition: identity data dominates, then size. */
    fun risk(baseName: String, category: PartitionCategory, sizeBytes: Long?): PartitionRisk = when {
        category == PartitionCategory.PERSIST_IDENTITY -> PartitionRisk.CRITICAL_IDENTITY
        baseName.lowercase() in DEVICE_SPECIFIC -> PartitionRisk.CRITICAL_IDENTITY
        baseName.lowercase() in HUGE_NAMES -> PartitionRisk.HUGE
        sizeBytes != null && sizeBytes >= HUGE_THRESHOLD_BYTES -> PartitionRisk.HUGE
        else -> PartitionRisk.SAFE_TO_IMAGE
    }

    /** `super` is the physical container of the logical partitions, so it counts as logical. */
    fun isSuper(baseName: String): Boolean = baseName.equals("super", ignoreCase = true)
}
