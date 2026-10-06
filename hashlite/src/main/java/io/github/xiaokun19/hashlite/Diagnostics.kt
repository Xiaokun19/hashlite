package io.github.xiaokun19.hashlite

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：**崩溃**（未捕获异常）与**非致命错误**（降级、失败事件）统一记录、查看、导出。
 *
 * 关键设计：
 * - 捕获点：`Thread.setDefaultUncaughtExceptionHandler`（进程级）；**记录完仍然交回上一个
 *   handler**——系统照常杀进程/弹"应用已停止"，我们不吞异常；
 * - 非致命错误用 [recordError] 主动上报（尽量少而精：只放"出问题时值得回头查"的点）；
 * - 文件写在 App 私有目录（外部文件目录优先，其次内部），**不需要任何存储权限**；
 * - 每条报告 = 头部（版本/设备/设置/内存）+ 摘要 + 堆栈（可选）+ 最近事件（面包屑环）；
 * - 落盘路径上的一切都 `runCatching`——记录功能自己绝不能成为新的崩溃源；
 * - 导出（分享 / 保存）全部走 UI，不联网、不上传。
 */
object Diagnostics {

    enum class Kind { CRASH, ERROR }

    /** 一份诊断日志文件。 */
    data class Entry(
        val kind: Kind,
        val file: File,
        val name: String,
        val timeMillis: Long,
        val sizeBytes: Long,
    )

    private const val DIR_NAME = "reports"
    private const val MAX_FILES = 20
    private const val MAX_TRACE_CHARS = 96 * 1024
    private const val BREADCRUMB_CAPACITY = 120
    private const val KEY_LAST_SEEN = "lastSeenReport"

    private val breadcrumbs = ArrayDeque<String>(BREADCRUMB_CAPACITY + 1)
    private val lock = Any()
    private val onceKeys = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private var appStartElapsed = 0L
    private var appInfoLine = ""

