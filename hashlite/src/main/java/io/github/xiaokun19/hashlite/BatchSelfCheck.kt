package io.github.xiaokun19.hashlite

import io.github.xiaokun19.hashlite.core.BatchHasher
import io.github.xiaokun19.hashlite.core.ChecksumFile
import io.github.xiaokun19.hashlite.core.ChecksumFormat
import io.github.xiaokun19.hashlite.core.FileBatchFile
import io.github.xiaokun19.hashlite.core.HashParse
import io.github.xiaokun19.hashlite.core.LiteAlgorithm
import java.io.File
import java.util.Locale

/**
 * 批量的无头自检：不需要点屏幕，也不需要 SAF（SAF 目录树必须先由用户在系统选择器里授权）。
 *
 * 为什么单独做这个：本机 ROM 会过滤第三方 App 的 logcat，UI 自动化又容易点错，
 * 所以"真机验证"一律走**文件报告**（`getExternalFilesDir()/batchcheck.txt`，shell 可直接读）。
 *
 * 它回答三个问题：
 * 1. 真机存储上，并行到底能不能把聚合吞吐抬起来（并行度 → 吞吐 曲线 + 加速比）；
 * 2. 并行结果是否与串行**逐字节一致**（哈希是纯函数，并行只该改变耗时）；
 * 3. 导出 → 回读 → 校验 这条闭环在真实文件上是否成立（含"故意写错一个值"的负例）。
 */
object BatchSelfCheck {

