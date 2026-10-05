package io.github.xiaokun19.hashlite.core

import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.digests.SM3Digest
import java.security.MessageDigest

/** 算法分组：常用的一直显示，"更多"折叠在后面。 */
enum class AlgorithmGroup { COMMON, MORE }

/**
 * 支持的算法。
 *
 * [hwcapBit] 是该算法"若要吃到硬件指令"所需的那一位 HWCAP（null = 没有对应指令）。
 * [platformImplemented] 才是能否带硬件徽标的**必要条件**：只有平台（Conscrypt/BoringSSL）
 * 真的有实现，才谈得上用指令；纯软件实现（BouncyCastle 里的 SHA-3/SM3）永远标不了。
 * 典型的"坑"：本机 /proc/cpuinfo 里既有 sm3 也有 sha3 位，但 Android 平台没有这两个算法的
 * 实现，所以徽标不能亮。
 */
enum class LiteAlgorithm(
    val label: String,
    val hexChars: Int,
    val group: AlgorithmGroup,
    val kind: Kind,
    val hwcapBit: Int?,
    val note: String? = null,
) {
    // ---- 常用 ----
    MD5("MD5", 32, AlgorithmGroup.COMMON, Kind.JCA, null),
    SHA1("SHA-1", 40, AlgorithmGroup.COMMON, Kind.JCA, Hwcap.SHA1),
    SHA224("SHA-224", 56, AlgorithmGroup.COMMON, Kind.JCA, Hwcap.SHA256),
    SHA256("SHA-256", 64, AlgorithmGroup.COMMON, Kind.JCA, Hwcap.SHA256),
    SHA384("SHA-384", 96, AlgorithmGroup.COMMON, Kind.JCA, Hwcap.SHA512),
    SHA512("SHA-512", 128, AlgorithmGroup.COMMON, Kind.JCA, Hwcap.SHA512),

    // ---- 更多（点"更多算法"才展开） ----
    SHA3_256("SHA3-256", 64, AlgorithmGroup.MORE, Kind.BC, Hwcap.SHA3, "软件"),
    SHA3_512("SHA3-512", 128, AlgorithmGroup.MORE, Kind.BC, Hwcap.SHA3, "软件"),
    SM3("SM3", 64, AlgorithmGroup.MORE, Kind.BC, Hwcap.SM3, "国密·软件"),
    CRC32("CRC32", 8, AlgorithmGroup.MORE, Kind.CRC32, Hwcap.CRC32, "校验和"),
    ;

    enum class Kind { JCA, BC, CRC32 }

    /** 平台（JCA）是否提供实现——只有它可能吃到硬件指令。 */
    val platformImplemented: Boolean get() = kind == Kind.JCA

    /**
     * 探针复用：SHA-224 / SHA-384 没必要单独测，直接用同族快接口的结果
     * （它们走的是同一套 SHA-2 指令）。
     */
    val probeAs: LiteAlgorithm?
        get() = when (this) {
            SHA224 -> SHA256
            SHA384 -> SHA512
            else -> null
        }

    fun newDigest(): BlockDigest = when (kind) {
        Kind.JCA -> JcaDigest(MessageDigest.getInstance(label))
        Kind.BC -> when (this) {
            SHA3_256 -> BcBlockDigest(SHA3Digest(256), 32)
            SHA3_512 -> BcBlockDigest(SHA3Digest(512), 64)
            SM3 -> BcBlockDigest(SM3Digest(), 32)
            else -> error("$label 缺少 BC 实现")
        }

        Kind.CRC32 -> Crc32Digest()
    }

    companion object {
        val DEFAULT_SELECTION = listOf(SHA256, SHA1)

        val COMMON: List<LiteAlgorithm> = entries.filter { it.group == AlgorithmGroup.COMMON }
        val MORE: List<LiteAlgorithm> = entries.filter { it.group == AlgorithmGroup.MORE }

        fun byHexLength(length: Int): List<LiteAlgorithm> = entries.filter { it.hexChars == length }
    }
}

/**
 * AArch64 的 HWCAP 位号（内核 asm/hwcaps.h）。
 *
 * 为什么可以直接相信这些位号：/proc/cpuinfo 的 Features 顺序与 HWCAP 位序一致，
 * 本机 Features 为 "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp
 * cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 ..."，
 * 由此可逐一对应（sha3 在 dcpop 之后 = bit17, sm3 = bit18, sha512 = bit21）。
 */
object Hwcap {
    const val SHA1 = 5
    const val SHA256 = 6
    const val CRC32 = 7
    const val SHA3 = 17
    const val SM3 = 18
    const val SM4 = 19
    const val SHA512 = 21
}
