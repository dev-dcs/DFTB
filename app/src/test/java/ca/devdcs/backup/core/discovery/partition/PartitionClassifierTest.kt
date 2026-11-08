package ca.devdcs.backup.core.discovery.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionClassifierTest {

    @Test
    fun `categories across vendors`() {
        mapOf(
            "boot" to PartitionCategory.BOOT_CHAIN, "vbmeta_vendor" to PartitionCategory.BOOT_CHAIN,
            "xbl_config" to PartitionCategory.BOOT_CHAIN, "preloader_raw" to PartitionCategory.BOOT_CHAIN,
            "UP_PARAM" to PartitionCategory.BOOT_CHAIN, "modem" to PartitionCategory.MODEM,
            "RADIO" to PartitionCategory.MODEM, "md1img" to PartitionCategory.MODEM,
            "modemst1" to PartitionCategory.PERSIST_IDENTITY, "modem_userdata" to PartitionCategory.PERSIST_IDENTITY,
            "protect_f" to PartitionCategory.PERSIST_IDENTITY, "SEC_EFS" to PartitionCategory.PERSIST_IDENTITY,
            "userdata" to PartitionCategory.USERDATA, "metadata" to PartitionCategory.USERDATA,
            "super" to PartitionCategory.SYSTEM_LOGICAL, "vendor_dlkm" to PartitionCategory.SYSTEM_LOGICAL,
            "bluetooth" to PartitionCategory.FIRMWARE, "connsys_wifi" to PartitionCategory.FIRMWARE,
            "misc" to PartitionCategory.OTHER,
        ).forEach { (name, expected) -> assertEquals(name, expected, PartitionClassifier.category(name)) }
    }

    @Test
    fun `mapper nodes are logical unless user data`() {
        assertEquals(PartitionCategory.SYSTEM_LOGICAL, PartitionClassifier.category("system-verity", isMapper = true))
        assertEquals(PartitionCategory.USERDATA, PartitionClassifier.category("userdata", isMapper = true))
    }

    @Test
    fun `security and device-specific partitions are critical`() {
        listOf("steady", "keyrefuge", "keydata", "secdata", "ssd", "keystore", "storsec", "uefivarstore").forEach {
            assertEquals(it, PartitionCategory.PERSIST_IDENTITY, PartitionClassifier.category(it))
        }
        assertEquals(PartitionCategory.FIRMWARE, PartitionClassifier.category("ddr"))
        assertEquals(PartitionCategory.BOOT_CHAIN, PartitionClassifier.category("UP_PARAM"))
        listOf("ddr", "PARAM", "up_param").forEach {
            assertEquals(it, PartitionRisk.CRITICAL_IDENTITY, PartitionClassifier.risk(it, PartitionClassifier.category(it), 1 shl 20))
        }
        // Device-specific but not identity: not pulled into the STANDARD view.
        assertFalse(PartitionViews.isStandard("ddr", includeSuper = false))
        assertTrue(PartitionViews.isStandard("keystore", includeSuper = false))
    }

    @Test
    fun `risk prefers identity then size`() {
        assertEquals(PartitionRisk.CRITICAL_IDENTITY, PartitionClassifier.risk("efs", PartitionCategory.PERSIST_IDENTITY, 1))
        assertEquals(PartitionRisk.HUGE, PartitionClassifier.risk("super", PartitionCategory.SYSTEM_LOGICAL, null))
        assertEquals(PartitionRisk.HUGE, PartitionClassifier.risk("oem_big", PartitionCategory.OTHER, PartitionClassifier.HUGE_THRESHOLD_BYTES))
        assertEquals(PartitionRisk.SAFE_TO_IMAGE, PartitionClassifier.risk("boot", PartitionCategory.BOOT_CHAIN, 64L shl 20))
    }
}
