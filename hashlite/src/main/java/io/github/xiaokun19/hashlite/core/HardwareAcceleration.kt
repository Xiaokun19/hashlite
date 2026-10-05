package io.github.xiaokun19.hashlite.core

import android.content.Context
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * 判断"某个算法在这台机器上是否真的吃到硬件加速"，并据此决定要不要亮硬件徽标。
 *
 * 两层判断，缺一不可：
 *
 * 1. **CPU 有没有这条指令**：读 `/proc/self/auxv` 的 `AT_HWCAP` 位（比解析 cpuinfo 文本精确、
 *    而且不受型号名字影响），读不到再回退到 `/proc/cpuinfo` 的 Features 文本。
 *
 * 2. **平台库有没有真的用上**：把平台实现和纯软件实现（BouncyCastle，CRC32 用自写的表驱动版）
 *    各跑一遍同样大小的数据，比值 ≥ [RATIO_THRESHOLD] 才算"真的快"。
 *    这一层是必要的：**CPU 有指令 ≠ 库用了指令**。本机就是活例子——
 *    `/proc/cpuinfo` 里 `sm3`、`sha3` 位都在，但 Android 平台根本没有 SM3/SHA-3 的实现，
 *    所以这两个算法只能标"软件"。
 *
 * 探测只在首次运行时做一次（约 0.1 秒，全部在内存里跑），结果缓存进 SharedPreferences。
 * 还没探测完时，UI 先用"CPU 有位"的乐观估计，探测完自动修正。
 */
object HardwareAcceleration {

    private const val AT_HWCAP = 16L
    private const val AT_HWCAP2 = 26L
    private const val AT_NULL = 0L
    private const val PREFS = "hashlite_accel"
    private const val RATIO_THRESHOLD = 2.0

    /**
     * CRC32 的门槛更高：它的基线是"Java 查表实现"，而平台实现是 native(zlib)，
     * 两者差距里混着"语言/运行时"的差异，所以只有明显拉开才算数。
     */
    private const val CRC32_RATIO_THRESHOLD = 5.0

    private val PROBE_ALGORITHMS = listOf(LiteAlgorithm.SHA1, LiteAlgorithm.SHA256, LiteAlgorithm.SHA512)

    data class CpuFlags(val hwcap: Long, val hwcap2: Long, val source: String) {
        fun has(bit: Int?): Boolean {
            if (bit == null) return false
            return (hwcap shr bit) and 1L == 1L
        }

        val note: String get() = "HWCAP=0x%016x（%s）".format(hwcap, source)
    }

    /** 平台实现 / 软件实现 的吞吐与比值。 */
    data class Probe(
        val bytesPerSec: Map<LiteAlgorithm, Double>,
        val baseline: Map<LiteAlgorithm, Double>,
        val ratios: Map<LiteAlgorithm, Double>,
        val crc32Ratio: Double,
    ) {
        fun summary(): String {
            val parts = ArrayList<String>()
            for (algorithm in PROBE_ALGORITHMS) {
                val platform = bytesPerSec[algorithm] ?: continue
                val software = baseline[algorithm] ?: continue
                val ratio = ratios[algorithm] ?: 0.0
                parts += "%s %.2f vs %.2f GB/s (%.1f×)".format(
                    algorithm.label,
                    platform / 1e9,
                    software / 1e9,
                    ratio,
                )
            }
            parts += "CRC32 %.2f×".format(crc32Ratio)
            return parts.joinToString(" · ")
        }
    }

    // ---------- 1. CPU 能力 ----------

    fun readCpuFlags(): CpuFlags {
        val fromAuxv = runCatching { readAuxv() }.getOrNull()
        if (fromAuxv != null) return fromAuxv
        return fromCpuInfo()
    }

    private fun readAuxv(): CpuFlags? {
        val file = File("/proc/self/auxv")
        if (!file.canRead()) return null
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var hwcap = 0L
        var hwcap2 = 0L
        while (buffer.remaining() >= 16) {
            val type = buffer.long
            val value = buffer.long
            when (type) {
                AT_HWCAP -> hwcap = value
                AT_HWCAP2 -> hwcap2 = value
                AT_NULL -> break
            }
        }
        return if (hwcap == 0L) null else CpuFlags(hwcap, hwcap2, "/proc/self/auxv")
    }

    /** 回退方案：解析 /proc/cpuinfo 的 Features 文本（位序与 HWCAP 一致，但只能拿到名字）。 */
    private fun fromCpuInfo(): CpuFlags {
        val features = runCatching {
            val file = File("/proc/cpuinfo")
            if (!file.canRead()) return@runCatching emptyList()
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            }
        }.getOrDefault(emptyList())

