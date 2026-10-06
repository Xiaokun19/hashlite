package io.github.xiaokun19.hashlite.core

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

data class HashOutcome(
    val hexByAlgorithm: Map<LiteAlgorithm, String>,
    val totalBytes: Long,
    val elapsedNanos: Long,
    val error: String? = null,
    /** 用户主动取消：不算“错误”（error 为空、hex 为空），UI 用中性样式显示。 */
    val cancelled: Boolean = false,
) {
    val success: Boolean get() = error == null && hexByAlgorithm.isNotEmpty()

    val bytesPerSec: Double
        get() = if (elapsedNanos <= 0L) 0.0 else totalBytes.toDouble() / (elapsedNanos / 1_000_000_000.0)
}

data class HashProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val bytesPerSec: Double,
    val etaSeconds: Double,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * 纯净版的流式哈希器。
 *
 * 设计刻意简单：
 * - direct [ByteBuffer] + 定位读，零拷贝喂给 [MessageDigest]
 * - 少量预读线程 + 有界槽队列，让读取与计算重叠（4MB 块 + 2 线程是实测的甜点）
 * - 只报"已读 / 总大小 / 最近速度 / 剩余时间"，不做温度、掉速、基准那一套
 *
 * 文件大小未知时（少数 provider 不给 SIZE）自动退化为单缓冲顺序读。
 */
