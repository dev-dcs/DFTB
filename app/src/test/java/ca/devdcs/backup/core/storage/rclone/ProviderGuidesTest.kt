package ca.devdcs.backup.core.storage.rclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderGuidesTest {

    @Test
    fun `catalogue covers the required providers with unique ids`() {
        val ids = ProviderGuides.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val types = ProviderGuides.all.map { it.rcloneType }.toSet()
        listOf("s3", "b2", "drive", "onedrive", "dropbox", "mega", "pcloud", "box", "sftp", "webdav").forEach {
            assertTrue("missing $it", it in types)
        }
        val s3Providers = ProviderGuides.all.filter { it.rcloneType == "s3" }.map { it.fixedOptions["provider"] }.toSet()
        assertEquals(setOf("AWS", "Minio", "Wasabi", "Cloudflare"), s3Providers)
    }

    @Test
    fun `every guide is internally consistent`() {
        ProviderGuides.all.forEach { guide ->
            assertTrue(guide.id, guide.stepsRes.isNotEmpty())
            assertTrue(guide.id, RcloneIni.isValidRemoteName(guide.suggestedRemoteName))
            assertTrue(guide.id, guide.rcloneType in RcloneIni.KNOWN_TYPES)
            assertEquals(guide.id, guide.fields.size, guide.fields.map { it.key }.toSet().size)
            guide.fields.forEach { field ->
                if (field.kind == GuideFieldKind.CHOICE) assertTrue(field.key, field.defaultValue in field.choices)
                if (field.obscure || field.kind == GuideFieldKind.PASSWORD) assertTrue(field.key, field.secret)
                if (RcloneIni.isSecretKey(field.key)) assertTrue("${guide.id}.${field.key} must be secret", field.secret)
            }
            guide.atLeastOneOf.flatten().forEach { assertNotNull(guide.field(it)) }
            val hasToken = guide.fields.any { it.kind == GuideFieldKind.OAUTH_TOKEN }
            assertEquals(guide.id, guide.oauth, hasToken)
            if (guide.oauth) {
                assertEquals("rclone authorize \"${guide.rcloneType}\"", guide.authorizeCommand)
            } else {
                assertNull(guide.authorizeCommand)
            }
        }
    }

    @Test
    fun `lookup by id`() {
        assertEquals(ProviderGuides.B2, ProviderGuides.byId("b2"))
        assertNull(ProviderGuides.byId("nope"))
    }
}
