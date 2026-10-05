package io.github.xiaokun19.hashlite.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 数据来源抽象。
 *
 * - App 用 [AndroidFileSource]（SAF 的 ParcelFileDescriptor）
 * - 单元测试用 [FileHashSource]
 */
interface HashSource {
    val displayName: String
    val size: Long

    fun openChannel(): FileChannel

    fun close() {}
}

class FileHashSource(private val file: File) : HashSource {

    private var handle: RandomAccessFile? = null

    override val displayName: String get() = file.name
    override val size: Long get() = file.length()

    override fun openChannel(): FileChannel {
        val raf = RandomAccessFile(file, "r")
        handle = raf
        return raf.channel
    }

    override fun close() {
        runCatching { handle?.close() }
        handle = null
    }
}