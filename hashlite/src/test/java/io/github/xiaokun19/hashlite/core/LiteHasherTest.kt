package io.github.xiaokun19.hashlite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 期望值来源：由本机 OpenSSL 3.0.13 与 Python hashlib 生成并交叉比对（见仓库 README）。
 */
class LiteHasherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val empty = mapOf(
        LiteAlgorithm.MD5 to "d41d8cd98f00b204e9800998ecf8427e",
        LiteAlgorithm.SHA1 to "da39a3ee5e6b4b0d3255bfef95601890afd80709",
        LiteAlgorithm.SHA224 to "d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f",
        LiteAlgorithm.SHA256 to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        LiteAlgorithm.SHA384 to "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b",
        LiteAlgorithm.SHA512 to "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
    )

    private val abc = mapOf(
        LiteAlgorithm.MD5 to "900150983cd24fb0d6963f7d28e17f72",
        LiteAlgorithm.SHA1 to "a9993e364706816aba3e25717850c26c9cd0d89d",
        LiteAlgorithm.SHA224 to "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7",
        LiteAlgorithm.SHA256 to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        LiteAlgorithm.SHA384 to "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7",
        LiteAlgorithm.SHA512 to "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
    )

    private val millionA = mapOf(
        LiteAlgorithm.MD5 to "7707d6ae4e027c70eea2a935c2296f21",
        LiteAlgorithm.SHA1 to "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
        LiteAlgorithm.SHA224 to "20794655980c91d8bbb4c1ea97618a4bf03f42581948b2ee4ee7ad67",
        LiteAlgorithm.SHA256 to "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
        LiteAlgorithm.SHA384 to "9d0e1809716474cb086e834e310a4a1ced149e9c00f248527972cec5704c2a5b07b8b3dc38ecc4ebae97ddd87f3d8985",
        LiteAlgorithm.SHA512 to "e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973ebde0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b",
    )

    private fun oneShot(data: ByteArray, algorithm: LiteAlgorithm): String {
        // 用算法自己的摘要实现（JCA / BouncyCastle / CRC32 都能覆盖）；JDK 上并没有 SM3
        val digest = algorithm.newDigest()
        digest.update(data)
        return HashParse.toHex(digest.finish())
    }

    @Test
    fun `published vectors match for all supported algorithms`() {
        val cases = listOf(
            "empty" to (ByteArray(0) to empty),
            "abc" to ("abc".toByteArray() to abc),
            "1e6 x 'a'" to (ByteArray(1_000_000) { 'a'.code.toByte() } to millionA),
        )
        for ((name, case) in cases) {
            val (data, expected) = case
            for ((algorithm, value) in expected) {
                assertEquals("$name ${algorithm.label}", value, oneShot(data, algorithm))
            }
        }
    }

    private fun writePattern(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            var written = 0
            var seed = 11
            while (written < size) {
                for (i in chunk.indices) {
                    seed = seed * 1103515245 + 12345
                    chunk[i] = (seed ushr 16).toByte()
                }
                val length = minOf(chunk.size, size - written)
                out.write(chunk, 0, length)
                written += length
            }
        }
        return file
    }

    @Test
    fun `engine result equals straight sequential hashing`() {
        val size = 2 * 1024 * 1024 + 777
        val file = writePattern("pattern.bin", size)
        val bytes = file.readBytes()
        val algorithms = LiteAlgorithm.entries.toList()

        for (blockSize in intArrayOf(256 * 1024, 1024 * 1024, 4 * 1024 * 1024)) {
            for (threads in intArrayOf(1, 2, 4)) {
                val outcome = LiteHasher(blockSize, threads).hash(FileHashSource(file), algorithms)
                assertNull("block=$blockSize threads=$threads 报错: ${outcome.error}", outcome.error)
                assertTrue(outcome.success)
                assertEquals(size.toLong(), outcome.totalBytes)
                for (algorithm in algorithms) {
                    assertEquals(
                        "block=$blockSize threads=$threads ${algorithm.label}",
                        oneShot(bytes, algorithm),
                        outcome.hexByAlgorithm[algorithm],
                    )
                }
            }
        }
    }

    @Test
    fun `unknown size falls back to sequential reading`() {
        val file = writePattern("unknown.bin", 1024 * 1024 + 13)
        val outcome = LiteHasher().hash(ZeroSizeSource(file), listOf(LiteAlgorithm.SHA256))
        assertNull(outcome.error)
        assertEquals(
            oneShot(file.readBytes(), LiteAlgorithm.SHA256),
            outcome.hexByAlgorithm[LiteAlgorithm.SHA256],
        )
    }

    @Test
    fun `progress is monotonic and finishes at total`() {
        val size = 1024 * 1024 + 5
        val file = writePattern("progress.bin", size)
        val seen = ArrayList<Long>()
        val outcome = LiteHasher(256 * 1024, 2).hash(
            source = FileHashSource(file),
            algorithms = listOf(LiteAlgorithm.SHA256),
            onProgress = { seen += it.doneBytes },
        )
        assertEquals(outcome.totalBytes, seen.lastOrNull())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `empty file hashes to the empty digest`() {
        val file = temp.newFile("empty.bin")
        val outcome = LiteHasher().hash(FileHashSource(file), listOf(LiteAlgorithm.SHA256))
        assertEquals(0L, outcome.totalBytes)
        assertEquals(empty[LiteAlgorithm.SHA256], outcome.hexByAlgorithm[LiteAlgorithm.SHA256])
    }

    @Test
    fun `no algorithm selected reports an error instead of crashing`() {
        val file = writePattern("none.bin", 1024)
        val outcome = LiteHasher().hash(FileHashSource(file), emptyList())
        assertTrue(!outcome.success)
        assertTrue(outcome.hexByAlgorithm.isEmpty())
    }

    /** 新增算法（SHA-3 / SM3 / CRC32）的公开向量：openssl 与 Python zlib 生成。 */
    private val extra = mapOf(
        "empty" to Pair(
            ByteArray(0),
            mapOf(
                LiteAlgorithm.SHA3_256 to "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
                LiteAlgorithm.SHA3_512 to "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a615b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
                LiteAlgorithm.SM3 to "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
                LiteAlgorithm.CRC32 to "00000000",
            ),
        ),
        "abc" to Pair(
            "abc".toByteArray(),
            mapOf(
                LiteAlgorithm.SHA3_256 to "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
                LiteAlgorithm.SHA3_512 to "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0",
                LiteAlgorithm.SM3 to "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
                LiteAlgorithm.CRC32 to "352441c2",
            ),
        ),
        "1e6 x 'a'" to Pair(
            ByteArray(1_000_000) { 'a'.code.toByte() },
            mapOf(
                LiteAlgorithm.SHA3_256 to "5c8875ae474a3634ba4fd55ec85bffd661f32aca75c6d699d0cdcb6c115891c1",
                LiteAlgorithm.SHA3_512 to "3c3a876da14034ab60627c077bb98f7e120a2a5370212dffb3385a18d4f38859ed311d0a9d5141ce9cc5c66ee689b266a8aa18ace8282a0e0db596c90b0a7b87",
                LiteAlgorithm.SM3 to "c8aaf89429554029e231941a2acc0ad61ff2a5acd8fadd25847a3a732b3b02c3",
                LiteAlgorithm.CRC32 to "dc25bfbc",
            ),
        ),
    )

    @Test
    fun `extra algorithms match published vectors`() {
        for ((name, case) in extra) {
            val (data, expected) = case
            for ((algorithm, value) in expected) {
                val digest = algorithm.newDigest()
                digest.update(data)
                assertEquals("$name ${algorithm.label}", value, HashParse.toHex(digest.finish()))
            }
        }
    }

    @Test
    fun `extra algorithms survive chunked feeding through the engine`() {
        val file = writePattern("extra.bin", 1024 * 1024 + 321)
        val bytes = file.readBytes()
        val algorithms = listOf(
            LiteAlgorithm.SHA3_256,
            LiteAlgorithm.SHA3_512,
            LiteAlgorithm.SM3,
            LiteAlgorithm.CRC32,
        )
        val outcome = LiteHasher(256 * 1024, 2).hash(FileHashSource(file), algorithms)
        assertNull(outcome.error)
        for (algorithm in algorithms) {
            val digest = algorithm.newDigest()
            digest.update(bytes)
            assertEquals(algorithm.label, HashParse.toHex(digest.finish()), outcome.hexByAlgorithm[algorithm])
        }
    }

    @Test
    fun `crc32 helper agrees with java util zip semantics`() {
        // 自写的软件 CRC32 只用于加速探测的基线，必须和 java.util.zip 完全一致
        val data = "123456789".toByteArray()
        val software = SoftwareCrc32().apply { update(data) }.finish()
        val platform = java.util.zip.CRC32().apply { update(data) }.value
        assertEquals(platform, software)
        assertEquals(0xCBF43926L, software) // CRC-32/ISO-HDLC 的经典校验值
    }

    /** 模拟"provider 不告诉文件大小"的来源。 */
    private class ZeroSizeSource(private val file: File) : HashSource {
        override val displayName: String get() = file.name
        override val size: Long get() = 0L
        private var handle: RandomAccessFile? = null

        override fun openChannel(): FileChannel {
            val raf = RandomAccessFile(file, "r")
            handle = raf
            return raf.channel
        }

        override fun close() {
            runCatching { handle?.close() }
        }
    }
}