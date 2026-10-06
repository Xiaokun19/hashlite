package io.github.xiaokun19.hashlite.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaokun19.hashlite.RunKeeper
import io.github.xiaokun19.hashlite.core.AndroidBatchFile
import io.github.xiaokun19.hashlite.core.BatchFile
import io.github.xiaokun19.hashlite.core.BatchHasher
import io.github.xiaokun19.hashlite.core.BatchProgress
import io.github.xiaokun19.hashlite.core.BatchReport
import io.github.xiaokun19.hashlite.core.ChecksumFile
import io.github.xiaokun19.hashlite.core.ChecksumFormat
import io.github.xiaokun19.hashlite.core.ChecksumList
import io.github.xiaokun19.hashlite.core.HashParse
import io.github.xiaokun19.hashlite.core.LiteAlgorithm
import io.github.xiaokun19.hashlite.core.SafTree
import io.github.xiaokun19.hashlite.core.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 顶层页签：单文件 / 批量。 */
enum class HashMode(val label: String) {
    SINGLE("单个文件"),
    BATCH("批量校验"),
}

/** 批量模式的状态。 */
class BatchUiState {

    var folderUri: Uri? by mutableStateOf(null)
    var folderName: String by mutableStateOf("")
    var docs: List<SafTree.Doc> by mutableStateOf(emptyList())

    /** 清单可选：不选就是"只算不比"。 */
    var checksumUri: Uri? by mutableStateOf(null)
    var checksumName: String by mutableStateOf("")
    var checksumList: ChecksumList? by mutableStateOf(null)

    /** 并行度 = 同时算几个文件。 */
    var workers: Int by mutableStateOf(BatchHasher.DEFAULT_WORKERS)

    /** 没有清单、或清单那行认不出算法时，用这个兜底。 */
    var fallbackAlgorithm: LiteAlgorithm by mutableStateOf(LiteAlgorithm.SHA256)

    var running: Boolean by mutableStateOf(false)
    var progress: BatchProgress? by mutableStateOf(null)
    var report: BatchReport? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)

    var exportFormat: ChecksumFormat by mutableStateOf(ChecksumFormat.COREUTILS)
    var exportAlgorithm: LiteAlgorithm? by mutableStateOf(null)
    var showAll: Boolean by mutableStateOf(false)
    var copied: String? by mutableStateOf(null)

    val fileCount: Int get() = docs.size
    val totalBytes: Long get() = docs.sumOf { if (it.size > 0L) it.size else 0L }
    val hasFolder: Boolean get() = folderUri != null && docs.isNotEmpty()
    val canRun: Boolean get() = hasFolder && !running

    /** 选目录。返回要提示给用户的话（null = 一切正常）。 */
    fun loadFolder(context: Context, uri: Uri): String? {
        SafTree.takePersistablePermission(context.contentResolver, uri)
        folderUri = uri
        folderName = SafTree.rootName(context.contentResolver, uri)
        docs = SafTree.listFiles(context.contentResolver, uri)
        report = null
        progress = null
        return when {
            docs.isEmpty() -> "这个目录里没有文件"
            docs.size >= SafTree.MAX_FILES -> "文件太多，只取前 ${SafTree.MAX_FILES} 个"
            else -> null
        }
    }

    /** 选校验文件。 */
    fun loadChecksum(context: Context, uri: Uri): String? {
        val name = SafTree.queryName(context.contentResolver, uri) ?: "校验文件"
        val parsed = ChecksumFile.parse(SafTree.readText(context.contentResolver, uri), name)
        checksumUri = uri
        checksumName = name
        checksumList = parsed
        report = null
        return when {
            parsed.entries.isEmpty() -> "没解析出校验值，检查一下文件格式"
            parsed.badLines.isNotEmpty() -> "有 ${parsed.badLines.size} 行没看懂，已忽略"
            else -> null
        }
    }

    fun clearChecksum() {
        checksumUri = null
        checksumName = ""
        checksumList = null
        report = null
    }

    fun clearFolder() {
        folderUri = null
        folderName = ""
        docs = emptyList()
        report = null
        progress = null
        error = null
    }

    fun tasks(context: Context): List<BatchFile> = docs.map { doc ->
        val entry = checksumList?.lookup(doc.name)
        AndroidBatchFile.fromDoc(context.contentResolver, doc, entry?.hash, entry?.algorithm)
    }

    fun algorithmsFor(file: BatchFile): List<LiteAlgorithm> =
        BatchHasher.algorithmsFor(checksumList?.lookup(file.name), fallbackAlgorithm)

    /** 结果里出现过的算法（导出时只能导出算过的那个）。 */
    fun exportableAlgorithms(): List<LiteAlgorithm> =
        report?.results?.mapNotNull { it.primaryAlgorithm }?.distinct()?.sortedBy { it.ordinal }.orEmpty()

    fun exportText(): String? {
        val current = report ?: return null
        val algorithm = exportAlgorithm ?: exportableAlgorithms().firstOrNull() ?: return null
        val lines = current.results.mapNotNull { result ->
            result.hex[algorithm]?.let { ChecksumFile.ChecksumLine(result.name, it) }
        }
        if (lines.isEmpty()) return null
        return ChecksumFile.build(lines, algorithm, exportFormat)
    }

    fun suggestedExportName(): String {
        val algorithm = exportAlgorithm ?: exportableAlgorithms().firstOrNull() ?: fallbackAlgorithm
        val base = folderName.ifBlank { null }
        return ChecksumFile.suggestFileName(base, algorithm, exportFormat)
    }
}

