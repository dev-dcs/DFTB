package ca.devdcs.backup.core.pipeline.checksum

import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ChecksumEngineTest {

    private val engine = DefaultChecksumEngine(Dispatchers.IO)
    private val all = ChecksumAlgorithm.entries.toSet()
    private val millionA = ByteArray(1_000_000) { 'a'.code.toByte() }

    private val expected: Map<String, Map<ChecksumAlgorithm, String>> = mapOf(
        "" to mapOf(
            ChecksumAlgorithm.MD5 to "d41d8cd98f00b204e9800998ecf8427e",
            ChecksumAlgorithm.SHA1 to "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            ChecksumAlgorithm.SHA256 to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ChecksumAlgorithm.SHA512 to "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            ChecksumAlgorithm.BLAKE3 to "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
        ),
        "abc" to mapOf(
            ChecksumAlgorithm.MD5 to "900150983cd24fb0d6963f7d28e17f72",
            ChecksumAlgorithm.SHA1 to "a9993e364706816aba3e25717850c26c9cd0d89d",
            ChecksumAlgorithm.SHA256 to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ChecksumAlgorithm.SHA512 to "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            ChecksumAlgorithm.BLAKE3 to "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85",
        ),
        "1M a" to mapOf(
            ChecksumAlgorithm.MD5 to "7707d6ae4e027c70eea2a935c2296f21",
            ChecksumAlgorithm.SHA1 to "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
            ChecksumAlgorithm.SHA256 to "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            ChecksumAlgorithm.SHA512 to "e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973ebde0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b",
            ChecksumAlgorithm.BLAKE3 to "616f575a1b58d4c9797d4217b9730ae5e6eb319d76edef6549b46f4efe31ff8b",
        ),
    )

    private fun input(label: String): ByteArray = when (label) {
        "" -> ByteArray(0)
        "abc" -> "abc".toByteArray()
        else -> millionA
    }

    @Test
    fun digestKnownAnswers() = runBlocking {
        for ((label, hashes) in expected) {
            val data = input(label)
            val r = engine.digest(ByteArrayInputStream(data), all)
            assertEquals(data.size.toLong(), r.bytes)
            assertEquals("vector '$label'", hashes, r.hex)
        }
    }

    @Test
    fun teeKnownAnswersSinglePassAndPassThrough() {
        for ((label, hashes) in expected) {
            val data = input(label)
            val sink = ByteArrayOutputStream()
            val tee = engine.tee(sink, all)
            // Mixed write sizes, including single bytes, to exercise both write paths.
            var i = 0
            var step = 1
            while (i < data.size) {
                val n = minOf(step, data.size - i)
                if (n == 1) tee.write(data[i].toInt()) else tee.write(data, i, n)
                i += n
                step = step * 3 + 1
                if (step > 70_000) step = 1
            }
            tee.close()
            assertTrue(data.contentEquals(sink.toByteArray()))
            assertEquals(data.size.toLong(), tee.result().bytes)
            assertEquals("vector '$label'", hashes, tee.result().hex)
        }
    }

    /** Official BLAKE3 test_vectors.json inputs (byte i = i % 251), covering chunk/tree boundaries. */
    @Test
    fun blake3OfficialVectors() = runBlocking {
        val vectors = mapOf(
            1 to "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213",
            1023 to "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11",
            1024 to "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7",
            1025 to "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444",
            2048 to "e776b6028c7cd22a4d0ba182a8bf62205d2ef576467e838ed6f2529b85fba24a",
            2049 to "5f4d72f40d7a5f82b15ca2b2e44b1de3c2ef86c426c95c1af0b6879522563030",
            3072 to "b98cb0ff3623be03326b373de6b9095218513e64f1ee2edd2525c7ad1e5cffd2",
            4097 to "9b4052b38f1c5fc8b1f9ff7ac7b27cd242487b3d890d15c96a1c25b8aa0fb995",
            8193 to "bab6c09cb8ce8cf459261398d2e7aef35700bf488116ceb94a36d0f5f1b7bc3b",
            31744 to "62b6960e1a44bcc1eb1a611a8d6235b6b4b78f32e7abc4fb4c6cdcce94895c47",
            102400 to "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085",
        )
        for ((len, hex) in vectors) {
            val data = ByteArray(len) { (it % 251).toByte() }
            val r = engine.digest(ByteArrayInputStream(data), setOf(ChecksumAlgorithm.BLAKE3))
            assertEquals("len $len", hex, r.hex[ChecksumAlgorithm.BLAKE3])
        }
    }

    @Test
    fun emptyAlgorithmSetStillCountsBytes() = runBlocking {
        val r = engine.digest(ByteArrayInputStream(ByteArray(12345)), emptySet())
        assertEquals(12345L, r.bytes)
        assertTrue(r.hex.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun resultBeforeCloseThrows() {
        engine.tee(ByteArrayOutputStream(), setOf(ChecksumAlgorithm.SHA256)).result()
    }
}
