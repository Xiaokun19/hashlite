package io.github.xiaokun19.hashlite

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
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

        handleIncoming(intent)
        setContent {
            HashLiteTheme {
                LiteScreen(
                    incomingUri = incomingUri.value,
                    incomingNonce = incomingNonce.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = extractUri(intent) ?: return
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
            val on = HardwareAcceleration.isAccelerated(algorithm, flags, ratios)
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%-10s 徽标=%-2s  %s",
                    algorithm.label,
                    if (on) "亮" else "灭",
                    HardwareAcceleration.explain(algorithm, flags, ratios),
                ),
            )
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "HashLite"
        const val EXTRA_HWCHECK = "hwcheck"
        const val EXTRA_BATCHCHECK = "batchcheck"
        const val EXTRA_FILES = "files"
        const val EXTRA_SIZE_MIB = "sizeMiB"
        const val EXTRA_WORKERS = "workers"
    }
}
