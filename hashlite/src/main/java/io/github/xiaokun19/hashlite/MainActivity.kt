package io.github.xiaokun19.hashlite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.xiaokun19.hashlite.core.HardwareAcceleration
import io.github.xiaokun19.hashlite.core.Hwcap
import io.github.xiaokun19.hashlite.core.LiteAlgorithm
import io.github.xiaokun19.hashlite.ui.LiteScreen
import io.github.xiaokun19.hashlite.ui.theme.HashLiteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val incomingUri = mutableStateOf<Uri?>(null)

    /**
     * 每次收到 Intent 都递增，用作 Compose 的 key。
     *
     * 只用 URI 当 key 的话，"重复分享同一个文件"时 URI 没变，
     * LaunchedEffect 不会重跑，界面上会残留上一次的计算结果。
     */
    private val incomingNonce = mutableStateOf(0L)

    /** 深浅色模式：默认跟随系统；由设置面板修改（Compose 状态，改一下全树重组）。 */
    private val themeMode = mutableStateOf(ThemeMode.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 无头检测入口（验证硬件加速徽标的判定逻辑）：
        //   am start -n io.github.xiaokun19.hashlite/.MainActivity -e hwcheck 1
        if (intent?.getStringExtra(EXTRA_HWCHECK) != null) {
            runHardwareCheckAndExit()
            return
        }

        // 批量并行自检（真机上的"并行度 → 吞吐"曲线 + 清单闭环）：
        //   am start -n io.github.xiaokun19.hashlite/.MainActivity -e batchcheck 1 -e files 8 -e sizeMiB 32 -e workers 1,2,4
        if (intent?.getStringExtra(EXTRA_BATCHCHECK) != null) {
            runBatchCheckAndExit()
            return
        }

        // native Keccak 自检（能不能用 / 快多少 / 算得对不对）：
        //   am start -n io.github.xiaokun19.hashlite/.MainActivity -e nativecheck 1
        if (intent?.getStringExtra(EXTRA_NATIVECHECK) != null) {
            runNativeCheckAndExit()
            return
        }

        // 错误报告自检（写一条非致命错误再退出；报告进 reports/error-*.txt）：
        //   am start -n io.github.xiaokun19.hashlite/.MainActivity -e errorcheck 1
        if (intent?.getStringExtra(EXTRA_ERRORCHECK) != null) {
            Diagnostics.recordError(this, "测试错误报告（-e errorcheck 1）", "无头自检写入的错误样例；App 不会崩溃。")
            finish()
            return
        }

        // 崩溃日志自检（延迟 1.5s 抛异常 → 全局捕获 → 写报告 → 系统杀进程）：
        //   am start -n io.github.xiaokun19.hashlite/.MainActivity -e crashcheck 1
        if (intent?.getStringExtra(EXTRA_CRASHCHECK) != null) {
            window.decorView.postDelayed(
                { throw RuntimeException("测试崩溃（-e crashcheck 1）：诊断日志自检") },
                1_500,
            )
        }

        handleIncoming(intent)
        themeMode.value = AppSettings.of(this).themeMode
        // 通知渠道的名称/说明跟随“界面语言”：启动时顺手建一遍（幂等，会更新名称）
        HashService.ensureChannels(this)

        setContent {
            val mode = themeMode.value
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 系统栏图标颜色跟随“实际生效”的主题（手动覆盖时不能再用系统值）
            LaunchedEffect(dark) { applySystemBarAppearance(dark) }
            HashLiteTheme(darkTheme = dark) {
                LiteScreen(
                    incomingUri = incomingUri.value,
                    incomingNonce = incomingNonce.value,
                    themeMode = mode,
                    onThemeModeChange = { chosen ->
                        themeMode.value = chosen
                        AppSettings.of(this@MainActivity).themeMode = chosen
                    },
                )
            }
        }
    }

    /** 界面语言换成别的语言时，资源要靠 recreate 重载（locale 在 attachBaseContext 里套）。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /** 系统栏（状态栏/导航栏）图标亮暗跟随 App 实际主题。 */
    private fun applySystemBarAppearance(dark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    override fun onStart() {
        super.onStart()
        // 前台可见时不必再弹"完成通知"（结果就在眼前）
        RunKeeper.appVisible = true
    }

    override fun onStop() {
        RunKeeper.appVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 诊断自检入口：支持 App 已经在前台运行时再触发
        if (intent.getStringExtra(EXTRA_ERRORCHECK) != null) {
            Diagnostics.recordError(this, "测试错误报告（-e errorcheck 1）", "无头自检写入的错误样例；App 不会崩溃。")
            finish()
            return
        }
        if (intent.getStringExtra(EXTRA_CRASHCHECK) != null) {
            window.decorView.postDelayed(
                { throw RuntimeException("测试崩溃（-e crashcheck 1）：诊断日志自检") },
                1_500,
            )
        }
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = extractUri(intent) ?: return
        Diagnostics.breadcrumb("intent.file $uri")
        incomingUri.value = uri
        incomingNonce.value += 1
    }

    /** 支持从文件管理器"分享"/"打开"进来；只把文件带进来，不自动开算。 */
    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }

            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    /**
     * 硬件加速检测报告：把"CPU 有没有指令 / 平台有没有用上 / 探测要多久"写成文本，
     * 便于用 shell 直接读（无需在屏幕上点来点去）。
     */
    private fun runHardwareCheckAndExit() {
        lifecycleScope.launch {
            val target = File(getExternalFilesDir(null) ?: filesDir, "hwcheck.txt")
            val report = withContext(Dispatchers.Default) { buildHardwareReport() }
            runCatching { target.writeText(report) }
            Log.i(TAG, report)
            finish()
        }
    }

    /** 批量并行自检：报告写到 `getExternalFilesDir()/batchcheck.txt`（shell 可直接读）。 */
    private fun runBatchCheckAndExit() {
        val fileCount = intent?.getStringExtra(EXTRA_FILES)?.toIntOrNull() ?: 8
        val sizeMiB = intent?.getStringExtra(EXTRA_SIZE_MIB)?.toIntOrNull() ?: 32
        val workers = intent?.getStringExtra(EXTRA_WORKERS)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(1, 2, 4)

        lifecycleScope.launch {
            val base = getExternalFilesDir(null) ?: filesDir
            val report = withContext(Dispatchers.IO) {
                BatchSelfCheck.run(File(base, "batchcheck"), fileCount, sizeMiB, workers)
            }
            val target = File(base, "batchcheck.txt")
            runCatching { target.writeText(report) }
            Log.i(TAG, report)
            finish()
        }
    }

    /** native Keccak 自检：报告写到 `getExternalFilesDir()/nativecheck.txt`。 */
    private fun runNativeCheckAndExit() {
        val sizeMiB = intent?.getStringExtra(EXTRA_SIZE_MIB)?.toIntOrNull() ?: 64
        // 持续负载实验：-e sustain 30 [-e fgs 1] —— 验证前台服务能不能扛住系统压频
        val sustainSeconds = intent?.getStringExtra(EXTRA_SUSTAIN)?.toIntOrNull() ?: 0
        val useFgs = intent?.getStringExtra(EXTRA_FGS) == "1"
        lifecycleScope.launch {
            val base = getExternalFilesDir(null) ?: filesDir
            if (sustainSeconds > 0 && useFgs) {
                RunKeeper.begin(this@MainActivity, "持续负载测试", longTask = true)
                RunKeeper.setCancelHook { NativeSelfCheck.requestStop() }
                RunKeeper.progress(this@MainActivity, "准备中…", null)
            }
            val report = withContext(Dispatchers.Default) {
                buildString {
                    append(NativeSelfCheck.run(sizeMiB))
                    if (sustainSeconds > 0) {
                        appendLine()
                        appendLine("--- 持续负载 ${sustainSeconds}s（前台服务=${if (useFgs) "开" else "关"}）---")
                        append(
                            NativeSelfCheck.sustain(sustainSeconds) { percent, text ->
                                RunKeeper.progress(this@MainActivity, text, percent)
                            },
                        )
                    }
                }
            }
            if (sustainSeconds > 0 && useFgs) {
                RunKeeper.end(this@MainActivity, "持续负载测试", "完成", error = false)
            }
            val target = File(base, "nativecheck.txt")
            runCatching { target.writeText(report) }
            Log.i(TAG, report)
            finish()
        }
    }

    private fun buildHardwareReport(): String {
        val sb = StringBuilder()
        val flags = HardwareAcceleration.readCpuFlags()

        // 先看缓存命中情况：命中时应该几乎不花时间（只读 auxv + SharedPreferences）
        val cachedStart = System.nanoTime()
        val cached = HardwareAcceleration.cachedRatios(this@MainActivity)
        val cachedMs = (System.nanoTime() - cachedStart) / 1_000_000.0
        sb.appendLine("=== 硬件加速检测报告 ===")
        sb.appendLine(
            "缓存: " + if (cached.isEmpty()) "空（首次运行）" else "命中 ${cached.size} 项",
        )
        sb.appendLine("读缓存耗时: %.3f ms".format(cachedMs))

        val started = System.nanoTime()
        val probe = HardwareAcceleration.probe()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val ratios = HardwareAcceleration.ratioMap(probe)

        sb.appendLine()
        sb.appendLine(
            "设备: ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT} / " +
                Build.SUPPORTED_ABIS.joinToString(),
        )
        sb.appendLine("CPU 能力: ${flags.note}")
        sb.appendLine(
            "  位号: sha1=${Hwcap.SHA1} sha2=${Hwcap.SHA256} crc32=${Hwcap.CRC32} " +
                "sha3=${Hwcap.SHA3} sm3=${Hwcap.SM3} sha512=${Hwcap.SHA512}",
        )
        sb.appendLine("  指令存在: " + listOf(
            "sha1" to flags.has(Hwcap.SHA1),
            "sha2" to flags.has(Hwcap.SHA256),
            "crc32" to flags.has(Hwcap.CRC32),
            "sha3" to flags.has(Hwcap.SHA3),
            "sm3" to flags.has(Hwcap.SM3),
            "sha512" to flags.has(Hwcap.SHA512),
        ).joinToString(" ") { "${it.first}=${if (it.second) "有" else "无"}" })
        sb.appendLine("实测（平台 vs 纯软件）: ${probe.summary()}")
        sb.appendLine("探测耗时: %.1f ms（一次性；之后走 SharedPreferences 缓存）".format(elapsedMs))
        // 报告也顺手把缓存写上，方便验证"第二次直接命中缓存"
        HardwareAcceleration.save(this@MainActivity, ratios, probe.summary())
        sb.appendLine("已写入缓存（下次启动直接命中，不再探测）")
        sb.appendLine()
        for (algorithm in LiteAlgorithm.entries) {
            // SHA3 走内置汇编时也是“实际在用加速路径”，和 UI 的亮标规则保持一致
            val on = HardwareAcceleration.isAccelerated(algorithm, flags, ratios) || algorithm.nativeAccelerated
            val why = if (algorithm.nativeAccelerated) {
                "内置汇编实现（native 库可用）"
            } else {
                HardwareAcceleration.explain(algorithm, flags, ratios)
            }
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%-10s 徽标=%-2s  %s",
                    algorithm.label,
                    if (on) "亮" else "灭",
                    why,
                ),
            )
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "HashLite"
        const val EXTRA_HWCHECK = "hwcheck"
        const val EXTRA_BATCHCHECK = "batchcheck"
        const val EXTRA_NATIVECHECK = "nativecheck"
        const val EXTRA_CRASHCHECK = "crashcheck"
        const val EXTRA_ERRORCHECK = "errorcheck"
        const val EXTRA_SUSTAIN = "sustain"
        const val EXTRA_FGS = "fgs"
        const val EXTRA_FILES = "files"
        const val EXTRA_SIZE_MIB = "sizeMiB"
        const val EXTRA_WORKERS = "workers"
    }
}
