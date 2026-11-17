package ca.devdcs.backup.core.discovery.partition

/**
 * Captured-style scan outputs for the vendor layouts the scanner must handle. Vendor-format
 * sections (`ls -l`, /proc/partitions, `dmctl`) are raw text as the tools print them; the
 * script's own tab-separated rows are built with [link] / [nv] so the tabs are explicit.
 */
object PartitionFixtures {

    private const val M = PartitionScanScript.MARKER

    fun link(path: String, real: String?): String = "L\t$path\t${real ?: ""}"
    fun dir(path: String): String = "D\t$path"
    fun nv(name: String, value: Any): String = "$name\t$value"

    /** Assemble script stdout from sections in script order. [complete] appends the end marker. */
    fun stdout(
        props: List<String> = emptyList(),
        byName: List<String> = emptyList(),
        byNameLs: List<String> = emptyList(),
        proc: String = "",
        sys: List<String> = emptyList(),
        dmName: List<String> = emptyList(),
        mapper: List<String> = emptyList(),
        dmctl: String = "",
        complete: Boolean = true,
    ): List<String> = buildList {
        add("$M ${PartitionScanScript.SECTION_PROPS}"); addAll(props)
        add("$M ${PartitionScanScript.SECTION_BYNAME}"); addAll(byName)
        add("$M ${PartitionScanScript.SECTION_BYNAME_LS}"); addAll(byNameLs)
        add("$M ${PartitionScanScript.SECTION_PROC}"); if (proc.isNotEmpty()) addAll(proc.lines())
        add("$M ${PartitionScanScript.SECTION_SYS}"); addAll(sys)
        add("$M ${PartitionScanScript.SECTION_DMNAME}"); addAll(dmName)
        add("$M ${PartitionScanScript.SECTION_MAPPER}"); addAll(mapper)
        add("$M ${PartitionScanScript.SECTION_DMCTL}"); if (dmctl.isNotEmpty()) addAll(dmctl.lines())
        if (complete) add("$M ${PartitionScanScript.SECTION_END}")
    }

    // ---------------------------------------------------------------- Pixel 8 (Tensor G3, UFS)

    private val pixelByName: List<Pair<String, String>> = listOf(
        "abl_a" to "sdb5", "abl_b" to "sdc5", "bl1_a" to "sdb1", "bl1_b" to "sdc1",
        "boot_a" to "sda12", "boot_b" to "sda13", "init_boot_a" to "sda14", "init_boot_b" to "sda15",
        "vendor_boot_a" to "sda16", "vendor_boot_b" to "sda17", "dtbo_a" to "sda18", "dtbo_b" to "sda19",
        "vbmeta_a" to "sda20", "vbmeta_b" to "sda21", "vbmeta_system_a" to "sda22", "vbmeta_system_b" to "sda23",
        "vbmeta_vendor_a" to "sda24", "vbmeta_vendor_b" to "sda25", "modem_a" to "sda26", "modem_b" to "sda27",
        "frp" to "sda1", "misc" to "sda2", "metadata" to "sda3", "persist" to "sda4", "efs" to "sda5",
        "efs_backup" to "sda6", "modem_userdata" to "sda7", "devinfo" to "sda8",
        "super" to "sda28", "userdata" to "sda29",
    )

    private val pixelSectors: Map<String, Long> = mapOf(
        "sdb1" to 256, "sdc1" to 256, "sdb5" to 4096, "sdc5" to 4096,
        "sda1" to 1024, "sda2" to 2048, "sda3" to 131072, "sda4" to 65536, "sda5" to 65536,
        "sda6" to 65536, "sda7" to 16384, "sda8" to 8,
        "sda12" to 131072, "sda13" to 131072, "sda14" to 16384, "sda15" to 16384,
        "sda16" to 131072, "sda17" to 131072, "sda18" to 32768, "sda19" to 32768,
        "sda20" to 128, "sda21" to 128, "sda22" to 128, "sda23" to 128, "sda24" to 128, "sda25" to 128,
        "sda26" to 524288, "sda27" to 524288, "sda28" to 18874368, "sda29" to 482344960,
    )

