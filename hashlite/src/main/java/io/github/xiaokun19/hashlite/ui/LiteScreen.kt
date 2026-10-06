package io.github.xiaokun19.hashlite.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import io.github.xiaokun19.hashlite.AppSettings
import io.github.xiaokun19.hashlite.Diagnostics
import io.github.xiaokun19.hashlite.R
import io.github.xiaokun19.hashlite.RunKeeper
import io.github.xiaokun19.hashlite.ThemeMode
import io.github.xiaokun19.hashlite.core.AndroidFileSource
import io.github.xiaokun19.hashlite.core.HashParse
import io.github.xiaokun19.hashlite.core.HashProgress
import io.github.xiaokun19.hashlite.core.HardwareAcceleration
import io.github.xiaokun19.hashlite.core.LiteAlgorithm
import io.github.xiaokun19.hashlite.core.LiteHasher
import io.github.xiaokun19.hashlite.core.NativeKeccak
import io.github.xiaokun19.hashlite.core.SafTree
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
        fileName = queryName(context, uri).ifBlank { context.getString(R.string.fallback_shared_file) }
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
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
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
    var keepAwake by remember { mutableStateOf(appSettings.keepAwake) }
    var language by remember { mutableStateOf(appSettings.language) }

    // 诊断日志（崩溃 + 非致命错误）：列表 / 未读提示 / 查看与导出
    var diagFiles by remember { mutableStateOf<List<Diagnostics.Entry>>(emptyList()) }
    var diagUnseen by remember { mutableStateOf<Diagnostics.Entry?>(null) }
    var showDiagSheet by remember { mutableStateOf(false) }
    var diagSelected by remember { mutableStateOf<Diagnostics.Entry?>(null) }
    var diagText by remember { mutableStateOf("") }
    var diagCopied by remember { mutableStateOf(false) }
    var diagSaved by remember { mutableStateOf(false) }
    var notifyGranted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    var batteryOk by remember { mutableStateOf(batteryUnrestricted(context)) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifyGranted = granted
        }

    // 每次打开设置面板时重读"电池优化白名单"状态（用户可能刚从系统页回来）
    LaunchedEffect(state.showSettings) {
        if (state.showSettings) batteryOk = batteryUnrestricted(context)
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

    // 诊断日志：SAF 保存（与批量导出同一套写法）
    val diagSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && diagSelected != null) {
            val text = diagText
            scope.launch {
                val ok = withContext(Dispatchers.IO) { SafTree.writeText(context.contentResolver, uri, text) }
                if (ok) {
                    diagSaved = true
                    delay(1500)
                    if (diagSaved) diagSaved = false
                }
            }
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

    fun refreshDiag() {
        diagFiles = Diagnostics.list(context)
        diagUnseen = Diagnostics.latestUnseen(context)
    }

    fun openDiagSheet(entry: Diagnostics.Entry?) {
        val list = Diagnostics.list(context)
        val target = entry ?: list.firstOrNull() ?: return
        diagFiles = list
        diagSelected = target
        diagText = Diagnostics.read(context, target)
        Diagnostics.markSeen(context, target) // 看过即不再提示（卡片随之消失）
        diagUnseen = Diagnostics.latestUnseen(context)
        showDiagSheet = true
    }

    fun shareDiag(entry: Diagnostics.Entry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(R.string.diag_share_subject, Diagnostics.displayTime(entry.timeMillis)),
            )
            putExtra(Intent.EXTRA_TEXT, Diagnostics.read(context, entry))
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.diag_share_chooser)))
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
        Diagnostics.breadcrumb(
            "hash.start name=${state.fileName.take(120)} size=${state.fileSize} " +
                "algos=${state.selected.joinToString("/") { it.label }}",
        )
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error(context.getString(R.string.error_cannot_read_file))
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
                val cancelled = outcome.cancelled
                if (outcome.error != null) {
                    state.error = outcome.error
                    Diagnostics.recordError(context, "哈希读取失败", "${state.fileName}: ${outcome.error}")
                }
                Diagnostics.breadcrumb("hash.end ok=${outcome.success} cancelled=$cancelled err=${outcome.error ?: "-"}")
                RunKeeper.end(
                    context,
                    when {
                        cancelled -> context.getString(R.string.notif_title_cancelled, state.fileName)
                        outcome.success -> context.getString(R.string.notif_title_done, state.fileName)
                        else -> context.getString(R.string.notif_title_failed, state.fileName)
                    },
                    if (cancelled) context.getString(R.string.notif_text_cancelled) else hashSummary(context, outcome),
                    error = !outcome.success && !cancelled,
                    cancelled = cancelled,
                )
            }.onFailure {
                state.error = it.message ?: it.toString()
                Diagnostics.recordError(context, "哈希计算异常", "${state.fileName}: ${it.message ?: it}", it)
                RunKeeper.end(
                    context,
                    context.getString(R.string.notif_title_failed, state.fileName),
                    it.message ?: context.getString(R.string.notif_unknown_error),
                    error = true,
                )
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

        // 诊断日志：刷新未读提示 + 启动检查（"本应带 native 的构建"却不可用 → 记一条错误，进程内一次）
        refreshDiag()
        if (Diagnostics.once("native-check")) {
            val expected = nativeExpected(context)
            val loaded = NativeKeccak.loaded
            Diagnostics.breadcrumb("native: expected=$expected loaded=$loaded usable=${NativeKeccak.usable}")
            if (expected && !NativeKeccak.usable) {
                val detail = if (loaded) {
                    "库已加载但向量自检未通过：selfTestFails=${runCatching { NativeKeccak.selfTest() }.getOrDefault(-1)}" +
                        " · hwcapSha3=${runCatching { NativeKeccak.haveSha3Ext() }.getOrDefault(false)}" +
                        " · variant=${runCatching { NativeKeccak.variantName() }.getOrDefault("?")}"
                } else {
                    "libkeccak.so 加载失败（该构建本应携带它；可能是设备/ROM 兼容问题）"
                }
                Diagnostics.recordError(context, "SHA-3 加速库不可用，已回退纯软件实现", detail)
                refreshDiag()
            }
        }
    }

    val colors = MaterialTheme.colorScheme

    // CPU 能力只读一次（读 /proc/self/auxv，几 KB 文件，很快）
    val cpuFlags = remember { HardwareAcceleration.readCpuFlags() }
    val accelerated: (LiteAlgorithm) -> Boolean = { algorithm ->
        HardwareAcceleration.isAccelerated(algorithm, cpuFlags, state.accelRatios) ||
            // SHA3 走内置 native 库时也是真·硬件指令（ARMv8.2-SHA3 的 EOR3 等），一并亮徽标
            algorithm.nativeAccelerated
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

            // 有未查看的诊断日志（崩溃 / 错误报告）时提示一次；查看或忽略后消失
            diagUnseen?.let { entry ->
                DiagnosticsCard(
                    entry = entry,
                    onView = { openDiagSheet(entry) },
                    onShare = { shareDiag(entry) },
                    onIgnore = {
                        Diagnostics.markSeen(context, entry)
                        refreshDiag()
                    },
                )
            }

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
                    Text(
                        stringResource(R.string.error_with_message, message),
                        fontSize = 12.sp,
                        color = colors.error,
                    )
                }
                if (state.outcome?.cancelled == true) {
                    // 用户主动取消：中性色、不算“错误”
                    Text(
                        stringResource(R.string.state_cancelled),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.footer_readonly_note),
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
                    batteryUnrestricted = batteryOk,
                    keepAwake = keepAwake,
                    themeMode = themeMode,
                    language = language,
                    languageEnabled = !state.running,
                    diagCrashCount = diagFiles.count { it.kind == Diagnostics.Kind.CRASH },
                    diagErrorCount = diagFiles.count { it.kind == Diagnostics.Kind.ERROR },
                    diagLatestLabel = diagFiles.firstOrNull()?.let { Diagnostics.displayTime(it.timeMillis) },
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
                    onKeepAwake = { value ->
                        keepAwake = value
                        appSettings.keepAwake = value
                        // 打开时若正在跑长任务，立刻补一把唤醒锁（不用等下一次开始）
                        if (value && RunKeeper.active) RunKeeper.refreshWakeLock(context)
                    },
                    onThemeMode = { chosen -> onThemeModeChange(chosen) },
                    onLanguage = { chosen ->
                        language = chosen
                        appSettings.language = chosen
                        // 语言资源要靠 recreate 重载（locale 在 attachBaseContext 里套）
                        (context as? Activity)?.recreate()
                    },
                    onOpenDiag = { openDiagSheet(null) },
                    onRequestUnrestricted = { requestBatteryUnrestricted(context) },
                    onClose = { state.showSettings = false },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // 诊断日志面板：同一套浮层语言（遮罩点击关闭）
            if (showDiagSheet && diagSelected != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showDiagSheet = false },
                )
                DiagnosticsSheet(
                    entries = diagFiles,
                    selected = diagSelected,
                    text = diagText,
                    copied = diagCopied,
                    saved = diagSaved,
                    onSelect = { entry ->
                        diagSelected = entry
                        diagText = Diagnostics.read(context, entry)
                    },
                    onShare = { diagSelected?.let { shareDiag(it) } },
                    onSave = { diagSelected?.let { diagSaver.launch(Diagnostics.exportName(it)) } },
                    onCopy = {
                        clipboard.setText(AnnotatedString(diagText))
                        diagCopied = true
                        scope.launch {
                            delay(1400)
                            diagCopied = false
                        }
                    },
                    onDelete = {
                        diagSelected?.let { Diagnostics.delete(context, it) }
                        val list = Diagnostics.list(context)
                        diagFiles = list
                        diagUnseen = Diagnostics.latestUnseen(context)
                        val next = list.firstOrNull()
                        if (next == null) {
                            showDiagSheet = false
                            diagSelected = null
                        } else {
                            diagSelected = next
                            diagText = Diagnostics.read(context, next)
                        }
                    },
                    onClose = { showDiagSheet = false },
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
            Text(stringResource(R.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            val hw = HardwareAcceleration.acceleratedCommon(flags, ratios)
            Text(
                if (hw.isEmpty()) {
                    stringResource(R.string.header_hw_none)
                } else {
                    stringResource(R.string.header_hw_enabled, hw.joinToString("/") { it.label })
                },
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
                Text(stringResource(R.string.pick_file), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.pick_file_hint),
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
                    if (state.fileSize > 0L) HashParse.formatBytes(state.fileSize) else stringResource(R.string.file_size_unknown),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPick, enabled = !state.running) {
                        Text(stringResource(R.string.action_replace))
                    }
                    TextButton(onClick = onClear, enabled = !state.running) {
                        Text(stringResource(R.string.action_clear))
                    }
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
            Text(
                stringResource(R.string.section_algorithms),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.selected.size > 1) {
                Text(
                    stringResource(R.string.algorithms_single_pass),
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                )
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
                stringResource(R.string.more_algorithms),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
            if (moreSelected > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.more_algorithms_selected, moreSelected),
                    fontSize = 11.sp,
                    color = colors.primary,
                )
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
                stringResource(R.string.legend_accel),
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
                                contentDescription = stringResource(R.string.badge_hardware_accel),
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
            Text(
                stringResource(if (state.running) R.string.action_computing else R.string.action_start),
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
                            val eta = etaText(context, progress.etaSeconds)
                            if (eta.isNotEmpty()) append(" · ").append(eta)
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }

        state.outcome?.takeIf { it.success }?.let { outcome ->
            Text(
                stringResource(
                    R.string.summary_done,
                    HashParse.formatDuration(outcome.elapsedNanos),
                    HashParse.formatSpeed(outcome.bytesPerSec),
                ),
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
                Text(
                    stringResource(R.string.section_result),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.label_uppercase), fontSize = 11.sp, color = colors.onSurfaceVariant)
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
                }) {
                    Text(
                        stringResource(if (copied == "ALL") R.string.action_copied else R.string.action_copy_all),
                        fontSize = 12.sp,
                    )
                }
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
                                Text(
                                    stringResource(R.string.badge_hardware_accel),
                                    fontSize = 10.sp,
                                    color = colors.primary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(
                                    if (copied == algorithm.label) R.string.action_copied else R.string.action_tap_to_copy,
                                ),
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
        Text(
            stringResource(R.string.section_verify),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = state.compareText,
            onValueChange = { state.compareText = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            minLines = 2,
            placeholder = {
                Text(stringResource(R.string.compare_placeholder), fontSize = 12.sp)
            },
            textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        )

        val allowShort = LiteAlgorithm.CRC32 in state.selected
        val expected = HashParse.extractHex(state.compareText, allowShort)
        val outcome = state.outcome

        when {
            state.compareText.isBlank() -> Unit

            expected == null -> Text(
                stringResource(R.string.compare_invalid),
                fontSize = 12.sp,
                color = colors.error,
            )

            else -> {
                val candidates = HashParse.candidates(expected)
                Text(
                    stringResource(R.string.compare_recognized, candidates.joinToString(" / ") { it.label }),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                val compared = outcome?.hexByAlgorithm.orEmpty()
                    .filterKeys { it in candidates }
                when {
                    compared.isEmpty() -> Text(
                        stringResource(R.string.compare_need_compute),
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
                                        stringResource(if (pass) R.string.compare_pass else R.string.compare_fail),
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
private fun hashSummary(context: Context, outcome: io.github.xiaokun19.hashlite.core.HashOutcome): String = buildString {
    val first = outcome.hexByAlgorithm.entries.firstOrNull()
    if (first != null) {
        append(first.key.label).append(' ').append(first.value.take(16)).append('…')
        if (outcome.hexByAlgorithm.size > 1) {
            append(context.getString(R.string.notif_summary_more_algorithms, outcome.hexByAlgorithm.size))
        }
    } else {
        append(outcome.error ?: context.getString(R.string.state_done))
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
    return uri.lastPathSegment ?: context.getString(R.string.fallback_unknown_file)
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

/** 该构建是否"本应携带 native 库"（由构建时注入的 meta-data 标记）。 */
private fun nativeExpected(context: Context): Boolean = runCatching {
    val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    // 兼容两种存法：字符串 "true" 或布尔 true
    info.metaData?.get("hashlite.nativeExpected")?.toString() == "true"
}.getOrDefault(false)

@Suppress("unused")
private val unusedColorGuard: Color = Color.Transparent