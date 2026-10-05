package io.github.xiaokun19.hashlite.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** 批量模式下"一个待算文件"的输入。 */
interface BatchFile {
    /** 相对路径（`sub/a.bin`），用于显示与和清单比对。 */
    val name: String
    val size: Long

    /** 清单里的期望值（小写十六进制）；只算不比时为 null。 */
    val expected: String?
    val expectedAlgorithm: LiteAlgorithm?

    fun open(): HashSource
}

/** 单元测试用：直接读本地文件。 */
class FileBatchFile(
    private val file: File,
    override val name: String = file.name,
    override val size: Long = file.length(),
    override val expected: String? = null,
    override val expectedAlgorithm: LiteAlgorithm? = null,
) : BatchFile {
    override fun open(): HashSource = FileHashSource(file)
}

/** 逐文件结论。 */
enum class Verdict {
    /** 与清单一致。 */
    MATCH,

    /** 清单里有值，但对不上。 */
    MISMATCH,

    /** 清单里没列这个文件（只算不比时全部是这个）。 */
    UNLISTED,

    /** 读失败 / 打开失败 / 被取消。 */
    ERROR,
}

data class BatchFileResult(
    val name: String,
    val size: Long,
    val hex: Map<LiteAlgorithm, String>,
    val expected: String?,
    val expectedAlgorithm: LiteAlgorithm?,
    val verdict: Verdict,
    val error: String? = null,
    val elapsedNanos: Long = 0L,
    val bytesPerSec: Double = 0.0,
) {
    val matched: Boolean get() = verdict == Verdict.MATCH

    /** 结果卡上显示的那一个值（多算法时取第一个）。 */
    val primaryHex: String? get() = hex.values.firstOrNull()

    val primaryAlgorithm: LiteAlgorithm? get() = hex.keys.firstOrNull()
}

data class BatchProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    /** 只统计大小已知的文件；inode 报不出大小时会偏小。 */
    val bytesTotal: Long,
    /** 所有 worker 合计的最近速度（2 秒滑窗）。 */
    val aggregateBytesPerSec: Double,
    /** 此刻正在算的文件名（≤ 并行度）。 */
    val running: List<String>,
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)

    val etaSeconds: Double
        get() = if (aggregateBytesPerSec <= 0.0) -1.0 else max(0L, bytesTotal - bytesDone) / aggregateBytesPerSec
}

data class BatchReport(
    val results: List<BatchFileResult>,
    /** 清单里有、目录里没有的文件名。 */
    val missing: List<String>,
    val totalBytes: Long,
    val elapsedNanos: Long,
    val workers: Int,
    val cancelled: Boolean = false,
    /** 大小报不出来的文件数（进度条只能靠"文件个数"看）。 */
    val unknownSizeFiles: Int = 0,
) {
    val matchedCount: Int get() = results.count { it.verdict == Verdict.MATCH }
    val mismatchedCount: Int get() = results.count { it.verdict == Verdict.MISMATCH }
    val unlistedCount: Int get() = results.count { it.verdict == Verdict.UNLISTED }
    val errorCount: Int get() = results.count { it.verdict == Verdict.ERROR }

    val aggregateBytesPerSec: Double
        get() = if (elapsedNanos <= 0L) 0.0 else totalBytes.toDouble() / (elapsedNanos / 1_000_000_000.0)

    /** 全部合格（没有不匹配 / 缺失 / 读取失败）。没有任何清单时也返回 true。 */
    val allGood: Boolean get() = mismatchedCount == 0 && errorCount == 0 && missing.isEmpty()

    val comparing: Boolean get() = results.any { it.verdict != Verdict.UNLISTED } || missing.isNotEmpty()
}

/**
 * 多文件并行哈希。
 *
 * 为什么值得单独做：单个文件的哈希链**无法并行**（实测硬顶 ~1.5 GB/s，加预读线程也不会更快），
 * 只有"同时算多个文件"才能把聚合吞吐抬到读取上限。每个 worker 跑一个文件（各自 1 个预读线程），
 * worker 数 = 并行度。
 *
 * 线程模型：K 个守护线程 + 一个 [AtomicInteger] 取号；每个文件的结果写回自己那格数组，
 * 所以顺序与并行度无关、结果必然等于串行。
 */
