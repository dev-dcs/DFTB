package ca.devdcs.backup.core.discovery.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppClassifierTest {

    @Test
    fun `root managers and critical packages by exact name`() {
        assertTrue(AppClassifier.isRootManager("com.topjohnwu.magisk"))
        assertTrue(AppClassifier.isRootManager("me.weishu.kernelsu"))
        assertTrue(AppClassifier.isRootManager("me.bmax.apatch"))
        assertFalse(AppClassifier.isRootManager("com.topjohnwu.magisk.fake"))
        assertTrue(AppClassifier.isCriticalSystem("android"))
        assertTrue(AppClassifier.isCriticalSystem("com.android.providers.settings"))
        assertFalse(AppClassifier.isCriticalSystem("com.example.bank"))
    }

    @Test
    fun `self detection covers debug and release ids`() {
        assertTrue(AppClassifier.isSelf("ca.devdcs.backup.debug", "ca.devdcs.backup.debug", "ca.devdcs.backup"))
        assertTrue(AppClassifier.isSelf("ca.devdcs.backup", "ca.devdcs.backup.debug", "ca.devdcs.backup"))
        assertFalse(AppClassifier.isSelf("ca.devdcs.backupx", "ca.devdcs.backup", "ca.devdcs.backup"))
    }

    @Test
    fun `kind from flags`() {
        assertEquals(AppKind.USER, AppClassifier.kindOf(isSystem = false, isUpdatedSystem = false))
        assertEquals(AppKind.SYSTEM, AppClassifier.kindOf(isSystem = true, isUpdatedSystem = false))
        assertEquals(AppKind.UPDATED_SYSTEM, AppClassifier.kindOf(isSystem = true, isUpdatedSystem = true))
    }

    @Test
    fun `badges and restore warning`() {
        val flags = AppSpecialFlags(
            isSplitBundle = true, hasObb = null, isSelf = false, isRootManager = true, isCriticalSystem = false,
            allowBackup = false, isDebuggable = false, isApex = false,
        )
        assertTrue(flags.warnOnRestore)
        assertEquals(listOf(AppFlagBadge.ROOT_MANAGER, AppFlagBadge.SPLIT, AppFlagBadge.NO_ALLOW_BACKUP), flags.badges())
    }
}
