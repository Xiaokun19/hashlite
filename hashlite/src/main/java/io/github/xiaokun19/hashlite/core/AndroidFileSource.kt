package io.github.xiaokun19.hashlite.core

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * SAF 数据源。
 *
 * 用 [ParcelFileDescriptor.AutoCloseInputStream] 而不是 `FileInputStream(dup.fileDescriptor)`：
 * `ParcelFileDescriptor` 带终结器，被 GC 回收时会 close 它持有的 fd。若把 dup 的结果放在
 * 局部变量里，GC 随时可能把 fd 关掉，正在进行的读就会报 `Bad file descriptor`（EBADF）。
 * AutoCloseInputStream 内部持有 pfd 引用，既不会被提前终结，又负责释放 dup 出来的 fd。
 */
class AndroidFileSource(
    private val pfd: ParcelFileDescriptor,
    override val displayName: String,
    override val size: Long,
) : HashSource {

    private var stream: FileInputStream? = null

    override fun openChannel(): FileChannel {
        val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
        val input = ParcelFileDescriptor.AutoCloseInputStream(dup)
        stream = input
        return input.channel
    }

    override fun close() {
        runCatching { stream?.close() }
        stream = null
        runCatching { pfd.close() }
    }
}