class BatchHasher(
    val workers: Int = DEFAULT_WORKERS,
    private val blockSize: Int = defaultBlockSize(workers),
    private val prefetchPerFile: Int = 1,
) {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * @param algorithmsFor 每个文件要算哪些算法。校验场景下按清单要求的算法算即可
     *   （64 位十六进制的清单不该顺手把 SHA3-256 和 SM3 一起算了，那是白送 5 倍时间）。
     */
    fun run(
        files: List<BatchFile>,
        checksumList: ChecksumList? = null,
        algorithmsFor: (BatchFile) -> List<LiteAlgorithm> = { DEFAULT_ALGORITHMS },
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): BatchReport {
        val results = arrayOfNulls<BatchFileResult>(files.size)
        if (files.isEmpty()) {
            return BatchReport(emptyList(), checksumList?.entries?.map { it.name } ?: emptyList(), 0L, 0L, workers)
        }

        val totalKnown = files.sumOf { if (it.size > 0L) it.size else 0L }
        val unknownSize = files.count { it.size <= 0L }
        val next = AtomicInteger(0)
        val filesDone = AtomicInteger(0)
        val bytesDone = AtomicLong(0L)
        val speed = AggregateSpeed()
        val running = ConcurrentHashMap.newKeySet<String>()
        val lastEmit = AtomicLong(0L)
        val emitLock = Any()

        /**
         * 多线程都会调它，所以"读计数 + 回调"必须整体串行：否则可能出现
         * A 线程先报了大值、B 线程（更早读到旧值）后报小值，界面上的进度条就会往回跳。
         */
        fun emit(force: Boolean) {
            val callback = onProgress ?: return
            synchronized(emitLock) {
                val now = System.nanoTime()
                if (!force && now - lastEmit.get() < EMIT_INTERVAL_NANOS) return
                lastEmit.set(now)
                val total = bytesDone.get()
                speed.record(total, now)
                callback(
                    BatchProgress(
                        filesDone = filesDone.get(),
                        filesTotal = files.size,
                        bytesDone = total,
                        bytesTotal = totalKnown,
                        aggregateBytesPerSec = speed.rate(now),
                        running = running.toList().sorted(),
                    ),
                )
            }
        }

        val start = System.nanoTime()
        val threads = (0 until max(1, workers)).map { workerId ->
            Thread({
                while (!cancelled) {
                    val index = next.getAndIncrement()
                    if (index >= files.size) break
                    val file = files[index]
                    val algorithms = algorithmsFor(file).ifEmpty { DEFAULT_ALGORITHMS }
                    running.add(file.name)
                    emit(true)

                    val fileStart = System.nanoTime()
                    var previous = 0L
                    val outcome = runCatching {
                        // 每个文件一个独立的哈希器：块大小/预读线程与单文件模式一致，只是并行跑
                        LiteHasher(blockSize, prefetchPerFile).hash(file.open(), algorithms) { progress ->
                            val delta = progress.doneBytes - previous
                            if (delta > 0L) {
                                previous = progress.doneBytes
                                bytesDone.addAndGet(delta)
                                emit(false)
                            }
                        }
                    }

                    val elapsed = System.nanoTime() - fileStart
                    results[index] = outcome.fold(
                        onSuccess = { hashed ->
                            BatchFileResult(
                                name = file.name,
                                size = file.size,
                                hex = hashed.hexByAlgorithm,
                                expected = file.expected,
                                expectedAlgorithm = file.expectedAlgorithm,
                                verdict = judge(hashed, file),
                                error = hashed.error,
                                elapsedNanos = elapsed,
                                bytesPerSec = if (elapsed > 0L) hashed.totalBytes.toDouble() / (elapsed / 1_000_000_000.0) else 0.0,
                            )
                        },
                        onFailure = { t ->
                            BatchFileResult(
                                name = file.name,
                                size = file.size,
                                hex = emptyMap(),
                                expected = file.expected,
                                expectedAlgorithm = file.expectedAlgorithm,
                                verdict = Verdict.ERROR,
                                error = t.message ?: t.toString(),
                                elapsedNanos = elapsed,
                            )
                        },
                    )
                    running.remove(file.name)
                    filesDone.incrementAndGet()
                    emit(true)
                }
            }, "batch-hash-$workerId").apply {
                isDaemon = true
                start()
            }
        }

        threads.forEach { it.join() }
        val elapsed = System.nanoTime() - start

        val finalResults = results.mapIndexed { index, result ->
            result ?: BatchFileResult(
                name = files[index].name,
                size = files[index].size,
                hex = emptyMap(),
                expected = files[index].expected,
                expectedAlgorithm = files[index].expectedAlgorithm,
                verdict = Verdict.ERROR,
                error = "已取消",
            )
        }
        val doneNames = files.filterIndexed { index, _ -> results[index] != null }.map { it.name }
        return BatchReport(
            results = finalResults,
            missing = checksumList?.missing(doneNames)?.map { it.name } ?: emptyList(),
            totalBytes = bytesDone.get(),
            elapsedNanos = elapsed,
            workers = workers,
            cancelled = cancelled,
            unknownSizeFiles = unknownSize,
        )
    }

    private fun judge(hashed: HashOutcome, file: BatchFile): Verdict {
        if (hashed.error != null) return Verdict.ERROR
        val expected = file.expected
        if (expected.isNullOrBlank()) return Verdict.UNLISTED
        val want = expected.trim().lowercase()
        val wanted = file.expectedAlgorithm
        if (wanted != null && hashed.hexByAlgorithm[wanted]?.equals(want, ignoreCase = true) == true) {
            return Verdict.MATCH
        }
        // 清单没写清算法（或认错了）：凡是长度对得上的算法都拿来比一次
        val anyLengthMatch = hashed.hexByAlgorithm.any { (algorithm, hex) ->
            algorithm.hexChars == want.length && hex.equals(want, ignoreCase = true)
        }
        return if (anyLengthMatch) Verdict.MATCH else Verdict.MISMATCH
    }

    companion object {
        /** 本机 2 个大核 —— 并行度默认贴着大核数，再往上会撞读带宽。 */
        const val DEFAULT_WORKERS = 2
        val DEFAULT_ALGORITHMS = listOf(LiteAlgorithm.SHA256)

        private const val EMIT_INTERVAL_NANOS = 120_000_000L

        /** 并行度高时把块调小：内存占用 = 并行度 × 2 × 块大小。 */
        fun defaultBlockSize(workers: Int): Int = if (workers <= 4) 4 * 1024 * 1024 else 2 * 1024 * 1024

        /** 按清单要求给每个文件挑算法（清单没写就用兜底）。 */
        fun algorithmsFor(entry: ChecksumEntry?, fallback: LiteAlgorithm): List<LiteAlgorithm> =
            listOf(entry?.algorithm ?: fallback)
    }
}

/** 所有 worker 合计的最近速度（2 秒滑窗，样本由 [record] 推进）。 */
private class AggregateSpeed {

    private val times = ArrayList<Long>(256)
    private val totals = ArrayList<Long>(256)

    @Synchronized
    fun record(total: Long, now: Long) {
        times.add(now)
        totals.add(total)
        if (times.size > 2048) {
            times.subList(0, times.size - 512).clear()
            totals.subList(0, totals.size - 512).clear()
        }
    }

    @Synchronized
    fun rate(now: Long): Double {
        if (times.isEmpty()) return 0.0
        val cutoff = now - WINDOW_NANOS
        var index = times.size - 1
        while (index > 0 && times[index - 1] >= cutoff) index--
        val span = now - times[index]
        val bytes = totals[totals.size - 1] - totals[index]
        return if (span > 0L) bytes.toDouble() / (span / 1_000_000_000.0) else 0.0
    }

    private companion object {
        const val WINDOW_NANOS = 2_000_000_000L
    }
}