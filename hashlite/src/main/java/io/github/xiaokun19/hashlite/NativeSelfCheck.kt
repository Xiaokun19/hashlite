package io.github.xiaokun19.hashlite

import io.github.xiaokun19.hashlite.core.BcBlockDigest
import io.github.xiaokun19.hashlite.core.HashParse
import io.github.xiaokun19.hashlite.core.LiteAlgorithm
import io.github.xiaokun19.hashlite.core.NativeBlockDigest
import io.github.xiaokun19.hashlite.core.NativeKeccak
import org.bouncycastle.crypto.digests.SHA3Digest
import java.util.Locale

/**
 * `-e nativecheck` 的无头自检：回答三件事——**能不能用、快多少、算得对不对**。
 *
 * 报告写到 `getExternalFilesDir()/nativecheck.txt`（shell 可读）。
 * 速度对比用的缓冲与 App 内"内存基准"同口径（常驻内存、3 轮取最优）。
 */
object NativeSelfCheck {

    fun run(sizeMiB: Int = 64): String {
        val sb = StringBuilder()
        sb.appendLine("=== native Keccak 自检 ===")

        if (!NativeKeccak.loaded) {
            sb.appendLine("native 库未加载（APK 里没有 libkeccak.so，或不是 arm64-v8a）")
            sb.appendLine("→ App 会自动回退 BouncyCastle（功能不受影响，只是慢）")
            return sb.toString()
        }
        sb.appendLine("库加载: OK")
        sb.appendLine("HWCAP sha3: ${NativeKeccak.haveSha3Ext()}")
        val fails = NativeKeccak.selfTest()
        sb.appendLine("向量自检: ${if (fails == 0) "OK" else "FAIL（$fails 个不过）"}")
        sb.appendLine("当前变体: ${NativeKeccak.variantName()}")
        sb.appendLine("App 里 SHA3-256 实际实现: ${LiteAlgorithm.SHA3_256.implementation}")
        sb.appendLine()

        sb.appendLine("--- native 测速（SHA3-256，${sizeMiB} MiB 常驻内存，3 轮取最优）---")
        val bench = NativeKeccak.bench(sizeMiB)
        sb.appendLine(
            String.format(
                Locale.US,
                "plain %.1f MB/s | cext(EOR3) %.1f MB/s | cext/plain = %.2fx",
                bench[0], bench[1], if (bench[0] > 0) bench[1] / bench[0] else 0.0,
            ),
        )
        sb.appendLine()

        // 同一块数据：native vs BouncyCastle 的正确性对拍 + 速度对拍
        val n = sizeMiB * 1024 * 1024
        val data = ByteArray(n)
        var seed = 12345
        for (i in 0 until n) {
            seed = seed * 1103515245 + 12345
            data[i] = (seed ushr 16).toByte()
        }

        sb.appendLine("--- 正确性对拍（同一块 ${sizeMiB} MiB 数据：native vs BouncyCastle）---")
        for (algorithm in listOf(LiteAlgorithm.SHA3_256, LiteAlgorithm.SHA3_512)) {
            val bc = BcBlockDigest(
                if (algorithm == LiteAlgorithm.SHA3_256) SHA3Digest(256) else SHA3Digest(512),
                if (algorithm == LiteAlgorithm.SHA3_256) 32 else 64,
            )
            val native = NativeBlockDigest(
                if (algorithm == LiteAlgorithm.SHA3_256) NativeKeccak.RATE_SHA3_256 else NativeKeccak.RATE_SHA3_512,
                if (algorithm == LiteAlgorithm.SHA3_256) 32 else 64,
            )
            bc.update(data)
            native.update(data)
            val bcHex = HashParse.toHex(bc.finish())
            val nativeHex = HashParse.toHex(native.finish())
            sb.appendLine("${algorithm.label}: ${if (bcHex == nativeHex) "一致 OK" else "!! 不一致"}  ${nativeHex.take(24)}…")
        }
        sb.appendLine()

        sb.appendLine("--- 速度对比（同一块 ${sizeMiB} MiB 数据，App 口径）---")
        // 交替测量：否则先测的那个把机器烤热，后测的吃亏
        // 顺带记录**每轮跑在哪个 CPU**（/proc/self/stat 第 39 字段）与距开跑的时间：
        // 若速度低的轮次集中在同一个核上，就能坐实"被调度到小核/被降档"，而不是热降频。
        var nativeBest = 0.0
        var bcBest = 0.0
        val t0 = System.nanoTime()
        fun since(): String = String.format(Locale.US, "%5.2fs", (System.nanoTime() - t0) / 1e9)

        sb.appendLine("  [bench 阶段结束 @${since()}]")
        repeat(3) { i ->
            val nNanos = hashOnce(data, native = true)
            val nCpu = currentCpu()
            val nSpeed = mbps(nNanos, n)
            nativeBest = maxOf(nativeBest, nSpeed)

            val bNanos = hashOnce(data, native = false)
            val bCpu = currentCpu()
            val bSpeed = mbps(bNanos, n)
            bcBest = maxOf(bcBest, bSpeed)

            sb.appendLine(
                String.format(
                    Locale.US,
                    "  轮%d  native %7.1f MB/s (cpu=%d)   BC %7.1f MB/s (cpu=%d)   @%s",
                    i + 1, nSpeed, nCpu, bSpeed, bCpu, since(),
                ),
            )
        }

        // 反向再测一轮：如果"先测的总是更快"，说明是瞬时加速窗口在起作用
        val bFirst = mbps(hashOnce(data, native = false), n)
        val bCpu2 = currentCpu()
        val nSecond = mbps(hashOnce(data, native = true), n)
        val nCpu2 = currentCpu()
        sb.appendLine(
            String.format(
                Locale.US,
                "  反序  BC %7.1f MB/s (cpu=%d)   native %7.1f MB/s (cpu=%d)   @%s",
                bFirst, bCpu2, nSecond, nCpu2, since(),
            ),
        )
        sb.appendLine(
            String.format(
                Locale.US,
                "SHA3-256: native %.1f MB/s | BouncyCastle %.1f MB/s | 快 %.2fx",
                nativeBest, bcBest, if (bcBest > 0) nativeBest / bcBest else 0.0,
            ),
        )
        sb.appendLine()
        sb.appendLine("注：native 路径只在向量自检通过时才启用；自检不过会回退 BC（宁可慢，不产出错哈希）。")
        sb.appendLine("变体校正：首次遇到 ≥8 MiB 大块时实测选一次 —— 本次校正后 = ${NativeKeccak.variantName()}")
        return sb.toString()
    }