    /** 单文件 4MB 块、2 预读线程——和界面里的默认值保持一致。 */
    fun run(
        dir: File,
        fileCount: Int,
        sizeMiB: Int,
        workerSets: List<Int>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== 批量并行哈希自检 ===")
        sb.appendLine("目录: ${dir.absolutePath}")
        sb.appendLine("环境: ${Runtime.getRuntime().availableProcessors()} 核 · loadavg ${loadAverage()}")
        sb.appendLine("参数: $fileCount 个文件 · 每个 ${sizeMiB}MiB · 并行度 ${workerSets.joinToString()}")

        val created = ensureFiles(dir, fileCount, sizeMiB)
        val fileList = (0 until fileCount).map { File(dir, nameOf(it)) }
        val totalBytes = fileList.sumOf { it.length() }
        sb.appendLine("数据量: ${HashParse.formatBytes(totalBytes)}（新建 $created 个文件）")
        // 说明测量边界：App 内没有 root，无法 drop_caches，所以这里读到的是
        // FUSE + 页缓存状态下的数字，不是"冷盘"数字。
        sb.appendLine("注: App 无 root，无法 drop_caches；以下数字是 FUSE 路径 + 页缓存状态下的值")
        sb.appendLine()

        sb.appendLine("--- 并行度 → 聚合吞吐 ---")
        var baseline: Map<String, String>? = null
        var serialAggregate = 0.0
        var singleFileBest = 0.0
        var exportedText: String? = null

        for (workers in workerSets) {
            val tasks = fileList.map { FileBatchFile(it) }
            val report = BatchHasher(workers = workers).run(tasks) { }
            val perFile = report.results.mapNotNull { it.bytesPerSec.takeIf { it > 0.0 } }

            sb.appendLine(
                String.format(
                    Locale.US,
                    "并行度 %-2d 聚合 %-11s 用时 %-9s 平均每文件 %-11s %s",
                    workers,
                    HashParse.formatSpeed(report.aggregateBytesPerSec),
                    HashParse.formatDuration(report.elapsedNanos),
                    HashParse.formatSpeed(perFile.average()),
                    if (baseline == null) "" else String.format(Locale.US, "加速比 %.2f×", report.aggregateBytesPerSec / serialAggregate),
                ),
            )

            if (baseline == null) {
                baseline = report.results.associate { it.name to (it.hex[LiteAlgorithm.SHA256] ?: "") }
                serialAggregate = report.aggregateBytesPerSec
                singleFileBest = perFile.maxOrNull() ?: 0.0
                exportedText = ChecksumFile.build(
                    report.results.mapNotNull { result ->
                        result.hex[LiteAlgorithm.SHA256]?.let { ChecksumFile.ChecksumLine(result.name, it) }
                    },
                    LiteAlgorithm.SHA256,
                    ChecksumFormat.COREUTILS,
                )
            } else {
                val mismatches = report.results.count { baseline?.get(it.name) != it.hex[LiteAlgorithm.SHA256] }
                sb.appendLine("           与串行结果不一致的文件数: $mismatches")
            }
        }

        sb.appendLine()
        sb.appendLine("单文件最好成绩 ${HashParse.formatSpeed(singleFileBest)}（哈希链是串行的上限）")
        sb.appendLine("并行度 1 的聚合 ${HashParse.formatSpeed(serialAggregate)}")
        sb.appendLine()

        sb.appendLine("--- 清单闭环（导出 → 解析 → 校验）---")
        val text = exportedText
        sb.appendLine("导出行数: ${text?.lineSequence()?.filter { it.isNotBlank() }?.count() ?: 0}")
        if (text != null) {
            sb.appendLine("首行预览: ${text.lineSequence().firstOrNull()}")
            val parsed = ChecksumFile.parse(text, "checksums.sha256")
            sb.appendLine("解析: ${parsed.entries.size} 条 · ${parsed.format.displayName} · 统一算法 ${parsed.algorithm?.label ?: "—"}")

            fun verify(list: io.github.xiaokun19.hashlite.core.ChecksumList, tag: String) {
                val report = BatchHasher(workers = 2).run(
                    files = fileList.map { file ->
                        val entry = list.lookup(file.name)
                        FileBatchFile(file, expected = entry?.hash, expectedAlgorithm = entry?.algorithm)
                    },
                    checksumList = list,
                    algorithmsFor = { file -> BatchHasher.algorithmsFor(list.lookup(file.name), LiteAlgorithm.SHA256) },
                )
                sb.appendLine(
                    "$tag 匹配 ${report.matchedCount} · 不匹配 ${report.mismatchedCount} · " +
                        "缺失 ${report.missing.size} · 未列出 ${report.unlistedCount} · 读取失败 ${report.errorCount}",
                )
            }

            verify(parsed, "正向校验:")

            // 负例：把第一个值改坏，必须被标成"不匹配"（否则整条判定链就是摆设）
            // 注意：Kotlin 的 String.replaceFirst(String, String) 走的是**字面量**替换，
            // 想按正则替换必须显式构造 Regex（这里踩过一次，篡改静默失败、负例假绿）。
            val badHash = "0".repeat(64)
            val hexRun = Regex("[0-9a-f]{64}")
            val lines = text.lineSequence().toMutableList()
            val index = lines.indexOfFirst { it.isNotBlank() }
            if (index >= 0) lines[index] = hexRun.replaceFirst(lines[index], badHash)
            val tamperedText = lines.joinToString("\n")
            sb.appendLine("篡改生效: " + (tamperedText != text))
            verify(ChecksumFile.parse(tamperedText, "checksums.sha256"), "反向校验(故意改坏一个值):")

            // 缺文件：清单里加一条不存在的文件
            val missingList = ChecksumFile.parse(text + "$badHash  ghost.bin\n", "checksums.sha256")
            verify(missingList, "含缺失项:")
        }

        sb.appendLine()
        sb.appendLine("--- 结论 ---")
        sb.appendLine("并行结果与串行一致 = 哈希逻辑没问题；聚合吞吐随并行度上升 = 并行确实吃到了多核")
        return sb.toString()
    }

    private fun nameOf(index: Int): String = String.format(Locale.US, "f%02d.bin", index)

    /** 造测试数据：内容按序号做伪随机填充，保证每个文件的哈希都不同。 */
    private fun ensureFiles(dir: File, count: Int, sizeMiB: Int): Int {
        dir.mkdirs()
        val target = sizeMiB.toLong() * 1024 * 1024
        var created = 0
        for (i in 0 until count) {
            val file = File(dir, nameOf(i))
            if (file.length() == target) continue
            file.outputStream().buffered(1 shl 16).use { out ->
                val chunk = ByteArray(1 shl 20)
                var seed = 1234 + i * 7919
                var written = 0L
                while (written < target) {
                    for (j in chunk.indices) {
                        seed = seed * 1103515245 + 12345
                        chunk[j] = (seed ushr 16).toByte()
                    }
                    val length = minOf(chunk.size.toLong(), target - written).toInt()
                    out.write(chunk, 0, length)
                    written += length
                }
            }
            created++
        }
        return created
    }

    private fun loadAverage(): String = runCatching {
        File("/proc/loadavg").readText().trim().split(" ").take(3).joinToString("/")
    }.getOrDefault("?")
}