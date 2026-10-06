package io.github.xiaokun19.hashlite.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import io.github.xiaokun19.hashlite.AppSettings
import io.github.xiaokun19.hashlite.R
import io.github.xiaokun19.hashlite.RunKeeper
import io.github.xiaokun19.hashlite.core.AndroidFileSource
import io.github.xiaokun19.hashlite.core.HashParse
import io.github.xiaokun19.hashlite.core.HashProgress
import io.github.xiaokun19.hashlite.core.HardwareAcceleration
import io.github.xiaokun19.hashlite.core.LiteAlgorithm
import io.github.xiaokun19.hashlite.core.LiteHasher
import io.github.xiaokun19.hashlite.ui.theme.verdictColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 界面状态。 */
class LiteUiState {

    var fileUri: Uri? by mutableStateOf(null)
    var fileName: String by mutableStateOf("")
    var fileSize: Long by mutableStateOf(0L)
    var selected: Set<LiteAlgorithm> by mutableStateOf(LiteAlgorithm.DEFAULT_SELECTION.toSet())
    var running by mutableStateOf(false)
    var progress: HashProgress? by mutableStateOf(null)
    var outcome: io.github.xiaokun19.hashlite.core.HashOutcome? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var compareText: String by mutableStateOf("")
    var copied: String? by mutableStateOf(null)

    /** 结果是否显示为大写（持久化，重启后保持）。 */
    var uppercase: Boolean by mutableStateOf(false)

    /** 帮助面板是否展开。 */
    var showHelp: Boolean by mutableStateOf(false)

    /** 设置面板是否展开。 */
    var showSettings: Boolean by mutableStateOf(false)

    /** "更多算法"是否展开（默认折叠）。 */
    var showMoreAlgorithms: Boolean by mutableStateOf(false)

    /** 加速探测结果：算法 → 平台实现比纯软件快几倍。 */
    var accelRatios: Map<LiteAlgorithm, Double> by mutableStateOf(emptyMap())

    fun loadUppercase(context: Context) {
        uppercase = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UPPERCASE, false)
    }

    fun setUppercase(context: Context, value: Boolean) {
        uppercase = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_UPPERCASE, value)
            .apply()
    }

    val hasFile: Boolean get() = fileUri != null
    val canRun: Boolean get() = hasFile && !running && selected.isNotEmpty()

    fun attach(context: Context, uri: Uri) {
        fileUri = uri
        fileName = queryName(context, uri).ifBlank { "分享的文件" }
        fileSize = querySize(context, uri)
        outcome = null
        progress = null
        error = null
    }

    fun clear() {
        fileUri = null
        fileName = ""
        fileSize = 0L
        outcome = null
        progress = null
        error = null
    }

    fun toggle(algorithm: LiteAlgorithm) {
        selected = if (algorithm in selected) selected - algorithm else selected + algorithm
    }

    private companion object {
        const val PREFS = "hashlite"
        const val KEY_UPPERCASE = "uppercase"
    }
}

