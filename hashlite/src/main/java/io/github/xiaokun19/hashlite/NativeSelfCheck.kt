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
        // 交替测量：否则先测的那个把机器烤热，后测的吃亏（上一轮实测里 BC 掉了一半就是这原因）
        var nativeBest = 0.0
        var bcBest = 0.0
        repeat(3) {
            nativeBest = maxOf(nativeBest, mbps(hashOnce(data, native = true), n))
            bcBest = maxOf(bcBest, mbps(hashOnce(data, native = false), n))
        }
        sb.appendLine(
            String.format(
                Locale.US,
                "SHA3-256: native %.1f MB/s | BouncyCastle %.1f MB/s | 快 %.2fx",
                nativeBest, bcBest, if (bcBest > 0) nativeBest / bcBest else 0.0,
            ),
        )
        sb.appendLine()
        sb.appendLine("注：native 路径只在向量自检通过时才启用；自检不过会回退 BC（宁可慢，不产出错哈希）。")
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

    private fun mbps(nanos: Long, bytes: Int): Double =
        if (nanos <= 0L) 0.0 else bytes.toDouble() / (nanos / 1_000_000_000.0) / (1024.0 * 1024.0)
}