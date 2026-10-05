package io.github.xiaokun19.hashlite.core

import org.bouncycastle.crypto.Digest as BcDigest
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * 引擎内部的摘要抽象。
 *
 * 三种来源统一成"喂字节 → 出字节"：
 * - [JcaDigest]：平台实现（Conscrypt/BoringSSL），AArch64 上可能走硬件指令
 * - [BcBlockDigest]：BouncyCastle 纯软件实现（SHA-3、SM3 只能走这里）
 * - [Crc32Digest]：java.util.zip 的校验和
 */
interface BlockDigest {
    fun update(buffer: ByteBuffer)
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)
    fun finish(): ByteArray
}

class JcaDigest(private val md: MessageDigest) : BlockDigest {
    override fun update(buffer: ByteBuffer) {
        md.update(buffer)
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        md.update(bytes, offset, length)
    }

    override fun finish(): ByteArray = md.digest()
}

class BcBlockDigest(private val digest: BcDigest, private val outputBytes: Int) : BlockDigest {

    private val scratch = ByteArray(64 * 1024)

    override fun update(buffer: ByteBuffer) {
        if (buffer.hasArray()) {
            val array = buffer.array()
            val offset = buffer.arrayOffset() + buffer.position()
            val length = buffer.remaining()
            digest.update(array, offset, length)
            buffer.position(buffer.limit())
            return
        }
        while (buffer.hasRemaining()) {
            val length = minOf(buffer.remaining(), scratch.size)
            buffer.get(scratch, 0, length)
            digest.update(scratch, 0, length)
        }
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }

    override fun finish(): ByteArray {
        val out = ByteArray(outputBytes)
        digest.doFinal(out, 0)
        return out
    }
}

/** CRC32：输出 4 字节 = 校验值本身的大端字节序（十六进制 8 位）。 */
class Crc32Digest : BlockDigest {

    private val crc = CRC32()
    private val scratch = ByteArray(64 * 1024)

    override fun update(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val length = minOf(buffer.remaining(), scratch.size)
            buffer.get(scratch, 0, length)
            crc.update(scratch, 0, length)
        }
    }

    override fun update(bytes: ByteArray, offset: Int, length: Int) {
        crc.update(bytes, offset, length)
    }

    override fun finish(): ByteArray {
        val value = crc.value
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }
}

/**
 * 纯软件 CRC32（256 项查表），只用作"加速探测"的基线。
 *
 * 一开始写的是逐位实现，结果平台实现快了 346×——那个数字说明不了任何问题
 * （逐位版慢得离谱）。换成查表版后，比值才反映"平台是否真的用了 crc32 指令"。
 */
class SoftwareCrc32 {

    private var value = 0xFFFFFFFFL
    private val scratch = ByteArray(64 * 1024)

    fun update(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val length = minOf(buffer.remaining(), scratch.size)
            buffer.get(scratch, 0, length)
            update(scratch, 0, length)
        }
    }

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var crc = value
        for (i in offset until offset + length) {
            val index = ((crc xor (bytes[i].toLong() and 0xFF)) and 0xFF).toInt()
            crc = (crc ushr 8) xor TABLE[index]
        }
        value = crc
    }

    fun finish(): Long = value xor 0xFFFFFFFFL

    private companion object {
        val TABLE = LongArray(256) { index ->
            var c = index.toLong()
            repeat(8) {
                c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1
            }
            c
        }
    }
}