@Composable
fun LiteScreen(
    incomingUri: Uri? = null,
    incomingNonce: Long = 0L,
    state: LiteUiState = remember { LiteUiState() },
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<LiteHasher?>(null) }
    var mode by remember { mutableStateOf(HashMode.SINGLE) }
    val batchState = remember { BatchUiState() }

    // ---- 设置（计算体验）：屏幕常亮 / 常驻通知 / 完成通知 ----
    val appSettings = remember { AppSettings.of(context) }
    var keepScreenOn by remember { mutableStateOf(appSettings.keepScreenOn) }
    var notifyProgress by remember { mutableStateOf(appSettings.notifyProgress) }
    var notifyResult by remember { mutableStateOf(appSettings.notifyResult) }
    var notifyGranted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifyGranted = granted
        }

    fun ensureNotifyPermission() {
        // Android 13+ 才有 POST_NOTIFICATIONS 运行时权限；低版本恒为已授予
        if (!notifyGranted) permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // 屏幕常亮：只在"有会话在跑"时点亮（RunKeeper.active 是 Compose 状态），
    // 任务结束、关掉开关或页面销毁时立刻释放——onDispose 是兜底，防止留下常亮。
    val view = LocalView.current
    DisposableEffect(RunKeeper.active, keepScreenOn) {
        view.keepScreenOn = RunKeeper.active && keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            state.attach(context, uri)
        }
    }

    fun copyToClipboard(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        state.copied = label
        scope.launch {
            delay(1400)
            if (state.copied == label) state.copied = null
        }
    }

    fun start() {
        val uri = state.fileUri ?: return
        if (state.selected.isEmpty() || state.running) return
        state.running = true
        state.outcome = null
        state.progress = null
        state.error = null
        val hasher = LiteHasher()
        engine = hasher
        // 大活（≥64MB 或大小未知）才拉前台服务：防杀、防降频；小文件只保屏幕常亮
        RunKeeper.begin(
            context,
            state.fileName,
            longTask = state.fileSize <= 0L || state.fileSize >= RunKeeper.LONG_TASK_BYTES,
        )
        RunKeeper.setCancelHook { hasher.cancel() } // 通知栏上的"取消"
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("读不到这个文件；如果是分享进来的，权限可能已失效，请用「更换」重新选一次")
                    var lastUpdate = 0L
                    hasher.hash(
                        source = AndroidFileSource(pfd, state.fileName, state.fileSize),
                        algorithms = LiteAlgorithm.entries.filter { it in state.selected },
                    ) { progress ->
                        val now = System.nanoTime()
                        if (now - lastUpdate > 100_000_000L || progress.fraction >= 1f) {
                            lastUpdate = now
                            state.progress = progress
                            RunKeeper.progress(
                                context,
                                buildString {
                                    append(HashParse.formatBytes(progress.doneBytes))
                                    if (progress.totalBytes > 0L) {
                                        append(" / ").append(HashParse.formatBytes(progress.totalBytes))
                                    }
                                    append(" · ").append(HashParse.formatSpeed(progress.bytesPerSec))
                                },
                                percent = if (progress.totalBytes > 0L) (progress.fraction * 100).toInt() else null,
                            )
                        }
                    }
                }
            }
            engine = null
            state.running = false
            state.progress = null
            result.onSuccess { outcome ->
                state.outcome = outcome
                if (outcome.error != null) state.error = outcome.error
                RunKeeper.end(context, state.fileName, hashSummary(outcome), error = !outcome.success)
            }.onFailure {
                state.error = it.message ?: it.toString()
                RunKeeper.end(context, state.fileName, "失败：${it.message ?: it}", error = true)
            }
        }
    }

    // 从"分享 / 打开"进来：只把文件带进来，**不自动开始计算**，等用户自己点。
    // key 里带上 nonce，保证同一个文件被重复分享时也会重新绑定（清掉上一次结果）。
    LaunchedEffect(incomingUri, incomingNonce) {
        if (incomingUri != null) {
            mode = HashMode.SINGLE
            state.attach(context, incomingUri)
        }
    }

    LaunchedEffect(Unit) {
        state.loadUppercase(context)
        // 先读缓存（不测）：命中就直接定徽标，启动路径上只有"读 auxv + 读 SharedPreferences"
        state.accelRatios = HardwareAcceleration.cachedRatios(context)
        if (state.accelRatios.isEmpty()) {
            android.util.Log.i("HashLite", "accel: 首次运行，后台探测硬件加速（一次性）")
            val probe = withContext(Dispatchers.Default) { HardwareAcceleration.probe() }
            HardwareAcceleration.save(context, HardwareAcceleration.ratioMap(probe), probe.summary())
            state.accelRatios = HardwareAcceleration.ratioMap(probe)
        } else {
            android.util.Log.i("HashLite", "accel: 命中缓存，跳过探测")
        }
    }

    val colors = MaterialTheme.colorScheme

    // CPU 能力只读一次（读 /proc/self/auxv，几 KB 文件，很快）
    val cpuFlags = remember { HardwareAcceleration.readCpuFlags() }
    val accelerated: (LiteAlgorithm) -> Boolean = { algorithm ->
        HardwareAcceleration.isAccelerated(algorithm, cpuFlags, state.accelRatios)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header(
                flags = cpuFlags,
                ratios = state.accelRatios,
                onHelp = { state.showHelp = true },
                onSettings = { state.showSettings = true },
            )

            ModeSwitcher(mode = mode, onSelect = { mode = it })

            if (mode == HashMode.BATCH) {
                BatchSection(state = batchState)
            } else {
                FileCard(
                    state = state,
                    onPick = { picker.launch(arrayOf("*/*")) },
                    onClear = { state.clear() },
                )

                AlgorithmPicker(state, accelerated)

                ActionArea(state = state, onStart = { start() }, onCancel = { engine?.cancel() })

                state.outcome?.let { outcome ->
                    if (outcome.hexByAlgorithm.isNotEmpty()) {
                        ResultCard(
                            outcome = outcome,
                            copied = state.copied,
                            uppercase = state.uppercase,
                            accelerated = accelerated,
                            onToggleUppercase = { state.setUppercase(context, !state.uppercase) },
                            onCopy = { label, text -> copyToClipboard(label, text) },
                        )
                    }
                }

                CompareCard(state = state)

                state.error?.let { message ->
                    Text("出错：$message", fontSize = 12.sp, color = colors.error)
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "文件只读访问，不申请存储权限",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }

            // 帮助面板：遮罩 + 自底升起的说明页
            if (state.showHelp) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { state.showHelp = false },
                )
                HelpSheet(
                    onClose = { state.showHelp = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // 设置面板：同一套浮层语言（遮罩点击关闭）
            if (state.showSettings) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { state.showSettings = false },
                )
                SettingsSheet(
                    keepScreenOn = keepScreenOn,
                    notifyProgress = notifyProgress,
                    notifyResult = notifyResult,
                    notifyPermissionGranted = notifyGranted,
                    onKeepScreenOn = {
                        keepScreenOn = it
                        appSettings.keepScreenOn = it
                    },
                    onNotifyProgress = { value ->
                        notifyProgress = value
                        appSettings.notifyProgress = value
                        if (value) ensureNotifyPermission()
                    },
                    onNotifyResult = { value ->
                        notifyResult = value
                        appSettings.notifyResult = value
                        if (value) ensureNotifyPermission()
                    },
                    onClose = { state.showSettings = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun Header(
    flags: HardwareAcceleration.CpuFlags,
    ratios: Map<LiteAlgorithm, Double>,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("哈希计算", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                HardwareAcceleration.headerSummary(flags, ratios),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
        }
        // 设置入口：齿轮（计算体验：屏幕常亮 / 通知）
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.10f))
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Text("⚙", fontSize = 17.sp, color = colors.primary)
        }
        Spacer(Modifier.width(8.dp))

        // 帮助入口：圆底问号，和文件卡里的"＋"同一套语言
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.10f))
                .clickable(onClick = onHelp),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
        }
    }
}

