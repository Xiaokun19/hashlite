package io.github.xiaokun19.hashlite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HashParseTest {

    private val sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `extract tolerates common clipboard shapes`() {
        assertEquals(sha256, HashParse.extractHex(sha256))
        assertEquals(sha256, HashParse.extractHex("  $sha256 "))
        assertEquals(sha256, HashParse.extractHex(sha256.uppercase()))
        assertEquals(sha256, HashParse.extractHex("0x$sha256"))
        assertEquals(sha256, HashParse.extractHex("SHA256 (file.bin) = $sha256"))
        assertEquals(sha256, HashParse.extractHex("$sha256  file.bin"))
    }

    @Test
    fun `extract rejects garbage`() {
        assertNull(HashParse.extractHex(""))
        assertNull(HashParse.extractHex("no hash here"))
        assertNull(HashParse.extractHex("abcdef"))
        assertNull(HashParse.extractHex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b85"))
    }

    @Test
    fun `candidates come from hex length`() {
        assertEquals(listOf(LiteAlgorithm.MD5), HashParse.candidates("d41d8cd98f00b204e9800998ecf8427e"))
        assertEquals(listOf(LiteAlgorithm.SHA1), HashParse.candidates("a".repeat(40)))
        // 64 位现在有三个候选：SHA-256 / SHA3-256 / SM3
        val sixtyFour = HashParse.candidates(sha256)
        assertTrue(sixtyFour.contains(LiteAlgorithm.SHA256))
        assertTrue(sixtyFour.contains(LiteAlgorithm.SHA3_256))
        assertTrue(sixtyFour.contains(LiteAlgorithm.SM3))
        // 56 位只对应 SHA-224（SHA-512/224 不在纯净版里）
        assertEquals(listOf(LiteAlgorithm.SHA224), HashParse.candidates("c".repeat(56)))
        // 8 位只对应 CRC32，且默认不参与识别
        assertEquals(listOf(LiteAlgorithm.CRC32), HashParse.candidates("352441c2"))
        // 128 位现在有两个候选：SHA-512 / SHA3-512
        val oneTwentyEight = HashParse.candidates("b".repeat(128))
        assertTrue(oneTwentyEight.contains(LiteAlgorithm.SHA512))
        assertTrue(oneTwentyEight.contains(LiteAlgorithm.SHA3_512))
    }

    @Test
    fun `short crc32 values are only recognized when allowed`() {
        assertNull(HashParse.extractHex("352441c2"))
        assertEquals("352441c2", HashParse.extractHex("352441c2", allowShort = true))
        // 贴一整行时也能认出来
        assertEquals("352441c2", HashParse.extractHex("CRC32 (a.bin) = 352441C2", allowShort = true))
        // 有更长的合法哈希时优先认长的（sha256 会走 ≥32 位那条规则）
        assertEquals(sha256, HashParse.extractHex(sha256, allowShort = true))
        // 12 位既不是 CRC32 也不在支持列表里 → 不认
        assertNull(HashParse.extractHex("1234352441c2", allowShort = true))
    }

    @Test
    fun `matches is case insensitive and tolerant of decoration`() {
        assertTrue(HashParse.matches("SHA256 (f) = ${sha256.uppercase()}", sha256))
        assertTrue(HashParse.matches(sha256.uppercase(), sha256))
        assertTrue(HashParse.matches(sha256, sha256.uppercase()))
        assertFalse(HashParse.matches("d41d8cd98f00b204e9800998ecf8427e", sha256))
    }

    @Test
    fun `display only changes presentation, never the stored value`() {
        assertEquals(sha256.uppercase(), HashParse.display(sha256, uppercase = true))
        assertEquals(sha256, HashParse.display(sha256.uppercase(), uppercase = false))
        // 大小写切换不影响"识别为哪个算法"
        assertEquals(
            HashParse.candidates(sha256),
            HashParse.candidates(HashParse.display(sha256, uppercase = true)),
        )
    }

    @Test
    fun `formatting is stable`() {
        assertEquals("512 B", HashParse.formatBytes(512))
        assertEquals("1.00 KB", HashParse.formatBytes(1024))
        assertEquals("4.00 MB", HashParse.formatBytes(4L * 1024 * 1024))
        assertEquals("1.00 GB", HashParse.formatBytes(1024L * 1024 * 1024))
        assertTrue(HashParse.formatSpeed(1_500.0 * 1024 * 1024).endsWith("/s"))
        assertTrue(HashParse.formatDuration(500_000).contains("µs"))
        assertTrue(HashParse.formatDuration(2_500_000_000).contains("s"))
        assertNull(HashParse.etaParts(-1.0))
        assertNull(HashParse.etaParts(Double.NaN))
        assertEquals(0L to 12L, HashParse.etaParts(12.0))
        assertEquals(1L to 30L, HashParse.etaParts(90.0))
        assertEquals(2L to 0L, HashParse.etaParts(119.6))
    }
}