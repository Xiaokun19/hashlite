package io.github.xiaokun19.hashlite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加速判定逻辑的测试（不依赖真机）：
 * 用合成的 CpuFlags 与比值表把各种组合都过一遍。
 */
class HardwareAccelerationTest {

    private fun flags(vararg bits: Int): HardwareAcceleration.CpuFlags {
        var value = 0L
        for (bit in bits) value = value or (1L shl bit)
        return HardwareAcceleration.CpuFlags(value, 0L, "test")
    }

    private fun ratios(vararg pairs: Pair<LiteAlgorithm, Double>): Map<LiteAlgorithm, Double> =
        pairs.toMap()

    @Test
    fun `jca algorithm needs both cpu bit and measured ratio`() {
        val cpuWithSha2 = flags(Hwcap.SHA1, Hwcap.SHA256, Hwcap.SHA512)

        // 1) CPU 有位、没测过 → 乐观显示
        assertTrue(HardwareAcceleration.isAccelerated(LiteAlgorithm.SHA256, cpuWithSha2, emptyMap()))
        // 2) CPU 有位、实测确实快 → 显示
        assertTrue(
            HardwareAcceleration.isAccelerated(
                LiteAlgorithm.SHA256, cpuWithSha2, ratios(LiteAlgorithm.SHA256 to 6.0),
            ),
        )
        // 3) CPU 有位、但平台实现并不比软件快 → 不显示（"有指令≠用了指令"）
        assertFalse(
            HardwareAcceleration.isAccelerated(
                LiteAlgorithm.SHA256, cpuWithSha2, ratios(LiteAlgorithm.SHA256 to 1.1),
            ),
        )
        // 4) CPU 没这位指令 → 不显示
        assertFalse(
            HardwareAcceleration.isAccelerated(
                LiteAlgorithm.SHA256, flags(Hwcap.SHA1), ratios(LiteAlgorithm.SHA256 to 6.0),
            ),
        )
    }

    @Test
    fun `pure software implementations never get a badge`() {
        val everything = flags(Hwcap.SHA1, Hwcap.SHA256, Hwcap.SHA3, Hwcap.SM3, Hwcap.SHA512)
        val fast = ratios(
            LiteAlgorithm.SHA3_256 to 9.0,
            LiteAlgorithm.SHA3_512 to 9.0,
            LiteAlgorithm.SM3 to 9.0,
        )
        assertFalse(HardwareAcceleration.isAccelerated(LiteAlgorithm.SHA3_256, everything, fast))
        assertFalse(HardwareAcceleration.isAccelerated(LiteAlgorithm.SHA3_512, everything, fast))
        // 本机的关键例子：cpuinfo 里 sm3 位是有的，但平台没有 SM3 实现 → 徽标必须灭
        assertFalse(HardwareAcceleration.isAccelerated(LiteAlgorithm.SM3, everything, fast))
        assertTrue(everything.has(Hwcap.SM3))
    }

    @Test
    fun `crc32 depends purely on measured ratio with a higher bar`() {
        assertFalse(HardwareAcceleration.isAccelerated(LiteAlgorithm.CRC32, flags(Hwcap.CRC32), emptyMap()))
        // 基线是 Java 查表实现，门槛比摘要类算法高（5×）：3.2× 不足以判定用了 crc32 指令
        assertFalse(
            HardwareAcceleration.isAccelerated(
                LiteAlgorithm.CRC32, flags(Hwcap.CRC32), ratios(LiteAlgorithm.CRC32 to 3.2),
            ),
        )
        assertTrue(
            HardwareAcceleration.isAccelerated(
                LiteAlgorithm.CRC32, flags(Hwcap.CRC32), ratios(LiteAlgorithm.CRC32 to 8.0),
            ),
        )
    }

    @Test
    fun `ratio map fills in family reuse and crc32`() {
        val probe = HardwareAcceleration.Probe(
            bytesPerSec = mapOf(
                LiteAlgorithm.SHA1 to 2.6e9,
                LiteAlgorithm.SHA256 to 2.7e9,
                LiteAlgorithm.SHA512 to 1.7e9,
            ),
            baseline = mapOf(
                LiteAlgorithm.SHA1 to 0.4e9,
                LiteAlgorithm.SHA256 to 0.5e9,
                LiteAlgorithm.SHA512 to 0.4e9,
            ),
            ratios = mapOf(
                LiteAlgorithm.SHA1 to 6.5,
                LiteAlgorithm.SHA256 to 5.4,
                LiteAlgorithm.SHA512 to 4.2,
            ),
            crc32Ratio = 1.1,
        )
        val map = HardwareAcceleration.ratioMap(probe)
        assertEquals(5.4, map[LiteAlgorithm.SHA256] ?: 0.0, 1e-9)
        assertEquals(5.4, map[LiteAlgorithm.SHA224] ?: 0.0, 1e-9) // 复用同族
        assertEquals(4.2, map[LiteAlgorithm.SHA384] ?: 0.0, 1e-9) // 复用同族
        assertEquals(1.1, map[LiteAlgorithm.CRC32] ?: 0.0, 1e-9)
        assertNull(map[LiteAlgorithm.MD5])
    }

    @Test
    fun `explain distinguishes the platform-missing case`() {
        val flags = flags(Hwcap.SHA1, Hwcap.SHA256, Hwcap.SHA512, Hwcap.SM3)
        val text = HardwareAcceleration.explain(LiteAlgorithm.SM3, flags, emptyMap())
        assertTrue(text, text.contains("平台无实现"))
        val hw = HardwareAcceleration.explain(
            LiteAlgorithm.SHA256, flags, ratios(LiteAlgorithm.SHA256 to 5.0),
        )
        assertTrue(hw, hw.contains("硬件指令"))
    }

    @Test
    fun `bit positions match kernel ordering documented in cpuinfo`() {
        // /proc/cpuinfo 的 Features 顺序与 HWCAP 位序一致，这里把关键位号钉死
        assertEquals(5, Hwcap.SHA1)
        assertEquals(6, Hwcap.SHA256)
        assertEquals(7, Hwcap.CRC32)
        assertEquals(17, Hwcap.SHA3)
        assertEquals(18, Hwcap.SM3)
        assertEquals(21, Hwcap.SHA512)
    }
}