    /** 在 Application.onCreate 里尽早调用。 */
    fun install(context: Context) {
        val app = context.applicationContext
        appStartElapsed = SystemClock.elapsedRealtime()
        appInfoLine = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.packageName} ${info.versionName}（vc$vc）"
        }.getOrDefault(app.packageName)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 先记录，再交回系统（顺序不能反：交回后进程通常立刻被杀）
            runCatching { writeReport(app, Kind.CRASH, "未捕获异常", null, throwable, thread) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        breadcrumb("app.start ($appInfoLine)")
        breadcrumb(
            "device ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} / " +
                (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
        )
    }

    /** 记一条"面包屑"（环形缓冲，崩溃/错误报告会带上它）。任何线程都可以调。 */
    fun breadcrumb(message: String) {
        val line = "[${timeText(System.currentTimeMillis())}] ${message.take(300)}"
        synchronized(lock) {
            if (breadcrumbs.size >= BREADCRUMB_CAPACITY) breadcrumbs.removeFirst()
            breadcrumbs.addLast(line)
        }
    }

    /** 记一条**非致命**错误（降级、失败事件），不会中断运行。 */
    fun recordError(context: Context, title: String, detail: String? = null, throwable: Throwable? = null) {
        runCatching {
            breadcrumb("error: $title")
            writeReport(context.applicationContext, Kind.ERROR, title, detail, throwable, Thread.currentThread())
        }
    }

    /** 同一进程内只执行一次（返回 true = 第一次）。用于"启动检查只报一次"这类场景。 */
    fun once(key: String): Boolean = onceKeys.add(key)

    // ------------------------------------------------------------------ 落盘

    private fun writeReport(
        context: Context,
        kind: Kind,
        title: String,
        detail: String?,
        throwable: Throwable?,
        thread: Thread,
    ) {
        val dir = reportDir(context)
        runCatching { dir.mkdirs() }
        val now = System.currentTimeMillis()
        val prefix = if (kind == Kind.CRASH) "crash" else "error"
        val stamp = fileStamp(now)
        var file = File(dir, "$prefix-$stamp.txt")
        var dedupe = 1
        while (file.exists()) {
            file = File(dir, "$prefix-$stamp-$dedupe.txt")
            dedupe++
        }
        val text = buildReport(context, kind, title, detail, throwable, thread, now)
        val tmp = File(dir, file.name + ".part")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            runCatching { tmp.delete() }
        }
        runCatching { prune(dir) }
    }

    private fun buildReport(
        context: Context,
        kind: Kind,
        title: String,
        detail: String?,
        throwable: Throwable?,
        thread: Thread,
        nowMillis: Long,
    ): String {
        val sb = StringBuilder(4096)
        sb.appendLine("=== HashLite 诊断报告 ===")
        sb.appendLine("种类: ${if (kind == Kind.CRASH) "崩溃（未捕获异常）" else "错误（非致命，App 仍在运行）"}")
        sb.appendLine("应用: $appInfoLine")
        sb.appendLine("时间: ${timeText(nowMillis)}")
        sb.appendLine(
            "设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / " +
                Build.SUPPORTED_ABIS.joinToString(),
        )
        runCatching {
            val settings = AppSettings.of(context)
            sb.appendLine("设置: theme=${settings.themeMode.pref} · lang=${settings.language} · keepAwake=${settings.keepAwake}")
        }
        runCatching {
            val rt = Runtime.getRuntime()
            sb.appendLine("内存: max ${rt.maxMemory() / MB}MB · total ${rt.totalMemory() / MB}MB · free ${rt.freeMemory() / MB}MB")
        }
        sb.appendLine("进程已存活: ${(SystemClock.elapsedRealtime() - appStartElapsed) / 1000}s · 线程: ${thread.name}（id=${thread.id}）")
        sb.appendLine()
        sb.appendLine("--- 摘要 ---")
        sb.appendLine(title)
        if (!detail.isNullOrBlank()) sb.appendLine(detail)
        if (throwable != null) {
            sb.appendLine()
            sb.appendLine("--- 异常堆栈 ---")
            val sw = StringWriter()
            runCatching { throwable.printStackTrace(PrintWriter(sw)) }
            var trace = sw.toString()
            if (trace.length > MAX_TRACE_CHARS) trace = trace.take(MAX_TRACE_CHARS) + "\n…（堆栈过长，已截断）"
            sb.append(trace)
            if (!trace.endsWith("\n")) sb.appendLine()
        }
        sb.appendLine()
        sb.appendLine("--- 最近事件（旧 → 新）---")
        synchronized(lock) { breadcrumbs.forEach { sb.appendLine(it) } }
        sb.appendLine()
        sb.appendLine("（本报告由 HashLite 自动生成；不含被哈希文件的内容，仅供排查问题。）")
        return sb.toString()
    }

    private fun prune(dir: File) {
        val list = dir.listFiles { f -> isReport(f.name) } ?: return
        if (list.size <= MAX_FILES) return
        list.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun isReport(name: String): Boolean =
        (name.startsWith("crash-") || name.startsWith("error-")) && name.endsWith(".txt")

    // ------------------------------------------------------------------ 读取 / 导出

    /** 所有诊断日志，新的在前。 */
    fun list(context: Context): List<Entry> {
        val dir = reportDir(context)
        val files = dir.listFiles { f -> isReport(f.name) } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            Entry(
                kind = if (f.name.startsWith("crash-")) Kind.CRASH else Kind.ERROR,
                file = f,
                name = f.name,
                timeMillis = f.lastModified(),
                sizeBytes = f.length(),
            )
        }
    }

    fun read(context: Context, entry: Entry): String =
        runCatching { entry.file.readText() }.getOrDefault("（读取失败）")

    fun delete(context: Context, entry: Entry): Boolean =
        runCatching { entry.file.delete() }.getOrDefault(false)

    /** 最新一条"没被看过"的报告（主界面卡片提示用）。 */
    fun latestUnseen(context: Context): Entry? {
        val newest = list(context).firstOrNull() ?: return null
        val seen = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SEEN, null)
        return if (newest.name == seen) null else newest
    }

    /** 把某份报告标为"已看过"（卡片不再提示）。 */
    fun markSeen(context: Context, entry: Entry) {
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SEEN, entry.name)
            .apply()
    }

    /** SAF 保存的建议文件名：`hashlite-crash-20261006-201533.txt`。 */
    fun exportName(entry: Entry): String = "hashlite-" + entry.name

    private fun reportDir(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve(DIR_NAME)

    private const val MB = 1024L * 1024

    fun displayTime(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    fun timeText(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun fileStamp(millis: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(millis))
}