    val pixel8: List<String> = stdout(
        props = listOf("ro.boot.slot_suffix=_a", "ro.build.ab_update=true", "ro.boot.dynamic_partitions=true", "ro.virtual_ab.enabled=true"),
        byName = buildList {
            for (d in listOf("/dev/block/by-name", "/dev/block/platform/13200000.ufs/by-name")) {
                add(dir(d))
                pixelByName.forEach { (n, dev) -> add(link("$d/$n", "/dev/block/$dev")) }
            }
        },
        byNameLs = listOf(dir("/dev/block/by-name")) + pixelByName.map { (n, dev) ->
            "lrwxrwxrwx 1 root root 16 2024-10-01 09:12 $n -> /dev/block/$dev"
        },
        proc = """
            major minor  #blocks  name

               8        0  241172480 sda
               8       12      65536 sda12
             254        0    2442776 dm-0
             254        1     604520 dm-1
             254        2    3121204 dm-2
             254        3     866384 dm-3
             254        4      49152 dm-4
             254        5     230144 dm-5
             254        6  241172480 dm-6
        """.trimIndent(),
        sys = pixelSectors.map { (dev, s) -> nv(dev, s) },
        dmName = listOf(nv("dm-0", "system_a"), nv("dm-1", "system_ext_a"), nv("dm-2", "product_a"), nv("dm-3", "vendor_a")),
        mapper = listOf(
            link("/dev/block/mapper/system_a", "/dev/block/dm-0"),
            link("/dev/block/mapper/system_ext_a", "/dev/block/dm-1"),
            link("/dev/block/mapper/product_a", "/dev/block/dm-2"),
            link("/dev/block/mapper/vendor_a", "/dev/block/dm-3"),
            link("/dev/block/mapper/vendor_dlkm_a", "/dev/block/dm-4"),
            link("/dev/block/mapper/system_dlkm_a", "/dev/block/dm-5"),
            link("/dev/block/mapper/userdata", "/dev/block/dm-6"),
        ),
        dmctl = """
            Available Device Mapper Devices:
            system_a             : 254:0
            system_ext_a         : 254:1
            product_a            : 254:2
            vendor_a             : 254:3
            vendor_dlkm_a        : 254:4
            system_dlkm_a        : 254:5
            userdata             : 254:6
        """.trimIndent(),
    )

    // ------------------------------------------- Samsung Galaxy A (Exynos, eMMC, non-A/B, toolbox)

    private const val SAMSUNG_DIR = "/dev/block/platform/13540000.dwmmc0/by-name"
    private val samsungNames = listOf(
        "BOTA0" to "mmcblk0p1", "BOTA1" to "mmcblk0p2", "EFS" to "mmcblk0p3", "PARAM" to "mmcblk0p8",
        "BOOT" to "mmcblk0p10", "RECOVERY" to "mmcblk0p11", "RADIO" to "mmcblk0p12", "PERSISTENT" to "mmcblk0p13",
        "SYSTEM" to "mmcblk0p14", "CACHE" to "mmcblk0p15", "HIDDEN" to "mmcblk0p16", "USERDATA" to "mmcblk0p17",
    )

    val samsungEmmc: List<String> = stdout(
        props = listOf("ro.boot.slot_suffix=", "ro.build.ab_update=", "ro.boot.dynamic_partitions=", "ro.virtual_ab.enabled="),
        // readlink missing on this toolbox: every target empty, ls -l must fill them in.
        byName = listOf(dir(SAMSUNG_DIR)) + samsungNames.map { (n, _) -> link("$SAMSUNG_DIR/$n", null) },
        byNameLs = listOf(dir(SAMSUNG_DIR)) + samsungNames.map { (n, dev) ->
            "lrwxrwxrwx root     root              2017-03-14 11:02 $n -> /dev/block/$dev"
        },
        proc = """
            major minor  #blocks  name

             179        0   30539776 mmcblk0
             179        1       4096 mmcblk0p1
             179        2       4096 mmcblk0p2
             179        3      20480 mmcblk0p3
             179        8       8192 mmcblk0p8
             179       10      36864 mmcblk0p10
             179       11      40960 mmcblk0p11
             179       12      46080 mmcblk0p12
             179       13       1024 mmcblk0p13
             179       14    3584000 mmcblk0p14
             179       15     204800 mmcblk0p15
             179       16     102400 mmcblk0p16
             179       17   26411008 mmcblk0p17
        """.trimIndent(),
    )

    // ---------------------------------------------- MediaTek Dimensity (UFS, A/B, slot b active)