        var bits = 0L
        fun set(bit: Int, name: String) {
            if (features.contains(name)) bits = bits or (1L shl bit)
        }
        set(Hwcap.SHA1, "sha1")
        set(Hwcap.SHA256, "sha2")
        set(Hwcap.CRC32, "crc32")
        set(Hwcap.SHA3, "sha3")
        set(Hwcap.SM3, "sm3")
        set(Hwcap.SM4, "sm4")
        set(Hwcap.SHA512, "sha512")
        return CpuFlags(bits, 0L, "/proc/cpuinfo")
    }

    // ---------- 2. 平台是否真的用上了（微基准） ----------

    /**
     * 微基准。默认每轮只跑 1MiB、2 轮：
     * 比值余量很大（十几到几十倍），不需要大数据量；2 轮里第 1 轮相当于 JIT 预热，
     * 结果取最优，所以既准又快（冷启动一次性 ≈0.6 秒，之后走缓存完全不测）。
     */
    fun probe(blockMiB: Int = 1, rounds: Int = 2): Probe {
        val block = makeBuffer(blockMiB)
        val platform = LinkedHashMap<LiteAlgorithm, Double>()
        val software = LinkedHashMap<LiteAlgorithm, Double>()
        val ratios = LinkedHashMap<LiteAlgorithm, Double>()

        for (algorithm in PROBE_ALGORITHMS) {
            val platformBps = bestOfPlatform(rounds, block, algorithm)
            val softwareBps = bestOfSoftware(rounds, block, algorithm)
            platform[algorithm] = platformBps
            software[algorithm] = softwareBps
            ratios[algorithm] = if (softwareBps > 0.0) platformBps / softwareBps else 0.0
        }

        val crcPlatform = bestOfCrc(rounds, block)
        val crcSoftware = bestOfSoftwareCrc(rounds, block)
        val crcRatio = if (crcSoftware > 0.0) crcPlatform / crcSoftware else 0.0

        return Probe(platform, software, ratios, crcRatio)
    }

    private fun makeBuffer(mib: Int): ByteBuffer {
        val block = ByteBuffer.allocateDirect(mib * 1024 * 1024)
        val random = Random(20261005L)
        val chunk = ByteArray(64 * 1024)
        repeat(block.capacity() / chunk.size) {
            random.nextBytes(chunk)
            block.put(chunk)
        }
        block.flip()
        return block
    }

    private fun throughput(block: ByteBuffer, rounds: Int, body: () -> Unit): Double {
        var best = 0.0
        repeat(rounds) {
            val start = System.nanoTime()
            body()
            val elapsed = (System.nanoTime() - start) / 1e9
            if (elapsed > 0.0) {
                val bps = block.capacity() / elapsed
                if (bps > best) best = bps
            }
        }
        return best
    }

    /** 平台实现（Conscrypt/BoringSSL 或 java.util.zip）的吞吐。 */
    private fun bestOfPlatform(rounds: Int, block: ByteBuffer, algorithm: LiteAlgorithm): Double =
        throughput(block, rounds) {
            val digest = algorithm.newDigest()
            block.rewind()
            digest.update(block)
            digest.finish()
        }

    /** 同类算法的纯软件基线（BouncyCastle）的吞吐。 */
    private fun bestOfSoftware(rounds: Int, block: ByteBuffer, algorithm: LiteAlgorithm): Double =
        throughput(block, rounds) {
            val digest = when (algorithm) {
                LiteAlgorithm.SHA1 -> BcBlockDigest(SHA1Digest(), 20)
                LiteAlgorithm.SHA512 -> BcBlockDigest(SHA512Digest(), 64)
                else -> BcBlockDigest(SHA256Digest(), 32)
            }
            block.rewind()
            digest.update(block)
            digest.finish()
        }

    private fun bestOfCrc(rounds: Int, block: ByteBuffer): Double =
        throughput(block, rounds) {
            val digest = Crc32Digest()
            block.rewind()
            digest.update(block)
            digest.finish()
        }

    private fun bestOfSoftwareCrc(rounds: Int, block: ByteBuffer): Double =
        throughput(block, rounds) {
            val crc = SoftwareCrc32()
            block.rewind()
            crc.update(block)
            crc.finish()
        }

    // ---------- 3. 结论 + 缓存 ----------

    /**
     * 把探针结果摊平成"每个算法一个比值"的表（SHA-224/384 复用同族结果，CRC32 单列）。
     * 缓存与 UI 都用这张表。
     */
    fun ratioMap(probe: Probe): Map<LiteAlgorithm, Double> {
        val map = HashMap<LiteAlgorithm, Double>()
        for ((algorithm, ratio) in probe.ratios) map[algorithm] = ratio
        LiteAlgorithm.SHA224.probeAs?.let { map[LiteAlgorithm.SHA224] = probe.ratios[it] ?: 0.0 }
        LiteAlgorithm.SHA384.probeAs?.let { map[LiteAlgorithm.SHA384] = probe.ratios[it] ?: 0.0 }
        map[LiteAlgorithm.CRC32] = probe.crc32Ratio
        return map
    }

    fun isAccelerated(
        algorithm: LiteAlgorithm,
        flags: CpuFlags,
        ratios: Map<LiteAlgorithm, Double>,
    ): Boolean {
        // 纯软件实现（BC 的 SHA-3 / SM3）永远标不了硬件
        if (algorithm.kind == LiteAlgorithm.Kind.BC) return false

        val ratio = ratios[algorithm]

        // CRC32 没有对应"摘要库"，只能靠实测比（基线是 Java 查表实现，门槛单独抬高）
        if (algorithm.kind == LiteAlgorithm.Kind.CRC32) {
            return ratio != null && ratio >= CRC32_RATIO_THRESHOLD
        }

        // JCA 算法：CPU 必须有这条指令
        if (!flags.has(algorithm.hwcapBit)) return false
        // 还没实测过 → 先用 CPU 能力的乐观估计（探测完会修正）
        return ratio == null || ratio >= RATIO_THRESHOLD
    }

    fun save(context: Context, ratios: Map<LiteAlgorithm, Double>, note: String) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for ((algorithm, ratio) in ratios) editor.putFloat("r_" + algorithm.name, ratio.toFloat())
        editor.putString("probeNote", note)
        editor.apply()
    }

    /** 只读缓存（不测），用于启动时快速决定徽标。 */
    fun cachedRatios(context: Context): Map<LiteAlgorithm, Double> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("probeNote")) return emptyMap()
        val map = HashMap<LiteAlgorithm, Double>()
        for (algorithm in LiteAlgorithm.entries) {
            val key = "r_" + algorithm.name
            if (prefs.contains(key)) map[algorithm] = prefs.getFloat(key, 0f).toDouble()
        }
        return map
    }

    fun cachedNote(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("probeNote", null)

    /** 界面顶部那一行小字：本机实际能吃到硬件加速的是哪几个。 */
    fun headerSummary(flags: CpuFlags, ratios: Map<LiteAlgorithm, Double>): String {
        val hw = LiteAlgorithm.COMMON
            .filter { it.kind == LiteAlgorithm.Kind.JCA && isAccelerated(it, flags, ratios) }
            .joinToString("/") { it.label }
        return if (hw.isEmpty()) {
            "未检测到可用的硬件哈希指令"
        } else {
            "硬件指令已启用：$hw"
        }
    }

    /** 给报告/帮助用的文字：解释某算法为什么能/不能亮徽标。 */
    fun explain(algorithm: LiteAlgorithm, flags: CpuFlags, ratios: Map<LiteAlgorithm, Double>): String {
        val bitName = when (algorithm.hwcapBit) {
            Hwcap.SHA1 -> "sha1"
            Hwcap.SHA256 -> "sha2"
            Hwcap.CRC32 -> "crc32"
            Hwcap.SHA3 -> "sha3"
            Hwcap.SM3 -> "sm3"
            Hwcap.SHA512 -> "sha512"
            else -> null
        }
        val cpuHas = flags.has(algorithm.hwcapBit)
        val ratio = ratios[algorithm]
        return when {
            algorithm == LiteAlgorithm.MD5 -> "无对应指令"
            algorithm.kind == LiteAlgorithm.Kind.CRC32 -> when {
                ratio == null -> "平台原生实现（java.util.zip/zlib），未实测"
                ratio >= CRC32_RATIO_THRESHOLD -> "平台原生实现（zlib）实测比 Java 查表快 %.1f×".format(ratio)
                else -> "平台实现只比 Java 查表快 %.1f×，未用 crc32 指令".format(ratio)
            }

            algorithm.kind == LiteAlgorithm.Kind.BC ->
                "纯软件实现；CPU ${if (cpuHas) "有 $bitName 指令但平台无实现" else "也没有 $bitName 指令"}"

            !cpuHas -> "CPU 无 $bitName 指令"
            ratio == null -> "CPU 有 $bitName 指令，未实测"
            ratio >= RATIO_THRESHOLD -> "硬件指令（实测比软件快 %.1f×）".format(ratio)
            else -> "CPU 有 $bitName 指令，但平台实现只比软件快 %.1f×，判定未用指令".format(ratio)
        }
    }
}
