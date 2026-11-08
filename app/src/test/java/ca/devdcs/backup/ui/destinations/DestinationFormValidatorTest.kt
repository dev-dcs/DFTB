package ca.devdcs.backup.ui.destinations

import ca.devdcs.backup.domain.model.DestinationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationFormValidatorTest {

    private fun sftp(
        host: String = "nas.local",
        port: String = "",
        user: String = "me",
        password: String = "pw",
    ) = DestinationForm(type = DestinationType.SFTP, name = "NAS", host = host, port = port, username = user, password = password)

    @Test
    fun validSftpFormHasNoErrors() {
        assertEquals(emptyMap<FormField, FormError>(), DestinationFormValidator.validate(sftp()))
    }

    @Test
    fun nameIsRequiredAndBounded() {
        assertEquals(FormError.REQUIRED, DestinationFormValidator.nameError("  "))
        assertEquals(FormError.TOO_LONG, DestinationFormValidator.nameError("x".repeat(DestinationFormValidator.MAX_NAME_LENGTH + 1)))
        assertNull(DestinationFormValidator.nameError("Backups"))
    }

    @Test
    fun hostAcceptsNamesAndAddresses() {
        listOf("nas.local", "192.168.1.10", "my_nas", "::1", "[fe80::1%wlan0]", "example.com.").forEach {
            assertTrue(it, DestinationFormValidator.isValidHost(it))
        }
        listOf("", "nas local", "-bad.example", "sftp://host", "[::1", "a..b").forEach {
            assertFalse(it, DestinationFormValidator.isValidHost(it))
        }
        assertEquals(FormError.REQUIRED, DestinationFormValidator.validate(sftp(host = ""))[FormField.HOST])
        assertEquals(FormError.INVALID_HOST, DestinationFormValidator.validate(sftp(host = "host:22"))[FormField.HOST])
    }

    @Test
    fun portMustBeInRange() {
        assertNull(DestinationFormValidator.portError(""))
        assertNull(DestinationFormValidator.portError("2222"))
        assertEquals(FormError.INVALID_PORT, DestinationFormValidator.portError("0"))
        assertEquals(FormError.INVALID_PORT, DestinationFormValidator.portError("65536"))
        assertEquals(FormError.INVALID_PORT, DestinationFormValidator.portError("+22"))
        assertEquals(FormError.INVALID_PORT, DestinationFormValidator.validate(sftp(port = "abc"))[FormField.PORT])
    }

    @Test
    fun pathTraversalIsRejected() {
        val form = sftp().copy(basePath = "/backups/../etc")
        assertEquals(FormError.PATH_TRAVERSAL, DestinationFormValidator.validate(form)[FormField.BASE_PATH])
    }

    @Test
    fun sftpNeedsUsernameAndCredential() {
        val errors = DestinationFormValidator.validate(sftp(user = "", password = ""))
        assertEquals(FormError.REQUIRED, errors[FormField.USERNAME])
        assertEquals(FormError.REQUIRED, errors[FormField.PASSWORD])
    }

    @Test
    fun storedSecretCanBeKeptByLeavingItBlank() {
        val form = sftp(password = "").copy(hasStoredSecret = true, storedSecretMethod = AuthMethod.PASSWORD)
        assertNull(DestinationFormValidator.validate(form)[FormField.PASSWORD])
        // Switching method means the stored secret no longer applies.
        val keyForm = form.copy(authMethod = AuthMethod.PRIVATE_KEY)
        assertEquals(FormError.REQUIRED, DestinationFormValidator.validate(keyForm)[FormField.PRIVATE_KEY])
    }

    @Test
    fun privateKeyMustLookLikeAKey() {
        val form = sftp(password = "").copy(authMethod = AuthMethod.PRIVATE_KEY, privateKey = "hello")
        assertEquals(FormError.INVALID_KEY, DestinationFormValidator.validate(form)[FormField.PRIVATE_KEY])
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
        assertNull(DestinationFormValidator.validate(form.copy(privateKey = pem))[FormField.PRIVATE_KEY])
        assertTrue(DestinationFormValidator.looksLikePrivateKey("PuTTY-User-Key-File-3: ssh-ed25519\n"))
    }

    @Test
    fun smbNeedsAShare() {
        val base = DestinationForm(type = DestinationType.SMB, name = "Share", host = "nas", authMethod = AuthMethod.NONE)
        assertEquals(FormError.NEEDS_SHARE, DestinationFormValidator.validate(base)[FormField.BASE_PATH])
        assertNull(DestinationFormValidator.validate(base.copy(basePath = "/Backups/phone"))[FormField.BASE_PATH])
        val withOption = base.withOption(FormField.SMB_SHARE, "Backups")
        assertTrue(DestinationFormValidator.validate(withOption).isEmpty())
    }

    @Test
    fun nfsNeedsAbsoluteExportAndSafeOptions() {
        val base = DestinationForm(type = DestinationType.NFS, name = "NFS", host = "10.0.0.2")
        assertEquals(FormError.NEEDS_EXPORT, DestinationFormValidator.validate(base)[FormField.BASE_PATH])
        assertEquals(FormError.PATH_NOT_ABSOLUTE, DestinationFormValidator.validate(base.copy(basePath = "srv"))[FormField.BASE_PATH])
        val ok = base.copy(basePath = "/srv/backups")
        assertTrue(DestinationFormValidator.validate(ok).isEmpty())
        val unsafe = ok.withOption(FormField.NFS_MOUNT_OPTIONS, "vers=4;reboot")
        assertEquals(FormError.UNSAFE_MOUNT_OPTIONS, DestinationFormValidator.validate(unsafe)[FormField.NFS_MOUNT_OPTIONS])
    }

    @Test
    fun timeoutsAndPinAreChecked() {
        val ftps = DestinationForm(type = DestinationType.FTPS, name = "FTP", host = "ftp.example.com", authMethod = AuthMethod.NONE)
        assertTrue(DestinationFormValidator.validate(ftps).isEmpty())
        val badTimeout = ftps.withOption(FormField.FTP_CONNECT_TIMEOUT, "0")
        assertEquals(FormError.INVALID_TIMEOUT, DestinationFormValidator.validate(badTimeout)[FormField.FTP_CONNECT_TIMEOUT])
        val badPin = ftps.withOption(FormField.TLS_PIN, "abcd")
        assertEquals(FormError.INVALID_PIN, DestinationFormValidator.validate(badPin)[FormField.TLS_PIN])
        val goodPin = ftps.withOption(FormField.TLS_PIN, "AB:".repeat(31) + "AB")
        assertNull(DestinationFormValidator.validate(goodPin)[FormField.TLS_PIN])
        val badMode = ftps.withOption(FormField.FTPS_MODE, "sometimes")
        assertEquals(FormError.INVALID_CHOICE, DestinationFormValidator.validate(badMode)[FormField.FTPS_MODE])
    }

    @Test
    fun webDavAcceptsUrlOrHostPortPath() {
        val base = DestinationForm(type = DestinationType.WEBDAV, name = "Dav", authMethod = AuthMethod.NONE)
        assertTrue(DestinationFormValidator.validate(base.copy(host = "https://cloud.example.com/remote.php/dav")).isEmpty())
        assertTrue(DestinationFormValidator.validate(base.copy(host = "cloud.example.com:8443/dav")).isEmpty())
        assertTrue(DestinationFormValidator.validate(base.copy(host = "[::1]:8080")).isEmpty())
        assertEquals(FormError.INVALID_URL, DestinationFormValidator.validate(base.copy(host = "ftp://cloud.example.com"))[FormField.HOST])
        val badScheme = base.copy(host = "cloud.example.com").withOption(FormField.WEBDAV_SCHEME, "gopher")
        assertEquals(FormError.INVALID_CHOICE, DestinationFormValidator.validate(badScheme)[FormField.WEBDAV_SCHEME])
    }

    @Test
    fun s3NeedsBucketAndKeyPair() {
        val base = DestinationForm(type = DestinationType.S3, name = "S3")
        val errors = DestinationFormValidator.validate(base)
        assertEquals(FormError.NEEDS_BUCKET, errors[FormField.BASE_PATH])
        assertEquals(FormError.REQUIRED, errors[FormField.USERNAME])
        assertEquals(FormError.REQUIRED, errors[FormField.PASSWORD])
        val ok = base.copy(basePath = "bucket/phone", username = "AKIA", password = "secret")
        assertTrue(DestinationFormValidator.validate(ok).isEmpty())
        val endpoint = ok.withOption(FormField.S3_ENDPOINT, "https://s3.example.com:9000")
        assertTrue(DestinationFormValidator.validate(endpoint).isEmpty())
        val badEndpoint = ok.withOption(FormField.S3_ENDPOINT, "https://bad host")
        assertEquals(FormError.INVALID_URL, DestinationFormValidator.validate(badEndpoint)[FormField.S3_ENDPOINT])
        // Keeping a stored key pair: both blank is fine, a new access key alone is not.
        val stored = base.copy(basePath = "bucket", hasStoredSecret = true, storedSecretMethod = AuthMethod.PASSWORD)
        assertTrue(DestinationFormValidator.validate(stored).isEmpty())
        assertEquals(FormError.REQUIRED, DestinationFormValidator.validate(stored.copy(username = "AKIA2"))[FormField.PASSWORD])
    }

    @Test
    fun megaNeedsEmail() {
        val form = DestinationForm(type = DestinationType.MEGA, name = "Mega", username = "not-an-email", password = "pw")
        assertEquals(FormError.INVALID_EMAIL, DestinationFormValidator.validate(form)[FormField.USERNAME])
        assertTrue(DestinationFormValidator.validate(form.copy(username = "me@example.com")).isEmpty())
    }

    @Test
    fun rcloneRemoteMustExistWhenKnown() {
        val form = DestinationForm(type = DestinationType.RCLONE, name = "Drive", remoteName = "gdrive")
        assertTrue(DestinationFormValidator.validate(form, setOf("gdrive")).isEmpty())
        assertTrue(DestinationFormValidator.validate(form, null).isEmpty())
        assertEquals(FormError.UNKNOWN_REMOTE, DestinationFormValidator.validate(form, setOf("other"))[FormField.REMOTE_NAME])
        assertEquals(FormError.INVALID_REMOTE_NAME, DestinationFormValidator.remoteNameError("-bad", null))
    }

    @Test
    fun usbNeedsVolumeMatchingTheTree() {
        val form = DestinationForm(type = DestinationType.USB_OTG, name = "USB", treeUri = "content://x/tree/1A2B-3C4D%3A", treeVolumeId = "1A2B-3C4D")
        assertEquals(FormError.REQUIRED, DestinationFormValidator.validate(form)[FormField.USB_VOLUME])
        assertTrue(DestinationFormValidator.validate(form.copy(usbVolumeId = "1a2b-3c4d")).isEmpty())
        assertEquals(FormError.VOLUME_MISMATCH, DestinationFormValidator.validate(form.copy(usbVolumeId = "FFFF-0000"))[FormField.TREE_URI])
        assertEquals(FormError.REQUIRED, DestinationFormValidator.validate(form.copy(treeUri = "", usbVolumeId = "1A2B-3C4D"))[FormField.TREE_URI])
    }

    @Test
    fun localInternalPathMustBeAbsolute() {
        val form = DestinationForm(type = DestinationType.LOCAL_INTERNAL, name = "Local", basePath = "backups")
        assertEquals(FormError.PATH_NOT_ABSOLUTE, DestinationFormValidator.validate(form)[FormField.BASE_PATH])
        assertTrue(DestinationFormValidator.validate(form.copy(basePath = "")).isEmpty())
    }
}