@Composable
fun BatchSection(state: BatchUiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<BatchHasher?>(null) }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            state.error = null
            scope.launch {
                val message = withContext(Dispatchers.IO) { state.loadFolder(context, uri) }
                if (message != null) state.error = message
            }
        }
    }

    val checksumPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            state.error = null
            scope.launch {
                val message = withContext(Dispatchers.IO) {
                    runCatching { state.loadChecksum(context, uri) }
                        .getOrElse { it.message ?: it.toString() }
                }
                if (message != null) state.error = message
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val text = state.exportText()
                if (text == null) {
                    state.error = "没有可导出的结果"
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) { SafTree.writeText(context.contentResolver, uri, text) }
                if (ok) {
                    state.copied = "已保存"
                    delay(1600)
                    if (state.copied == "已保存") state.copied = null
                } else {
                    state.error = "写入失败（这个位置可能不允许写入）"
                }
            }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        state.copied = label
        scope.launch {
            delay(1600)
            if (state.copied == label) state.copied = null
        }
    }

    fun start() {
        if (!state.canRun) return
        val tasks = state.tasks(context)
        state.running = true
        state.report = null
        state.progress = null
        state.error = null
        val hasher = BatchHasher(workers = state.workers)
        engine = hasher
        // 大活（总量 ≥64MB）才拉前台服务：防杀、防降频；小批量只保屏幕常亮
        RunKeeper.begin(
            context,
            state.folderName,
            longTask = state.totalBytes >= RunKeeper.LONG_TASK_BYTES,
        )
        RunKeeper.setCancelHook { hasher.cancel() } // 通知栏上的"取消"
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    hasher.run(tasks, state.checksumList, state::algorithmsFor) { progress ->
                        state.progress = progress
                        RunKeeper.progress(
                            context,
                            buildString {
                                append("已完成 ${progress.filesDone}/${progress.filesTotal} 个文件")
                                append(" · 聚合 ").append(HashParse.formatSpeed(progress.aggregateBytesPerSec))
                            },
                            percent = if (progress.bytesTotal > 0L) (progress.fraction * 100).toInt() else null,
                        )
                    }
                }
            }
            engine = null
            state.running = false
            state.progress = null
            result.onSuccess { report ->
                state.report = report
                state.exportAlgorithm = report.results.firstOrNull()?.primaryAlgorithm
                if (report.cancelled) state.error = "已取消（结果不完整）"
                val hasProblem = report.mismatchedCount > 0 || report.errorCount > 0 || report.missing.isNotEmpty()
                RunKeeper.end(
                    context,
                    state.folderName,
                    buildString {
                        if (report.cancelled) append("已取消 · ")
                        append("匹配 ${report.matchedCount} · 不匹配 ${report.mismatchedCount}")
                        append(" · 缺失 ${report.missing.size}")
                        append(" · ").append(HashParse.formatSpeed(report.aggregateBytesPerSec))
                    },
                    error = hasProblem,
                )
            }.onFailure {
                state.error = it.message ?: it.toString()
                RunKeeper.end(context, state.folderName, "失败：${it.message ?: it}", error = true)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FolderCard(state, running = state.running, onPick = { treePicker.launch(null) }, onClear = { state.clearFolder() })

        ChecksumCard(
            state = state,
            running = state.running,
            onPick = { checksumPicker.launch(arrayOf("*/*")) },
            onClear = { state.clearChecksum() },
        )

        ParallelCard(state)

        BatchAction(state, onStart = { start() }, onCancel = { engine?.cancel() })

        state.report?.let { report ->
            BatchResultCard(
                state = state,
                report = report,
                onCopy = { label, text -> copyToClipboard(label, text) },
            )
            ExportCard(
                state = state,
                report = report,
                onExport = { exportPicker.launch(state.suggestedExportName()) },
                onCopy = { copyToClipboard("TEXT", it) },
            )
        }

        state.error?.let { message ->
            Text("提示：$message", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        Text(
            "批量模式同样不申请存储权限：目录与文件都用 SAF 只读打开",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 卡片

@Composable
private fun FolderCard(state: BatchUiState, running: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        if (state.folderUri == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !running, onClick = onPick)
                    .padding(vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", fontSize = 28.sp, color = colors.primary)
                }
                Text("选择文件夹", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("整目录批量计算 / 校验", fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    state.folderName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(state.fileCount).append(" 个文件")
                        if (state.totalBytes > 0L) append(" · ").append(HashParse.formatBytes(state.totalBytes))
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPick, enabled = !running) { Text("更换") }
                    TextButton(onClick = onClear, enabled = !running) { Text("清除") }
                }
            }
        }
    }
}

@Composable
private fun ChecksumCard(state: BatchUiState, running: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.30f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("校验文件（可选）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

            val list = state.checksumList
            if (list == null) {
                Text(
                    "选 .md5 / .sha1 / .sha256 / .sfv 这类旁挂清单，就能直接比对；不选则只算不比",
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariant,
                )
                TextButton(onClick = onPick, enabled = !running) { Text("选择校验文件") }
            } else {
                Text(
                    state.checksumName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append("${list.entries.size} 条 · ${list.format.displayName}")
                        list.algorithm?.let { append(" · ").append(it.label) }
                        if (list.hasUnknownAlgorithm) append(" · 有认不出算法的行")
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPick, enabled = !running) { Text("更换") }
                    TextButton(onClick = onClear, enabled = !running) { Text("清除") }
                }
            }
        }
    }
}