    private val mtkNames = listOf(
        "boot_a" to "sdc20", "boot_b" to "sdc40", "vendor_boot_a" to "sdc21", "vendor_boot_b" to "sdc41",
        "dtbo_a" to "sdc22", "dtbo_b" to "sdc42", "vbmeta_a" to "sdc23", "vbmeta_b" to "sdc43",
        "md1img_a" to "sdc24", "md1img_b" to "sdc44", "lk_a" to "sdc25", "lk_b" to "sdc45",
        "tee_a" to "sdc26", "tee_b" to "sdc46", "preloader_raw_a" to "sda", "preloader_raw_b" to "sdb",
        "nvcfg" to "sdc5", "nvdata" to "sdc6", "nvram" to "sdc7", "protect1" to "sdc8", "protect2" to "sdc9",
        "persist" to "sdc10", "proinfo" to "sdc4", "seccfg" to "sdc11", "para" to "sdc2", "expdb" to "sdc3",
        "frp" to "sdc12", "metadata" to "sdc13", "super" to "sdc60", "userdata" to "sdc61",
    )

    val mediatek: List<String> = stdout(
        props = listOf("[ro.boot.slot_suffix]: [_b]", "[ro.build.ab_update]: [true]", "[ro.boot.dynamic_partitions]: [true]", "[ro.virtual_ab.enabled]: [true]"),
        byName = buildList {
            for (d in listOf("/dev/block/by-name", "/dev/block/platform/bootdevice/by-name")) {
                add(dir(d))
                mtkNames.forEach { (n, dev) -> add(link("$d/$n", "/dev/block/$dev")) }
            }
        },
        proc = buildString {
            appendLine("major minor  #blocks  name")
            appendLine()
            mtkNames.forEachIndexed { i, (_, dev) -> appendLine("   8 ${32 + i} ${if (dev == "sdc61") 115343360 else 32768} $dev") }
            appendLine(" 253        0    1937408 dm-0")
            appendLine(" 253        1     925696 dm-1")
            append(" 253        2    1320960 dm-2")
        },
        dmctl = """
            Available Device Mapper Devices:
            system_b             : 253:0
            vendor_b             : 253:1
            product_b            : 253:2
        """.trimIndent(),
    )

    // ------------------------------- Xiaomi (Snapdragon 8 Gen 2, UFS, A/B, three by-name roots)

    private val xiaomiNames = listOf(
        "xbl_a" to "sdb1", "xbl_b" to "sdc1", "xbl_config_a" to "sdb2", "xbl_config_b" to "sdc2",
        "abl_a" to "sde10", "abl_b" to "sde40", "boot_a" to "sde11", "boot_b" to "sde41",
        "init_boot_a" to "sde12", "init_boot_b" to "sde42", "vendor_boot_a" to "sde13", "vendor_boot_b" to "sde43",
        "dtbo_a" to "sde14", "dtbo_b" to "sde44", "vbmeta_a" to "sde15", "vbmeta_b" to "sde45",
        "vbmeta_system_a" to "sde16", "vbmeta_system_b" to "sde46", "modem_a" to "sde17", "modem_b" to "sde47",
        "bluetooth_a" to "sde18", "bluetooth_b" to "sde48", "dsp_a" to "sde19", "dsp_b" to "sde49",
        "modemst1" to "sdf2", "modemst2" to "sdf3", "fsg" to "sde5", "fsc" to "sdf4",
        "persist" to "sda2", "persistbak" to "sda3", "cust" to "sda4", "logfs" to "sda5",
        "metadata" to "sda6", "super" to "sda7", "userdata" to "sda8",
    )

    val xiaomi: List<String> = stdout(
        props = listOf("ro.boot.slot_suffix=_a", "ro.build.ab_update=true", "ro.boot.dynamic_partitions=true", "ro.virtual_ab.enabled=true"),
        byName = buildList {
            for (d in listOf("/dev/block/by-name", "/dev/block/bootdevice/by-name", "/dev/block/platform/soc/1d84000.ufshc/by-name")) {
                add(dir(d))
                xiaomiNames.forEach { (n, dev) -> add(link("$d/$n", "/dev/block/$dev")) }
            }
        },
        sys = xiaomiNames.map { (_, dev) -> nv(dev, if (dev == "sda8") 900000000L else 8192L) },
        dmName = listOf(nv("dm-0", "system_a"), nv("dm-1", "vendor_a"), nv("dm-2", "mi_ext_a")),
    )
}
