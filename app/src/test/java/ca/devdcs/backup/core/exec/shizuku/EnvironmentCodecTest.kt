package ca.devdcs.backup.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one wire format that both the app process and the privileged Shizuku process must agree
 * on: if encode/decode ever drift, every Shizuku-brokered command would silently lose or corrupt its
 * environment, so the round-trip and the edge cases are pinned here.
 */
class EnvironmentCodecTest {

    @Test
    fun `encode then apply round-trips a simple map`() {
        val source = mapOf("PATH" to "/system/bin", "LANG" to "C")
        val target = HashMap<String, String>()

        EnvironmentCodec.applyTo(target, EnvironmentCodec.encode(source))

        assertEquals("/system/bin", target["PATH"])
        assertEquals("C", target["LANG"])
        assertEquals(2, target.size)
    }

    @Test
    fun `empty environment encodes to nothing and leaves target untouched`() {
        val target = hashMapOf("EXISTING" to "keep")

        val encoded = EnvironmentCodec.encode(emptyMap())
        EnvironmentCodec.applyTo(target, encoded)

        assertTrue(encoded.isEmpty())
        assertEquals(1, target.size)
        assertEquals("keep", target["EXISTING"])
    }

    @Test
    fun `values may contain equals signs`() {
        val target = HashMap<String, String>()

        EnvironmentCodec.applyTo(target, arrayOf("TOKEN=a=b=c", "KV=k=v"))

        assertEquals("a=b=c", target["TOKEN"])
        assertEquals("k=v", target["KV"])
    }

    @Test
    fun `overrides layer on top of inherited entries`() {
        val target = hashMapOf("PATH" to "/inherited")

        EnvironmentCodec.applyTo(target, arrayOf("PATH=/override"))

        assertEquals("/override", target["PATH"])
    }

    @Test
    fun `malformed entries without a key or separator are ignored`() {
        val target = HashMap<String, String>()

        EnvironmentCodec.applyTo(target, arrayOf("=novalue", "nokeyorsep", "GOOD=1"))

        assertFalse(target.containsKey(""))
        assertFalse(target.containsKey("nokeyorsep"))
        assertEquals("1", target["GOOD"])
        assertEquals(1, target.size)
    }

    @Test
    fun `empty value is preserved`() {
        val target = HashMap<String, String>()

        EnvironmentCodec.applyTo(target, EnvironmentCodec.encode(mapOf("EMPTY" to "")))

        assertTrue(target.containsKey("EMPTY"))
        assertEquals("", target["EMPTY"])
    }
}