    private fun hashOnce(data: ByteArray, native: Boolean): Long {
        val digest = if (native) {
            NativeBlockDigest(NativeKeccak.RATE_SHA3_256, 32)
        } else {
            BcBlockDigest(SHA3Digest(256), 32)
        }
        val t0 = System.nanoTime()
        digest.update(data)
        digest.finish()
        return System.nanoTime() - t0
    }

    /**
     * 持续负载实验：在 [seconds] 秒内反复哈希同一块数据，逐轮报速度。
     *
     * 用途：验证**前台服务能不能扛住系统的压频**——没有前台服务时，App 不交互 1~3 秒后
     * 大核会被降档，SHA3 从 ~1000 MB/s 掉到 ~245 MB/s（实测数据）。
     * 打印前 8 轮 + 每 10 轮 +最后的汇总，方便直接看出"什么时候开始掉"。
     */
    fun sustain(seconds: Int, sizeMiB: Int = 64, onProgress: ((percent: Int, text: String) -> Unit)? = null): String {
        if (!NativeKeccak.loaded) return "native 未加载，跳过持续负载"
        val n = sizeMiB * 1024 * 1024
        val data = ByteArray(n)
        var seed = 12345
        for (i in 0 until n) {
            seed = seed * 1103515245 + 12345
            data[i] = (seed ushr 16).toByte()
        }

        val sb = StringBuilder()
        sb.appendLine("变体=${NativeKeccak.variantName()} · 前台服务=${if (RunKeeper.serviceStarted) "开" else "关"}")
        val start = System.nanoTime()
        var round = 0
        var best = 0.0
        var worst = Double.MAX_VALUE
        var sum = 0.0
        while (System.nanoTime() - start < seconds * 1_000_000_000L && !stopRequested) {
            round++
            val speed = mbps(hashOnce(data, native = true), n)
            best = maxOf(best, speed)
            worst = minOf(worst, speed)
            sum += speed
            if (round <= 8 || round % 10 == 0) {
                sb.appendLine(String.format(Locale.US, "  第%3d轮 %8.1f MB/s", round, speed))
            }
            val elapsedSec = ((System.nanoTime() - start) / 1_000_000_000L).toInt()
            onProgress?.invoke(
                (elapsedSec * 100 / seconds.coerceAtLeast(1)).coerceIn(0, 100),
                String.format(Locale.US, "第%d轮 · %.0f MB/s", round, speed),
            )
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        if (round > 0) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "  共 %d 轮 / %.1fs · 最好 %.1f · 最差 %.1f · 平均 %.1f MB/s",
                    round, elapsed, best, worst, sum / round,
                ),
            )
        }
        if (stopRequested) sb.appendLine("  （被通知栏取消）")
        return sb.toString()
    }

    /** 持续负载的停止标志（通知栏"取消"会置位）。 */
    @Volatile
    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    private fun mbps(nanos: Long, bytes: Int): Double =
        if (nanos <= 0L) 0.0 else bytes.toDouble() / (nanos / 1_000_000_000.0) / (1024.0 * 1024.0)

    /**
     * 当前线程最近一次跑在哪个 CPU 上：`/proc/self/stat` 的第 39 字段（processor）。
     * 第 2 字段 comm 可能含空格/括号，所以先从最后一个 ") " 之后开始数（那之后就是第 3 字段起）。
     */
    private fun currentCpu(): Int = try {
        val stat = java.io.File("/proc/self/stat").readText()
        stat.substringAfterLast(") ").split(' ').getOrNull(36)?.toIntOrNull() ?: -1
    } catch (t: Throwable) {
        -1
    }
}