@Composable
private fun FileCard(state: LiteUiState, onPick: () -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        if (!state.hasFile) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.running, onClick = onPick)
                    .padding(vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", fontSize = 30.sp, color = colors.primary)
                }
                Text("选择文件", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "任意文件 · 只读访问",
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
                    state.fileName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.fileSize > 0L) HashParse.formatBytes(state.fileSize) else "大小未知",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPick, enabled = !state.running) { Text("更换") }
                    TextButton(onClick = onClear, enabled = !state.running) { Text("清除") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgorithmPicker(
    state: LiteUiState,
    accelerated: (LiteAlgorithm) -> Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("算法", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (state.selected.size > 1) {
                Text("会一次读完、并行算出全部", fontSize = 10.sp, color = colors.onSurfaceVariant)
            }
        }

        ChipRows(LiteAlgorithm.COMMON, state, accelerated)

        // "更多算法"：默认折叠，点一下展开
        val moreSelected = LiteAlgorithm.MORE.count { it in state.selected }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    state.showMoreAlgorithms = !state.showMoreAlgorithms
                    if (!state.showMoreAlgorithms) {
                        // 收起时把"更多"里的勾选一并取消，避免"看不见却还在算"
                        state.selected = state.selected - LiteAlgorithm.MORE.toSet()
                    }
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.showMoreAlgorithms) "▴" else "▾",
                fontSize = 12.sp,
                color = colors.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "更多算法（SHA-3 / 国密 / CRC32）",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
            if (moreSelected > 0) {
                Spacer(Modifier.width(6.dp))
                Text("已选 $moreSelected", fontSize = 11.sp, color = colors.primary)
            }
        }

        if (state.showMoreAlgorithms) {
            ChipRows(LiteAlgorithm.MORE, state, accelerated)
        }

        // 图例：让那枚芯片小图标自己说明含义（结果卡片里也用同一枚）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_cpu),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "= CPU 硬件指令加速（本机实测判定）；SHA3 / SM3 为软件实现，CRC32 是校验和、非加密哈希",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipRows(
    algorithms: List<LiteAlgorithm>,
    state: LiteUiState,
    accelerated: (LiteAlgorithm) -> Boolean,
) {
    // 2 列：本机字体缩放偏大时，"SHA-256 + 徽标"在 1/3 宽度里会把标签挤成 "SHA-2…"
    algorithms.chunked(2).forEach { rowItems ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rowItems.forEach { algorithm ->
                FilterChip(
                    selected = algorithm in state.selected,
                    onClick = { state.toggle(algorithm) },
                    label = {
                        Text(
                            algorithm.label,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    // 徽标放在 chip 自带的 trailingIcon 槽：label 在 M3 里是
                    // weight(1f, fill=false)，长标签会先让位，徽标不会被挤成几个 dp
                    trailingIcon = if (accelerated(algorithm)) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_cpu),
                                contentDescription = "硬件加速",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !state.running,
                )
            }
            repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ActionArea(state: LiteUiState, onStart: () -> Unit, onCancel: () -> Unit) {
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
                if (state.running) "计算中…" else "开始计算",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        state.progress?.let { progress ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 拿不到总大小时（个别 provider 如此）就不画进度条，只报已读字节与速度
                if (progress.totalBytes > 0L) {
                    SoftProgressBar(progress.fraction)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(HashParse.formatBytes(progress.doneBytes))
                            if (progress.totalBytes > 0) append(" / ").append(HashParse.formatBytes(progress.totalBytes))
                            append(" · ").append(HashParse.formatSpeed(progress.bytesPerSec))
                            val eta = HashParse.formatEta(progress.etaSeconds)
                            if (eta.isNotEmpty()) append(" · ").append(eta)
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }

        state.outcome?.takeIf { it.success }?.let { outcome ->
            Text(
                "完成 · ${HashParse.formatDuration(outcome.elapsedNanos)} · 平均 ${HashParse.formatSpeed(outcome.bytesPerSec)}",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SoftProgressBar(fraction: Float) {
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

@Composable
private fun ResultCard(
    outcome: io.github.xiaokun19.hashlite.core.HashOutcome,
    copied: String?,
    uppercase: Boolean,
    accelerated: (LiteAlgorithm) -> Boolean,
    onToggleUppercase: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结果", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("大写", fontSize = 11.sp, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Switch(
                    checked = uppercase,
                    onCheckedChange = { onToggleUppercase() },
                    modifier = Modifier.scale(0.7f),
                )
                TextButton(onClick = {
                    val text = outcome.hexByAlgorithm.entries.joinToString("\n") {
                        "${it.key.label}: ${HashParse.display(it.value, uppercase)}"
                    }
                    onCopy("ALL", text)
                }) { Text(if (copied == "ALL") "已复制" else "复制全部", fontSize = 12.sp) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(end = 8.dp)) {
                outcome.hexByAlgorithm.forEach { (algorithm, hex) ->
                    val shown = HashParse.display(hex, uppercase)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onCopy(algorithm.label, shown) }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                algorithm.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurfaceVariant,
                            )
                            if (accelerated(algorithm)) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_cpu),
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text("硬件加速", fontSize = 10.sp, color = colors.primary)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (copied == algorithm.label) "已复制" else "点按复制",
                                fontSize = 11.sp,
                                color = if (copied == algorithm.label) colors.primary else colors.onSurfaceVariant,
                            )
                        }
                        SelectionContainer {
                            Text(
                                shown,
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 19.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareCard(state: LiteUiState) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("校验", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = state.compareText,
            onValueChange = { state.compareText = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            minLines = 2,
            placeholder = {
                Text("粘贴校验值（md5 / sha1 / sha256 …，大小写与空格都不挑）", fontSize = 12.sp)
            },
            textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        )

        val allowShort = LiteAlgorithm.CRC32 in state.selected
        val expected = HashParse.extractHex(state.compareText, allowShort)
        val outcome = state.outcome

        when {
            state.compareText.isBlank() -> Unit

            expected == null -> Text(
                "没识别出有效的校验值",
                fontSize = 12.sp,
                color = colors.error,
            )

            else -> {
                val candidates = HashParse.candidates(expected)
                Text(
                    "识别为 " + candidates.joinToString(" / ") { it.label },
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                val compared = outcome?.hexByAlgorithm.orEmpty()
                    .filterKeys { it in candidates }
                when {
                    compared.isEmpty() -> Text(
                        "勾选上面的算法并计算后即可校验",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                    )

                    else -> compared.forEach { (algorithm, hex) ->
                        val pass = HashParse.matches(expected, hex, allowShort)
                        val (accent, _) = verdictColors(pass)
                        Surface(
                            color = accent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (pass) "✓" else "✗", fontSize = 20.sp, color = accent)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        if (pass) "校验通过" else "校验不通过",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = accent,
                                    )
                                    Text(algorithm.label, fontSize = 11.sp, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 完成通知里的一句话结果：算法 + 值前缀 + 耗时/速度。 */
private fun hashSummary(outcome: io.github.xiaokun19.hashlite.core.HashOutcome): String = buildString {
    val first = outcome.hexByAlgorithm.entries.firstOrNull()
    if (first != null) {
        append(first.key.label).append(' ').append(first.value.take(16)).append('…')
        if (outcome.hexByAlgorithm.size > 1) append("（共 ${outcome.hexByAlgorithm.size} 个算法）")
    } else {
        append(if (outcome.error != null) outcome.error else "完成")
    }
    append(" · ").append(HashParse.formatDuration(outcome.elapsedNanos))
    append(" · ").append(HashParse.formatSpeed(outcome.bytesPerSec))
}

private fun queryName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
            }
        }
    }
    return uri.lastPathSegment ?: "未知文件"
}

private fun querySize(context: Context, uri: Uri): Long {
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
    }
    return 0L
}

@Suppress("unused")
private val unusedColorGuard: Color = Color.Transparent