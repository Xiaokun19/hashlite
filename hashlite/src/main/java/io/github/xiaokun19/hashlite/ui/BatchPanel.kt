package io.github.xiaokun19.hashlite.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaokun19.hashlite.R
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
enum class HashMode(@StringRes val labelRes: Int) {
    SINGLE(R.string.mode_single),
    BATCH(R.string.mode_batch),
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
            docs.isEmpty() -> context.getString(R.string.batch_folder_empty)
            docs.size >= SafTree.MAX_FILES -> context.getString(R.string.batch_folder_too_many, SafTree.MAX_FILES)
            else -> null
        }
    }

    /** 选校验文件。 */
    fun loadChecksum(context: Context, uri: Uri): String? {
        val name = SafTree.queryName(context.contentResolver, uri) ?: context.getString(R.string.fallback_checksum_name)
        val parsed = ChecksumFile.parse(SafTree.readText(context.contentResolver, uri), name)
        checksumUri = uri
        checksumName = name
        checksumList = parsed
        report = null
        return when {
            parsed.entries.isEmpty() -> context.getString(R.string.batch_checksum_empty)
            parsed.badLines.isNotEmpty() -> context.resources.getQuantityString(
                R.plurals.ignored_lines,
                parsed.badLines.size,
                parsed.badLines.size,
            )
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
                    state.error = context.getString(R.string.batch_nothing_to_export)
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) { SafTree.writeText(context.contentResolver, uri, text) }
                if (ok) {
                    state.copied = "SAVED"
                    delay(1600)
                    if (state.copied == "SAVED") state.copied = null
                } else {
                    state.error = context.getString(R.string.batch_write_failed)
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
                            context.getString(
                                R.string.notif_batch_progress,
                                progress.filesDone,
                                progress.filesTotal,
                                HashParse.formatSpeed(progress.aggregateBytesPerSec),
                            ),
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
                val cancelled = report.cancelled
                val hasProblem = report.mismatchedCount > 0 || report.errorCount > 0 || report.missing.isNotEmpty()
                RunKeeper.end(
                    context,
                    when {
                        cancelled -> context.getString(R.string.notif_title_cancelled, state.folderName)
                        hasProblem -> context.getString(R.string.notif_title_batch_problem, state.folderName)
                        else -> context.getString(R.string.notif_title_batch_done, state.folderName)
                    },
                    context.getString(
                        R.string.notif_batch_result_text,
                        report.matchedCount,
                        report.mismatchedCount,
                        report.missing.size,
                        HashParse.formatSpeed(report.aggregateBytesPerSec),
                    ),
                    error = hasProblem && !cancelled,
                    cancelled = cancelled,
                )
            }.onFailure {
                state.error = it.message ?: it.toString()
                RunKeeper.end(
                    context,
                    context.getString(R.string.notif_title_batch_failed, state.folderName),
                    it.message ?: context.getString(R.string.notif_unknown_error),
                    error = true,
                )
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

        if (state.report?.cancelled == true) {
            // 用户主动取消：中性色、不算“错误”
            Text(
                stringResource(R.string.batch_cancelled_incomplete),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.error?.let { message ->
                Text(
                    stringResource(R.string.notice_with_message, message),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            stringResource(R.string.batch_footer_note),
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
                Text(stringResource(R.string.pick_folder), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.pick_folder_hint),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
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
                        append(pluralStringResource(R.plurals.file_count, state.fileCount, state.fileCount))
                        if (state.totalBytes > 0L) append(" · ").append(HashParse.formatBytes(state.totalBytes))
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPick, enabled = !running) {
                        Text(stringResource(R.string.action_replace))
                    }
                    TextButton(onClick = onClear, enabled = !running) {
                        Text(stringResource(R.string.action_clear))
                    }
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
            Text(
                stringResource(R.string.section_checksum_file),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )

            val list = state.checksumList
            if (list == null) {
                Text(
                    stringResource(R.string.checksum_hint),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariant,
                )
                TextButton(onClick = onPick, enabled = !running) {
                    Text(stringResource(R.string.pick_checksum))
                }
            } else {
                Text(
                    state.checksumName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val entriesText = pluralStringResource(R.plurals.entries_count, list.entries.size, list.entries.size)
                Text(
                    buildString {
                        append(entriesText).append(" · ").append(list.format.displayName)
                        list.algorithm?.let { append(" · ").append(it.label) }
                        if (list.hasUnknownAlgorithm) append(" · ").append(stringResource(R.string.checksum_unknown_algo))
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPick, enabled = !running) {
                        Text(stringResource(R.string.action_replace))
                    }
                    TextButton(onClick = onClear, enabled = !running) {
                        Text(stringResource(R.string.action_clear))
                    }
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
        Text(
            stringResource(R.string.section_parallelism),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.parallelism_hint, cores),
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
            Text(
                stringResource(R.string.section_algorithms),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
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
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStart,
            enabled = state.canRun,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            val label = when {
                state.running -> stringResource(R.string.action_computing)
                state.checksumList != null -> pluralStringResource(
                    R.plurals.start_verify_files,
                    state.fileCount,
                    state.fileCount,
                )
                else -> pluralStringResource(R.plurals.start_hash_files, state.fileCount, state.fileCount)
            }
            Text(
                label,
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
                            append(context.getString(R.string.batch_progress_files, progress.filesDone, progress.filesTotal))
                            if (progress.bytesTotal > 0L) {
                                append(" · ").append(HashParse.formatBytes(progress.bytesDone))
                                append(" / ").append(HashParse.formatBytes(progress.bytesTotal))
                            }
                            append(" · ").append(
                                context.getString(
                                    R.string.label_aggregate,
                                    HashParse.formatSpeed(progress.aggregateBytesPerSec),
                                ),
                            )
                            val eta = etaText(context, progress.etaSeconds)
                            if (eta.isNotEmpty()) append(" · ").append(eta)
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
                if (progress.running.isNotEmpty()) {
                    Text(
                        context.getString(
                            R.string.label_running_files,
                            progress.running.joinToString(context.getString(R.string.list_separator)) {
                                it.substringAfterLast('/')
                            },
                        ),
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

private fun verdictText(context: Context, verdict: Verdict): String = when (verdict) {
    Verdict.MATCH -> context.getString(R.string.verdict_match)
    Verdict.MISMATCH -> context.getString(R.string.verdict_mismatch)
    Verdict.UNLISTED -> context.getString(R.string.verdict_unlisted)
    Verdict.ERROR -> context.getString(R.string.verdict_error)
}

private const val VISIBLE_LIMIT = 60

@Composable
private fun BatchResultCard(state: BatchUiState, report: BatchReport, onCopy: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme

    val context = LocalContext.current
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
                Text(
                    stringResource(R.string.section_result),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onCopy("REPORT", buildReportText(context, state, report))
                }) {
                    Text(
                        stringResource(if (state.copied == "REPORT") R.string.action_copied else R.string.action_copy_report),
                        fontSize = 12.sp,
                    )
                }
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
                                    report.allGood -> stringResource(R.string.batch_all_passed)
                                    problems.isEmpty() -> stringResource(R.string.batch_some_failed)
                                    else -> pluralStringResource(
                                        R.plurals.files_failed,
                                        problems.size,
                                        problems.size,
                                    )
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            )
                            Text(
                                buildString {
                                    append(
                                        context.getString(
                                            R.string.batch_summary_counts,
                                            report.matchedCount,
                                            report.mismatchedCount,
                                            report.missing.size,
                                            report.unlistedCount,
                                        ),
                                    )
                                    if (report.errorCount > 0) {
                                        append(context.getString(R.string.batch_summary_error_suffix, report.errorCount))
                                    }
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
                    append(context.getString(R.string.throughput_aggregate, HashParse.formatSpeed(report.aggregateBytesPerSec)))
                    append(" · ").append(context.getString(R.string.throughput_workers, report.workers))
                    if (!averagePerFile.isNaN() && averagePerFile > 0.0) {
                        append(" · ").append(
                            context.getString(R.string.throughput_avg_per_file, HashParse.formatSpeed(averagePerFile)),
                        )
                    }
                    append(" · ").append(context.getString(R.string.throughput_elapsed, HashParse.formatDuration(report.elapsedNanos)))
                    append(" · ").append(context.getString(R.string.throughput_total, HashParse.formatBytes(report.totalBytes)))
                },
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariant,
            )

            if (report.unknownSizeFiles > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.files_unknown_size,
                        report.unknownSizeFiles,
                        report.unknownSizeFiles,
                    ),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }

            if (report.missing.isNotEmpty()) {
                Text(
                    stringResource(R.string.batch_missing_header),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.error,
                )
                report.missing.take(20).forEach { name ->
                    Text("· $name", fontSize = 11.5.sp, color = colors.onSurfaceVariant)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                shown.forEach { result -> BatchResultRow(result) }
            }

            if (sorted.size > shown.size) {
                TextButton(onClick = { state.showAll = true }) {
                    Text(
                        pluralStringResource(R.plurals.files_more, sorted.size - shown.size, sorted.size - shown.size),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchResultRow(result: io.github.xiaokun19.hashlite.core.BatchFileResult) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
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
                    append(verdictText(context, result.verdict))
                    if (result.size > 0L) append(" · ").append(HashParse.formatBytes(result.size))
                    if (result.bytesPerSec > 0.0) append(" · ").append(HashParse.formatSpeed(result.bytesPerSec))
                    when {
                        result.cancelled -> append(" · ").append(context.getString(R.string.state_cancelled))
                        else -> result.error?.let { append(" · ").append(it) }
                    }
                },
                fontSize = 11.sp,
                color = if (result.verdict == Verdict.MISMATCH || result.verdict == Verdict.ERROR) accent else colors.onSurfaceVariant,
            )
            val hex = result.primaryHex
            if (hex != null) {
                Text(
                    if (result.verdict == Verdict.MISMATCH) {
                        context.getString(R.string.batch_actual_hex, hex.take(20))
                    } else {
                        hex
                    },
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
                        context.getString(R.string.batch_expected_hex, it.take(20)),
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
    val context = LocalContext.current
    val algorithms = state.exportableAlgorithms()
    val text = state.exportText()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.30f)),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.section_export),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.export_hint),
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

            Text(stringResource(R.string.label_format), fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                Text(
                    stringResource(R.string.export_sfv_warning),
                    fontSize = 11.sp,
                    color = colors.error,
                )
            }

            Text(
                context.getString(
                    R.string.export_preview,
                    text?.lineSequence()?.take(2)?.joinToString(" / ")?.take(80) ?: "—",
                ),
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
                ) {
                    Text(
                        stringResource(if (state.copied == "SAVED") R.string.state_saved else R.string.action_save_file),
                        fontSize = 13.sp,
                    )
                }
                TextButton(onClick = { text?.let(onCopy) }, enabled = text != null) {
                    Text(
                        stringResource(if (state.copied == "TEXT") R.string.action_copied else R.string.action_copy_text),
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                pluralStringResource(
                    R.plurals.export_default_name,
                    report.results.size,
                    state.suggestedExportName(),
                    report.results.size,
                ),
                fontSize = 10.5.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 结果卡上的"复制报告"：给排查用的一行一文件文本。 */
private fun buildReportText(context: Context, state: BatchUiState, report: BatchReport): String = buildString {
    appendLine(context.getString(R.string.report_title))
    appendLine(context.getString(R.string.report_folder, state.folderName, state.fileCount))
    state.checksumList?.let {
        appendLine(context.getString(R.string.report_checksum, state.checksumName, it.entries.size, it.format.displayName))
    }
    appendLine(context.getString(R.string.report_workers, report.workers))
    appendLine(
        context.getString(
            R.string.report_speed,
            HashParse.formatSpeed(report.aggregateBytesPerSec),
            HashParse.formatDuration(report.elapsedNanos),
        ),
    )
    appendLine(
        context.getString(
            R.string.report_verdicts,
            report.matchedCount,
            report.mismatchedCount,
            report.missing.size,
            report.unlistedCount,
            report.errorCount,
        ),
    )
    appendLine()
    for (result in report.results.sortedWith(compareBy({ severity(it.verdict) }, { it.name }))) {
        append(verdictText(context, result.verdict)).append('\t').append(result.name).append('\t')
        append(result.primaryHex ?: "-")
        result.error?.let { append("\t").append(it) }
        appendLine()
    }
    for (name in report.missing) appendLine(context.getString(R.string.report_missing_line, name))
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
                    stringResource(entry.labelRes),
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                )
            }
        }
    }
}