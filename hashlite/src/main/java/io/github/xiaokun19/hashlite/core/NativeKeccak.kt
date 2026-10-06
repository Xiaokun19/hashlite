package io.github.xiaokun19.hashlite.core

import java.nio.ByteBuffer

/**
 * native Keccak（vendored OpenSSL 汇编）的 JNI 门面。
 *
 * 两道安全门：
 * 1. [loaded]：`.so` 没打进 APK / 非 arm64 设备 → 加载失败 → 一律回退 BC；
 * 2. [usable]：**向量自检通过**才算可用（惰性、只做一次）。自检不过就回退——
 *    宁可慢，也绝不产出错哈希。首次通过时会顺手用 4 MiB 实测选变体
 *
 * JVM 单测环境里 `loadLibrary` 直接抛异常 → usable=false → 走 BC，测试不受影响。
 */
object NativeKeccak {

    const val RATE_SHA3_256 = 136
    const val RATE_SHA3_512 = 72
    const val RATE_SHAKE128 = 168
    const val RATE_SHAKE256 = 136

    const val DOMAIN_SHA3 = 0x06
    const val DOMAIN_SHAKE = 0x1F

    val loaded: Boolean = try {
        System.loadLibrary("keccak")
        true
    } catch (t: Throwable) {
        false
    }

    /** 可用 = 库在 且 向量自检过；首次判定时顺带实测选变体。 */
    val usable: Boolean by lazy {
        if (!loaded) {
            false
        } else {
            val passes = runCatching { selfTest() == 0 }.getOrDefault(false)
            if (passes) {
                // 4 MiB × 两种变体 ≈ 25 ms，一次性
                runCatching { chooseByBench(4) }
            }
            passes
        }
    }

    @JvmStatic
    external fun haveSha3Ext(): Boolean

    @JvmStatic
    external fun variantName(): String

    @JvmStatic
    external fun setVariant(cext: Boolean)

    @JvmStatic
    external fun chooseByBench(mib: Int)

    /** 返回不通过的向量个数（0 = 全过）。 */
    @JvmStatic
    external fun selfTest(): Int

    /** 返回 [plain MB/s, cext MB/s]（SHA3-256）。 */
    @JvmStatic
    external fun bench(mib: Int): DoubleArray

    @JvmStatic
    external fun create(rate: Int): Long

    @JvmStatic
    external fun destroy(handle: Long)

    @JvmStatic
    external fun updateArray(handle: Long, data: ByteArray, offset: Int, length: Int)

    @JvmStatic
    external fun updateDirect(handle: Long, buffer: ByteBuffer, position: Int, length: Int)

    @JvmStatic
    external fun finish(handle: Long, outLen: Int, domain: Int): ByteArray
}

/**
 * 用 native Keccak 实现的 [BlockDigest]。
 *
 * 句柄在 [finish] 后释放；单次哈希被中途抛弃时最多泄漏 sizeof(kc_ctx)（≈200 字节），
 * 批量/取消场景下每个未完成哈希泄漏这么多，可忽略（已在 cpp 注释与文档里注明）。
 */
class NativeBlockDigest(private val rate: Int, private val outBytes: Int) : BlockDigest {

    private var handle: Long = NativeKeccak.create(rate)

    override fun update(buffer: ByteBuffer) {
        val position = buffer.position()
        val length = buffer.remaining()
        if (length <= 0) return
        if (buffer.isDirect) {
            NativeKeccak.updateDirect(handle, buffer, position, length)
            buffer.position(buffer.limit())
        } else {
            val copy = ByteArray(length)
            buffer.get(copy)
            NativeKeccak.updateArray(handle, copy, 0, length)
        }
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (length > 0) NativeKeccak.updateArray(handle, bytes, offset, length)
    }

    override fun finish(): ByteArray {
        val out = NativeKeccak.finish(handle, outBytes, NativeKeccak.DOMAIN_SHA3)
        NativeKeccak.destroy(handle)
        handle = 0L
        return out
    }
}