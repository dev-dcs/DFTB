package ca.devdcs.backup.ui.restore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationWordTest {

    @Test
    fun `exact word is accepted`() {
        assertTrue(ConfirmationWord.accepted("IDENTITY", "IDENTITY"))
    }

    @Test
    fun `case and surrounding or repeated spaces are ignored`() {
        assertTrue(ConfirmationWord.accepted("  identity ", "IDENTITY"))
        assertTrue(ConfirmationWord.accepted("other   device", "OTHER DEVICE"))
        assertTrue(ConfirmationWord.accepted("Other\tDevice", "OTHER DEVICE"))
    }

    @Test
    fun `other text is refused`() {
        assertFalse(ConfirmationWord.accepted("", "IDENTITY"))
        assertFalse(ConfirmationWord.accepted("IDENTIT", "IDENTITY"))
        assertFalse(ConfirmationWord.accepted("IDENTITY!", "IDENTITY"))
        assertFalse(ConfirmationWord.accepted("OTHERDEVICE", "OTHER DEVICE"))
    }

    @Test
    fun `an empty expected word never confirms`() {
        assertFalse(ConfirmationWord.accepted("", ""))
        assertFalse(ConfirmationWord.accepted("   ", "  "))
    }

    @Test
    fun `localized words compare without regard to case`() {
        assertTrue(ConfirmationWord.accepted("ταυτότητα", "ΤΑΥΤΌΤΗΤΑ"))
        assertTrue(ConfirmationWord.accepted("身份", "身份"))
    }
}