class LiteHasher(
    private val blockSize: Int = 4 * 1024 * 1024,
    private val prefetchThreads: Int = 2,
) {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun hash(
        source: HashSource,
        algorithms: List<LiteAlgorithm>,
        onProgress: ((HashProgress) -> Unit)? = null,
    ): HashOutcome {
        if (algorithms.isEmpty()) return HashOutcome(emptyMap(), 0L, 0L, "未选择算法")
        return if (source.size <= 0L) {
            sequential(source, algorithms, onProgress)
        } else {
            prefetched(source, source.size, algorithms, onProgress)
        }
    }

    private fun prefetched(
        source: HashSource,
        total: Long,
        algorithms: List<LiteAlgorithm>,
        onProgress: ((HashProgress) -> Unit)?,
    ): HashOutcome {
        val digests = algorithms.map { it.newDigest() }
        val nBlocks = ((total + blockSize - 1) / blockSize).toInt()
        val slots = max(2, prefetchThreads + 1)
        val buffers = Array(slots) { ByteBuffer.allocateDirect(blockSize) }
        val lens = IntArray(slots)
        val states = IntArray(slots) { FREE }

        val nextIndex = AtomicInteger(0)
        // 先把计数设成线程数：否则消费者可能在线程尚未启动时误判"读线程都没了"
        val aliveReaders = AtomicInteger(prefetchThreads)
        val stopReading = AtomicBoolean(false)
        val lock = ReentrantLock()
        val cond = lock.newCondition()
        val speed = SpeedWindow()

        var done = 0L
        var hashNanos = 0L
        var error: String? = null
        val start = System.nanoTime()
        val channel = source.openChannel()

        fun readerLoop() {
            try {
                while (!cancelled && !stopReading.get()) {
                    val index = nextIndex.getAndIncrement()
                    if (index >= nBlocks) break
                    val slot = index % slots

                    lock.lock()
                    try {
                        while (states[slot] != FREE) {
                            if (cancelled || stopReading.get()) return
                            cond.await(50, TimeUnit.MILLISECONDS)
                        }
                        states[slot] = READING
                    } finally {
                        lock.unlock()
                    }

                    val buffer = buffers[slot]
                    buffer.clear()
                    val base = index.toLong() * blockSize
                    var read = 0L
                    while (buffer.hasRemaining()) {
                        val n = try {
                            channel.read(buffer, base + read)
                        } catch (t: Throwable) {
                            error = "读取失败: ${t.message ?: t}"
                            -1
                        }
                        if (n <= 0) break
                        read += n
                    }

                    lock.lock()
                    try {
                        lens[slot] = buffer.position()
                        states[slot] = READY
                        cond.signalAll()
                    } finally {
                        lock.unlock()
                    }
                }
            } finally {
                aliveReaders.decrementAndGet()
                lock.lock()
                try {
                    cond.signalAll()
                } finally {
                    lock.unlock()
                }
            }
        }

        val readers = (1..prefetchThreads).map {
            Thread({ readerLoop() }, "hash-reader").apply {
                isDaemon = true
                start()
            }
        }

        var consumed = 0
        while (consumed < nBlocks) {
            val slot = consumed % slots
            lock.lock()
            try {
                while (states[slot] != READY && !cancelled && aliveReaders.get() > 0) {
                    cond.await(50, TimeUnit.MILLISECONDS)
                }
            } finally {
                lock.unlock()
            }
            if (states[slot] != READY) break

            val buffer = buffers[slot]
            buffer.flip()
            val length = buffer.remaining()

            if (length > 0) {
                val t0 = System.nanoTime()
                for (digest in digests) {
                    buffer.rewind()
                    digest.update(buffer)
                }
                hashNanos += System.nanoTime() - t0
            }

            done += length
            consumed++

            val now = System.nanoTime()
            val recent = speed.add(now, done)
            onProgress?.invoke(
                HashProgress(
                    doneBytes = done,
                    totalBytes = total,
                    bytesPerSec = recent,
                    etaSeconds = if (recent > 0.0) (total - done) / recent else -1.0,
                ),
            )

            lock.lock()
            try {
                states[slot] = FREE
                cond.signalAll()
            } finally {
                lock.unlock()
            }
        }

        stopReading.set(true)
        lock.lock()
        try {
            cond.signalAll()
        } finally {
            lock.unlock()
        }
        // 不能在预读线程还处于 read 时关掉 channel，否则内核返回 EBADF
        readers.forEach { it.join(3_000) }

        val elapsed = System.nanoTime() - start
        source.close()

        val complete = consumed == nBlocks && error == null && !cancelled
        val hex = if (complete) {
            algorithms.mapIndexed { i, algorithm -> algorithm to HashParse.toHex(digests[i].finish()) }
                .toMap()
        } else {
            emptyMap()
        }
        // 用户取消：不算“错误”——error 留空、cancelled 置位，文案交给 UI 层
        val wasCancelled = cancelled && error == null
        val message = error
            ?: if (!wasCancelled && consumed != nBlocks) "读取未完成（$done / $total 字节）" else null
        return HashOutcome(hex, done, elapsed, message, cancelled = wasCancelled)
    }

    /** 文件大小未知时的退路：单缓冲顺序读到 EOF。 */
    private fun sequential(
        source: HashSource,
        algorithms: List<LiteAlgorithm>,
        onProgress: ((HashProgress) -> Unit)?,
    ): HashOutcome {
        val digests = algorithms.map { it.newDigest() }
        val channel = source.openChannel()
        val buffer = ByteBuffer.allocateDirect(blockSize)
        val speed = SpeedWindow()
        var done = 0L
        var error: String? = null
        val start = System.nanoTime()

        while (!cancelled) {
            buffer.clear()
            val n = try {
                channel.read(buffer)
            } catch (t: Throwable) {
                error = "读取失败: ${t.message ?: t}"
                -1
            }
            if (n <= 0) break
            buffer.flip()
            val length = buffer.remaining()
            for (digest in digests) {
                buffer.rewind()
                digest.update(buffer)
            }
            done += length
            val recent = speed.add(System.nanoTime(), done)
            onProgress?.invoke(
                HashProgress(
                    doneBytes = done,
                    totalBytes = 0L,
                    bytesPerSec = recent,
                    etaSeconds = -1.0,
                ),
            )
        }

        val elapsed = System.nanoTime() - start
        source.close()
        val complete = error == null && !cancelled
        val hex = if (complete) {
            algorithms.mapIndexed { i, algorithm -> algorithm to HashParse.toHex(digests[i].finish()) }
                .toMap()
        } else {
            emptyMap()
        }
        return HashOutcome(hex, done, elapsed, error, cancelled = cancelled && error == null)
    }

    private companion object {
        const val FREE = 0
        const val READING = 1
        const val READY = 2
    }
}

/** 最近 2 秒的滑动窗口平均速度（ETA 用它才不会"卡住"）。 */
private class SpeedWindow {

    private val times = ArrayList<Long>(4096)
    private val bytes = ArrayList<Long>(4096)
    private var head = 0

    fun add(now: Long, done: Long): Double {
        times.add(now)
        bytes.add(done)
        val cutoff = now - WINDOW_NANOS
        while (head + 1 < times.size && times[head + 1] <= cutoff) head++
        val refTime = times[head]
        val refBytes = bytes[head]
        if (head > 4096) {
            times.subList(0, head).clear()
            bytes.subList(0, head).clear()
            head = 0
        }
        return if (now > refTime) (done - refBytes).toDouble() / ((now - refTime) / 1_000_000_000.0) else 0.0
    }

    private companion object {
        const val WINDOW_NANOS = 2_000_000_000L
    }
}