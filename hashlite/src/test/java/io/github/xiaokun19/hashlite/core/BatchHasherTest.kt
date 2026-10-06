package io.github.xiaokun19.hashlite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 批量并行哈希。
 *
 * 最重要的那条不变量：**并行结果必须和串行一字不差**。哈希是纯函数，
 * 并行只该改变"多快"，不该改变"算出什么"。
 */
class BatchHasherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writePattern(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            var written = 0
            var seed = 7
            while (written < size) {
                for (i in chunk.indices) {
                    seed = seed * 1103515245 + 12345
                    chunk[i] = (seed ushr 16).toByte()
                }
                val length = minOf(chunk.size, size - written)
                out.write(chunk, 0, length)
                written += length
            }
        }
        return file
    }

    private fun oneShot(file: File, algorithm: LiteAlgorithm): String {
        val digest = algorithm.newDigest()
        digest.update(file.readBytes())
        return HashParse.toHex(digest.finish())
    }

    private fun sampleFiles(count: Int, baseSize: Int = 200_000): List<File> =
        (0 until count).map { writePattern("f$it.bin", baseSize + it * 12_345) }

    @Test
    fun `parallel result equals sequential result for every worker count`() {
        val files = sampleFiles(8)
        val expected = files.map { oneShot(it, LiteAlgorithm.SHA256) }

        for (workers in intArrayOf(1, 2, 3, 8)) {
            val report = BatchHasher(workers = workers, blockSize = 64 * 1024)
                .run(files.map { FileBatchFile(it) })

            assertEquals("workers=$workers 文件数", files.size, report.results.size)
            assertEquals("workers=$workers 字节数", files.sumOf { it.length() }, report.totalBytes)
            assertTrue("workers=$workers 无取消", !report.cancelled)
            report.results.forEachIndexed { index, result ->
                assertEquals("workers=$workers ${result.name}", expected[index], result.hex[LiteAlgorithm.SHA256])
                assertEquals(Verdict.UNLISTED, result.verdict)
                assertEquals(files[index].length(), result.size)
            }
            assertTrue("workers=$workers 有聚合速度", report.aggregateBytesPerSec > 0.0)
        }
    }

    @Test
    fun `worker order never changes the outcome`() {
        val files = sampleFiles(6, 150_000)
        val serial = BatchHasher(workers = 1, blockSize = 64 * 1024).run(files.map { FileBatchFile(it) })
        val parallel = BatchHasher(workers = 6, blockSize = 64 * 1024).run(files.map { FileBatchFile(it) })

        assertEquals(serial.results.size, parallel.results.size)
        serial.results.forEachIndexed { index, result ->
            assertEquals(result.name, parallel.results[index].name)
            assertEquals(result.hex, parallel.results[index].hex)
            assertEquals(result.verdict, parallel.results[index].verdict)
        }
        assertEquals(serial.totalBytes, parallel.totalBytes)
    }

    @Test
    fun `verdicts cover match mismatch unlisted and missing`() {
        val a = writePattern("a.bin", 100_000)
        val b = writePattern("b.bin", 120_000)
        val bad = writePattern("bad.bin", 90_000)
        val extra = writePattern("extra.bin", 50_000)

        val text = buildString {
            appendLine("${oneShot(a, LiteAlgorithm.SHA256)}  a.bin")
            appendLine("${oneShot(b, LiteAlgorithm.SHA256)}  b.bin")
            appendLine("${"0".repeat(64)}  bad.bin")
            appendLine("${"1".repeat(64)}  ghost.bin")
        }
        val list = ChecksumFile.parse(text, "checksums.sha256")

        val files = listOf(a, b, bad, extra).map { file ->
            val entry = list.lookup(file.name)
            FileBatchFile(file, expected = entry?.hash, expectedAlgorithm = entry?.algorithm)
        }
        val report = BatchHasher(workers = 2, blockSize = 64 * 1024).run(
            files = files,
            checksumList = list,
            algorithmsFor = { file -> BatchHasher.algorithmsFor(list.lookup(file.name), LiteAlgorithm.SHA256) },
        )

        val byName = report.results.associateBy { it.name }
        assertEquals(Verdict.MATCH, byName["a.bin"]?.verdict)
        assertEquals(Verdict.MATCH, byName["b.bin"]?.verdict)
        assertEquals(Verdict.MISMATCH, byName["bad.bin"]?.verdict)
        assertEquals(Verdict.UNLISTED, byName["extra.bin"]?.verdict)

        assertEquals(2, report.matchedCount)
        assertEquals(1, report.mismatchedCount)
        assertEquals(1, report.unlistedCount)
        assertEquals(0, report.errorCount)
        assertEquals(listOf("ghost.bin"), report.missing)
        assertTrue(report.comparing)
        assertTrue(!report.allGood)

        // 不匹配/缺失会在结果里标出来，能直接给用户看
        assertNotNull(byName["bad.bin"]?.expected)
        assertTrue(byName["bad.bin"]?.error == null)
    }

    @Test
    fun `checksum list decides which algorithm is computed`() {
        val file = writePattern("crc.bin", 200_000)
        val list = ChecksumFile.parse("crc.bin ${oneShot(file, LiteAlgorithm.CRC32)}\n", "checksums.sfv")
        val entry = list.lookup("crc.bin")
        assertEquals(LiteAlgorithm.CRC32, entry?.algorithm)

        val report = BatchHasher(workers = 1, blockSize = 64 * 1024).run(
            files = listOf(FileBatchFile(file, expected = entry?.hash, expectedAlgorithm = entry?.algorithm)),
            checksumList = list,
            algorithmsFor = { BatchHasher.algorithmsFor(list.lookup(it.name), LiteAlgorithm.SHA256) },
        )

        assertEquals(setOf(LiteAlgorithm.CRC32), report.results[0].hex.keys)
        assertEquals(Verdict.MATCH, report.results[0].verdict)
    }

    @Test
    fun `progress is monotonic and ends at the total`() {
        val files = sampleFiles(6, 300_000)
        val seen = ArrayList<Long>()
        var maxRunning = 0
        var maxFilesDone = 0

        val report = BatchHasher(workers = 3, blockSize = 64 * 1024).run(
            files = files.map { FileBatchFile(it) },
            onProgress = { progress ->
                seen += progress.bytesDone
                maxRunning = maxOf(maxRunning, progress.running.size)
                maxFilesDone = maxOf(maxFilesDone, progress.filesDone)
                assertTrue(progress.fraction in 0f..1f)
                assertEquals(files.sumOf { it.length() }, progress.bytesTotal)
                assertEquals(files.size, progress.filesTotal)
            },
        )

        assertTrue(seen.isNotEmpty())
        assertEquals("进度必须单调", seen.sorted(), seen)
        assertEquals(report.totalBytes, seen.last())
        assertTrue(maxRunning <= 3)
        assertEquals(files.size, maxFilesDone)
    }

    @Test
    fun `a file that cannot be opened does not kill the batch`() {
        val good = writePattern("good.bin", 120_000)
        val broken = object : BatchFile {
            override val name = "broken.bin"
            override val size = 4096L
            override val expected: String? = null
            override val expectedAlgorithm: LiteAlgorithm? = null
            override fun open(): HashSource = throw IllegalStateException("模拟打开失败")
        }

        val report = BatchHasher(workers = 2, blockSize = 64 * 1024)
            .run(listOf(FileBatchFile(good), broken))

        val byName = report.results.associateBy { it.name }
        assertEquals(Verdict.UNLISTED, byName["good.bin"]?.verdict)
        assertEquals(Verdict.ERROR, byName["broken.bin"]?.verdict)
        assertTrue(byName["broken.bin"]?.error?.contains("模拟打开失败") == true)
        assertEquals(1, report.errorCount)
        assertEquals(good.length(), report.totalBytes)
    }

    @Test
    fun `files with unknown size fall back to sequential reading`() {
        val file = writePattern("unknown.bin", 300_000)
        val report = BatchHasher(workers = 2, blockSize = 64 * 1024)
            .run(listOf(FileBatchFile(file, size = 0L)))

        assertEquals(1, report.unknownSizeFiles)
        assertEquals(file.length(), report.totalBytes)
        assertEquals(oneShot(file, LiteAlgorithm.SHA256), report.results[0].hex[LiteAlgorithm.SHA256])
    }

    @Test
    fun `cancelled before start reports every file as cancelled`() {
        val files = sampleFiles(3, 50_000)
        val hasher = BatchHasher(workers = 2, blockSize = 64 * 1024)
        hasher.cancel()

        val report = hasher.run(files.map { FileBatchFile(it) })

        assertTrue(report.cancelled)
        assertEquals(files.size, report.results.size)
        assertTrue(report.results.all { it.verdict == Verdict.ERROR && it.cancelled && it.error == null })
        assertEquals(0L, report.totalBytes)
    }

    @Test
    fun `empty folder produces an empty report instead of crashing`() {
        val report = BatchHasher(workers = 4).run(emptyList())
        assertTrue(report.results.isEmpty())
        assertTrue(report.missing.isEmpty())
        assertEquals(0L, report.totalBytes)
    }

    @Test
    fun `export then verify is a closed loop`() {
        val files = sampleFiles(5, 120_000)

        // 第一趟：只算不比
        val first = BatchHasher(workers = 3, blockSize = 64 * 1024).run(files.map { FileBatchFile(it) })
        val text = ChecksumFile.build(
            first.results.map { ChecksumFile.ChecksumLine(it.name, it.hex[LiteAlgorithm.SHA256]!!) },
            LiteAlgorithm.SHA256,
            ChecksumFormat.COREUTILS,
        )

        // 第二趟：拿导出的清单回来校验，必须全绿
        val list = ChecksumFile.parse(text, "checksums.sha256")
        val second = BatchHasher(workers = 3, blockSize = 64 * 1024).run(
            files = files.map { file ->
                val entry = list.lookup(file.name)
                FileBatchFile(file, expected = entry?.hash, expectedAlgorithm = entry?.algorithm)
            },
            checksumList = list,
            algorithmsFor = { BatchHasher.algorithmsFor(list.lookup(it.name), LiteAlgorithm.SHA256) },
        )

        assertEquals(files.size, second.matchedCount)
        assertEquals(0, second.mismatchedCount)
        assertEquals(0, second.unlistedCount)
        assertEquals(0, second.errorCount)
        assertTrue(second.missing.isEmpty())
        assertTrue(second.allGood)
    }
}