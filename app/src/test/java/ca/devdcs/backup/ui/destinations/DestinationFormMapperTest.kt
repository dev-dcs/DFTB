package ca.devdcs.backup.ui.destinations

import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.DestinationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationFormMapperTest {

    @Test
    fun configKeepsOnlyRelevantTrimmedFields() {
        val form = DestinationForm(
            type = DestinationType.SFTP,
            name = "NAS",
            host = " nas.local ",
            port = "2222",
            basePath = " /backups ",
            username = " me ",
            remoteName = "ignored",
            treeUri = "content://ignored",
            options = mapOf(DestinationOptions.SMB_SHARE to "ignored", "rclone.custom" to "kept"),
        )
        val config = DestinationFormMapper.toConfig(form)
        assertEquals("nas.local", config.host)
        assertEquals(2222, config.port)
        assertEquals("/backups", config.basePath)
        assertEquals("me", config.username)
        assertNull(config.remoteName)
        assertNull(config.treeUri)
        assertEquals(mapOf("rclone.custom" to "kept"), config.options)
    }

    @Test
    fun advancedOptionsAreWrittenUnderBackendKeys() {
        val form = DestinationForm(type = DestinationType.SMB, name = "S", host = "nas")
            .withOption(FormField.SMB_SHARE, " Backups ")
            .withSwitch(FormField.SMB_ENCRYPT, true)
            .withSwitch(FormField.SMB_GUEST, false)
        val config = DestinationFormMapper.toConfig(form)
        assertEquals(mapOf("share" to "Backups", "encrypt" to "true"), config.options)
    }

    @Test
    fun s3AccessKeyNeverLandsInConfig() {
        val form = DestinationForm(type = DestinationType.S3, name = "S3", basePath = "bucket", username = "AKIA", password = "secret")
        assertNull(DestinationFormMapper.toConfig(form).username)
        val plan = DestinationFormMapper.secretPlan(form) as SecretPlan.Replace
        assertEquals(PlannedSecret.Password("AKIA", "secret"), plan.secret)
    }

    @Test
    fun secretPlanFollowsInputs() {
        val base = DestinationForm(type = DestinationType.SFTP, name = "N", host = "h", username = "me")
        assertEquals(
            SecretPlan.Replace(PlannedSecret.Password("me", "pw")),
            DestinationFormMapper.secretPlan(base.copy(password = "pw")),
        )
        val stored = base.copy(hasStoredSecret = true, storedSecretMethod = AuthMethod.PASSWORD)
        assertEquals(SecretPlan.UpdateUsername("me"), DestinationFormMapper.secretPlan(stored))
        val key = stored.copy(authMethod = AuthMethod.PRIVATE_KEY, privateKey = "-----BEGIN KEY-----\n\n", passphrase = "")
        assertEquals(
            SecretPlan.Replace(PlannedSecret.SshKey("-----BEGIN KEY-----\n", null)),
            DestinationFormMapper.secretPlan(key),
        )
        val storedKey = base.copy(authMethod = AuthMethod.PRIVATE_KEY, hasStoredSecret = true, storedSecretMethod = AuthMethod.PRIVATE_KEY)
        assertEquals(SecretPlan.Keep, DestinationFormMapper.secretPlan(storedKey))
        val ftp = DestinationForm(type = DestinationType.FTP, name = "F", host = "h", authMethod = AuthMethod.NONE, hasStoredSecret = true)
        assertEquals(SecretPlan.Remove, DestinationFormMapper.secretPlan(ftp))
    }

    @Test
    fun formRoundTripsFromDestination() {
        val destination = Destination(
            id = "d1",
            name = "Share",
            type = DestinationType.SMB,
            config = DestinationConfig(host = "nas", port = 445, basePath = "/Backups", username = "me", options = mapOf("domain" to "WORK")),
            credentialRef = "dest-1",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val form = DestinationForm.from(destination, AuthMethod.PASSWORD, treeVolumeId = null)
        assertEquals(AuthMethod.PASSWORD, form.authMethod)
        assertTrue(form.canKeepStoredSecret)
        assertEquals("", form.password)
        assertEquals(destination.config, DestinationFormMapper.toConfig(form))

        val anonymous = DestinationForm.from(destination.copy(credentialRef = null), null, null)
        assertEquals(AuthMethod.NONE, anonymous.authMethod)
        assertFalse(anonymous.hasStoredSecret)
    }

    @Test
    fun pcloudIsNotOfferedDirectly() {
        assertFalse(DestinationType.PCLOUD in DestinationTypeSpec.selectableTypes)
        assertTrue(DestinationType.SFTP in DestinationTypeSpec.selectableTypes)
    }
}