private val WORKER_CHOICES = listOf(1, 2, 3, 4, 6, 8)

@Composable
private fun ParallelCard(state: BatchUiState) {
    val colors = MaterialTheme.colorScheme
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("并行度", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "同时算几个文件。单文件的哈希链没法并行，只有并行多个文件才能把总吞吐抬起来" +
                "（本机 ${cores} 核；默认 2）；想看加速比，就用 1 再跑一遍同一目录",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WORKER_CHOICES.forEach { choice ->
                FilterChip(
                    selected = state.workers == choice,
                    onClick = { state.workers = choice },
                    label = { Text("$choice", fontSize = 12.sp) },
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.checksumList == null) {
            Spacer(Modifier.height(2.dp))
            Text("算法", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(LiteAlgorithm.MD5, LiteAlgorithm.SHA1, LiteAlgorithm.SHA256).forEach { algorithm ->
                    FilterChip(
                        selected = state.fallbackAlgorithm == algorithm,
                        onClick = { state.fallbackAlgorithm = algorithm },
                        label = { Text(algorithm.label, fontSize = 12.sp, maxLines = 1) },
                        enabled = !state.running,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchAction(state: BatchUiState, onStart: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStart,
            enabled = state.canRun,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text(
                when {
                    state.running -> "计算中…"
                    state.checksumList != null -> "开始校验 ${state.fileCount} 个文件"
                    else -> "开始计算 ${state.fileCount} 个文件"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        state.progress?.let { progress ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.bytesTotal > 0L) BatchProgressBar(progress.fraction)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append("${progress.filesDone}/${progress.filesTotal} 个文件")
                            if (progress.bytesTotal > 0L) {
                                append(" · ").append(HashParse.formatBytes(progress.bytesDone))
                                append(" / ").append(HashParse.formatBytes(progress.bytesTotal))
                            }
                            append(" · 聚合 ").append(HashParse.formatSpeed(progress.aggregateBytesPerSec))
                            val eta = HashParse.formatEta(progress.etaSeconds)
                            if (eta.isNotEmpty()) append(" · ").append(eta)
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                if (progress.running.isNotEmpty()) {
                    Text(
                        "正在算：" + progress.running.joinToString("、") { it.substringAfterLast('/') },
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchProgressBar(fraction: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.onSurface.copy(alpha = 0.08f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.primary),
        )
    }
}

// ------------------------------------------------------------------ 结果

private fun severity(verdict: Verdict): Int = when (verdict) {
    Verdict.MISMATCH -> 0
    Verdict.ERROR -> 1
    Verdict.MATCH -> 2
    Verdict.UNLISTED -> 3
}

private fun verdictText(verdict: Verdict): String = when (verdict) {
    Verdict.MATCH -> "匹配"
    Verdict.MISMATCH -> "不匹配"
    Verdict.UNLISTED -> "未列出"
    Verdict.ERROR -> "读取失败"
}

private const val VISIBLE_LIMIT = 60

@Composable
private fun BatchResultCard(state: BatchUiState, report: BatchReport, onCopy: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val averagePerFile = report.results.filter { it.bytesPerSec > 0.0 }.map { it.bytesPerSec }.average()
    val problems = report.results.filter { it.verdict == Verdict.MISMATCH || it.verdict == Verdict.ERROR }
    val sorted = report.results.sortedWith(compareBy({ severity(it.verdict) }, { it.name }))
    val shown = if (state.showAll) sorted else sorted.take(VISIBLE_LIMIT)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结果", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onCopy("REPORT", buildReportText(state, report))
                }) { Text(if (state.copied == "REPORT") "已复制" else "复制报告", fontSize = 12.sp) }
            }

            // 结论条：有清单时看"匹配/不匹配/缺失"，没有清单时只说算完了
            if (report.comparing) {
                val (accent, _) = io.github.xiaokun19.hashlite.ui.theme.verdictColors(report.allGood)
                Surface(
                    color = accent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (report.allGood) "✓" else "✗", fontSize = 20.sp, color = accent)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                when {
                                    report.allGood -> "全部通过"
                                    problems.isEmpty() -> "有文件没有对上"
                                    else -> "${problems.size} 个文件没通过"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            )
                            Text(
                                buildString {
                                    append("匹配 ${report.matchedCount}")
                                    append(" · 不匹配 ${report.mismatchedCount}")
                                    append(" · 缺失 ${report.missing.size}")
                                    append(" · 未列出 ${report.unlistedCount}")
                                    if (report.errorCount > 0) append(" · 读取失败 ${report.errorCount}")
                                },
                                fontSize = 11.sp,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 吞吐：聚合速度是"并行度有用没用"的直接答案
            Text(
                buildString {
                    append("聚合 ").append(HashParse.formatSpeed(report.aggregateBytesPerSec))
                    append(" · 并行 ").append(report.workers)
                    if (!averagePerFile.isNaN() && averagePerFile > 0.0) {
                        append(" · 平均每文件 ").append(HashParse.formatSpeed(averagePerFile))
                    }
                    append(" · 用时 ").append(HashParse.formatDuration(report.elapsedNanos))
                    append(" · 共 ").append(HashParse.formatBytes(report.totalBytes))
                },
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )

            if (report.unknownSizeFiles > 0) {
                Text(
                    "有 ${report.unknownSizeFiles} 个文件报不出大小，进度条按已知字节算",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }

            if (report.missing.isNotEmpty()) {
                Text("缺失（清单里有、目录里没有）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
                report.missing.take(20).forEach { name ->
                    Text("· $name", fontSize = 11.5.sp, color = colors.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                shown.forEach { result -> BatchResultRow(result) }
            }

            if (sorted.size > shown.size) {
                TextButton(onClick = { state.showAll = true }) {
                    Text("还有 ${sorted.size - shown.size} 个文件，点这里全部展开", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BatchResultRow(result: io.github.xiaokun19.hashlite.core.BatchFileResult) {
    val colors = MaterialTheme.colorScheme
    val accent = when (result.verdict) {
        Verdict.MATCH -> io.github.xiaokun19.hashlite.ui.theme.verdictColors(true).first
        Verdict.MISMATCH, Verdict.ERROR -> io.github.xiaokun19.hashlite.ui.theme.verdictColors(false).first
        Verdict.UNLISTED -> colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (result.verdict) {
                Verdict.MATCH -> "✓"
                Verdict.MISMATCH -> "✗"
                Verdict.ERROR -> "!"
                Verdict.UNLISTED -> "·"
            },
            fontSize = 15.sp,
            color = accent,
            modifier = Modifier.width(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.name,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(verdictText(result.verdict))
                    if (result.size > 0L) append(" · ").append(HashParse.formatBytes(result.size))
                    if (result.bytesPerSec > 0.0) append(" · ").append(HashParse.formatSpeed(result.bytesPerSec))
                    result.error?.let { append(" · ").append(it) }
                },
                fontSize = 11.sp,
                color = if (result.verdict == Verdict.MISMATCH || result.verdict == Verdict.ERROR) accent else colors.onSurfaceVariant,
            )
            val hex = result.primaryHex
            if (hex != null) {
                Text(
                    if (result.verdict == Verdict.MISMATCH) "实际 ${hex.take(20)}…" else hex,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (result.verdict == Verdict.MISMATCH) {
                result.expected?.let {
                    Text(
                        "期望 ${it.take(20)}…",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    state: BatchUiState,
    report: BatchReport,
    onExport: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val algorithms = state.exportableAlgorithms()
    val text = state.exportText()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.30f)),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("导出校验文件", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "把这次算出来的哈希写成清单文件，以后再校验/分享给别人都用同一份格式",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )

            if (algorithms.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    algorithms.forEach { algorithm ->
                        FilterChip(
                            selected = (state.exportAlgorithm ?: algorithms.first()) == algorithm,
                            onClick = { state.exportAlgorithm = algorithm },
                            label = { Text(algorithm.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Text("格式", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ChecksumFormat.entries.forEach { format ->
                    FilterChip(
                        selected = state.exportFormat == format,
                        onClick = {
                            state.exportFormat = format
                            if (format == ChecksumFormat.SFV) {
                                // SFV 只装得下 8 位 CRC32
                                algorithms.firstOrNull { it == LiteAlgorithm.CRC32 }?.let { state.exportAlgorithm = it }
                            }
                        },
                        label = { Text(format.displayName, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val sfvMismatch = state.exportFormat == ChecksumFormat.SFV && state.exportAlgorithm != LiteAlgorithm.CRC32
            if (sfvMismatch) {
                Text("SFV 只支持 CRC32：请把并行度那里的清单算法换成 CRC32，或改选其它格式", fontSize = 11.sp, color = colors.error)
            }

            Text(
                "预览：${text?.lineSequence()?.take(2)?.joinToString(" / ")?.take(80) ?: "—"}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onExport,
                    enabled = text != null && !sfvMismatch && !state.running,
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (state.copied == "已保存") "已保存" else "保存文件", fontSize = 13.sp) }
                TextButton(onClick = { text?.let(onCopy) }, enabled = text != null) {
                    Text(if (state.copied == "TEXT") "已复制" else "复制文本", fontSize = 13.sp)
                }
            }
            Text(
                "默认文件名 ${state.suggestedExportName()} · 共 ${report.results.size} 条",
                fontSize = 10.5.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 结果卡上的"复制报告"：给排查用的一行一文件文本。 */
private fun buildReportText(state: BatchUiState, report: BatchReport): String = buildString {
    appendLine("批量哈希报告")
    appendLine("目录：${state.folderName}（${state.fileCount} 个文件）")
    state.checksumList?.let { appendLine("清单：${state.checksumName} · ${it.entries.size} 条 · ${it.format.displayName}") }
    appendLine("并行度：${report.workers}")
    appendLine("聚合速度：${HashParse.formatSpeed(report.aggregateBytesPerSec)} · 用时 ${HashParse.formatDuration(report.elapsedNanos)}")
    appendLine(
        "匹配 ${report.matchedCount} / 不匹配 ${report.mismatchedCount} / 缺失 ${report.missing.size} / " +
            "未列出 ${report.unlistedCount} / 读取失败 ${report.errorCount}",
    )
    appendLine()
    for (result in report.results.sortedWith(compareBy({ severity(it.verdict) }, { it.name }))) {
        append(verdictText(result.verdict)).append('\t').append(result.name).append('\t')
        append(result.primaryHex ?: "-")
        result.error?.let { append("\t").append(it) }
        appendLine()
    }
    for (name in report.missing) appendLine("缺失\t$name")
}

/** 顶层页签：单个文件 / 批量校验。 */
@Composable
fun ModeSwitcher(mode: HashMode, onSelect: (HashMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.onSurface.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HashMode.entries.forEach { entry ->
            val selected = entry == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .clickable { onSelect(entry) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.label,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}