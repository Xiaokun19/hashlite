package io.github.xiaokun19.hashlite

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一次"计算会话"的管家。三件事：
 * - [active]：Compose 可观察——UI 用它决定要不要 `FLAG_KEEP_SCREEN_ON`；
 * - [begin]/[progress]/[end]：给 [HashService]（常驻通知）喂状态；
 * - [setCancelHook]/[requestCancel]：通知栏"取消"按钮 → 哈希协程的取消回调。
 *
 * 只做编排，不碰哈希逻辑本身。
 */
object RunKeeper {

    /** 有没有会话在跑（UI 观察它来控制屏幕常亮）。 */
    var active by mutableStateOf(false)
        private set

    @Volatile
    private var cancelHook: (() -> Unit)? = null

    private var lastNotifyAt = 0L

    /** 本次会话的标题（文件名/目录名），进度更新时要带上，否则通知标题会回退成默认值。 */
    private var currentTitle = ""

    fun setCancelHook(hook: (() -> Unit)?) {
        cancelHook = hook
    }

    /** 通知栏点了"取消"。 */
    fun requestCancel() {
        cancelHook?.invoke()
    }

    /**
     * 开跑：按设置拉起前台服务（常驻通知）。
     *
     * [longTask] 用来避免"哈希一个 1 KB 文件也闪一条通知"：
     * 只有**大活**（调用方按体积判断，当前门槛 64 MB）才真的拉起前台服务；
     * 但 [active] 无论大小都置位——屏幕常亮对任何运行时都成立。
     */
    fun begin(context: Context, title: String, longTask: Boolean = true) {
        active = true
        currentTitle = title
        lastNotifyAt = 0L
        serviceStarted = longTask && AppSettings.of(context).notifyProgress
        if (serviceStarted) HashService.start(context, title)
    }

    /** 进度上报（节流 ~2 次/秒；[percent] 传 null 表示不确定进度）。 */
    fun progress(context: Context, text: String, percent: Int?) {
        if (!active || !serviceStarted) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 500L) return
        lastNotifyAt = now
        // 标题要一起带上：HashService 每次 update 都会重建通知，不带就回退成默认标题
        HashService.update(context, currentTitle, text, percent)
    }

    /** 结束：收起常驻通知、清掉取消回调，并按设置弹"完成通知"。 */
    fun end(context: Context, title: String, text: String, error: Boolean = false) {
        val wasService = serviceStarted
        active = false
        serviceStarted = false
        cancelHook = null
        if (wasService) HashService.stop(context)
        // 只有"用户没在看"时才弹完成通知——正盯着结果卡再弹一条纯属打扰
        if (AppSettings.of(context).notifyResult && !appVisible) {
            HashService.notifyResult(context, title, text, error)
        }
    }

    /**
     * App 是否在前台（由 MainActivity 的 onStart/onStop 维护）。
     * 决定"完成通知"要不要弹：用户正开着 App 时结果卡就在眼前，不必再通知。
     */
    @Volatile
    var appVisible: Boolean = false

    /** 这次会话是否真的挂了前台服务（诊断/自检用）。 */
    @Volatile
    var serviceStarted: Boolean = false
        private set

    /** 超过这个体积才算"长任务"（拉前台服务 + 常驻通知）。 */
    const val LONG_TASK_BYTES = 64L * 1024 * 1024
}