package io.github.xiaokun19.hashlite

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 哈希期间的**前台服务**（常驻通知）。
 *
 * 它自己不干活——哈希仍在 App 的协程里跑，这里只做两件事：
 * 1. **保住进程**：前台服务不会被普通后台限制冻结/杀掉，系统也更愿意给足频率
 *    （实测：没有它的时候，灭屏/不交互 1~3 秒后大核会被从 4.32G 压到 2.84G，SHA3 掉到 245 MB/s）；
 * 2. **显示进度**：文件名/进度/速度 + 一个"取消"按钮。
 *
 * 注意：
 * - Android 12+ 不允许**从后台**启动前台服务——我们只在用户点"开始"（App 在前台）时启动；
 * - Android 14+ 前台服务必须声明类型：这里用 `dataSync`（Play 上架时可能需换 `specialUse` 并写用途说明）；
 * - Android 13+ 即使用户**拒绝通知权限**，前台服务仍能运行（只是抽屉里看不到这条通知）。
 */
class HashService : Service() {

    private var foregroundStarted = false

    /** 界面语言包装过的 Context（通知文案跟随 App 内选择的语言，而不是系统语言）。 */
    private val ui: Context get() = AppLocale.wrap(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ui.getString(R.string.notif_default_title)
                startOrUpdate(title, null, null)
            }

            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ui.getString(R.string.notif_default_title)
                val text = intent.getStringExtra(EXTRA_TEXT)
                val percent = intent.getIntExtra(EXTRA_PERCENT, -1).takeIf { it in 0..100 }
                startOrUpdate(title, text, percent)
            }

            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
            }

            ACTION_CANCEL -> RunKeeper.requestCancel()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        super.onDestroy()
    }

    // 通知是 best-effort：Android 13+ 未授权时系统静默丢弃；万一抛 SecurityException，
    // 也已由下方 runCatching 兜住——这里不需要额外的权限分支（低版本更没有该运行时权限）。
    @SuppressLint("MissingPermission")
    private fun startOrUpdate(title: String, text: String?, percent: Int?) {
        ensureChannels(this)
        val notification = buildOngoing(title, text, percent)
        if (!foregroundStarted) {
            startForeground(NOTIF_ONGOING, notification)
            foregroundStarted = true
        } else {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ONGOING, notification) }
        }
    }

    private fun stopForegroundCompat() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    private fun buildOngoing(title: String, text: String?, percent: Int?): Notification {
        val content = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_cpu)
            .setContentTitle(title)
            .setContentText(text ?: ui.getString(R.string.notif_preparing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(this))
            .addAction(0, ui.getString(R.string.notif_cancel_action), cancelIntent(this))
        if (percent != null) {
            content.setProgress(100, percent, false)
        } else {
            content.setProgress(0, 0, true)
        }
        return content.build()
    }

    companion object {
        const val ACTION_START = "io.github.xiaokun19.hashlite.action.START"
        const val ACTION_UPDATE = "io.github.xiaokun19.hashlite.action.UPDATE"
        const val ACTION_STOP = "io.github.xiaokun19.hashlite.action.STOP"
        const val ACTION_CANCEL = "io.github.xiaokun19.hashlite.action.CANCEL"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PERCENT = "percent"

        const val CHANNEL_PROGRESS = "hashing"
        const val CHANNEL_RESULT = "results"

        private const val NOTIF_ONGOING = 1
        private const val NOTIF_RESULT = 2

        /** 渠道建一次即可；Android 8 以下没有渠道概念。名称/说明跟随 App 内语言（会更新）。 */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val ui = AppLocale.wrap(context)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, ui.getString(R.string.notif_channel_progress_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ui.getString(R.string.notif_channel_progress_desc)
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, ui.getString(R.string.notif_channel_result_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ui.getString(R.string.notif_channel_result_desc)
                },
            )
        }

        fun start(context: Context, title: String) {
            val intent = Intent(context, HashService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
            // ContextCompat 在 API<26 回退 startService：裸调 startForegroundService 在 24/25 上
            // 会 NoSuchMethodError（被 runCatching 静默吞掉 → 前台服务根本起不来）。
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun update(context: Context, title: String, text: String, percent: Int?) {
            val intent = Intent(context, HashService::class.java).setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent ?: -1)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * 停止常驻通知。
         *
         * 注意两点：
         * 1. **只调 `stopService` 是不够的**——实测过"服务已经没了、通知还挂在抽屉里"
         *    （本机 ROM 清 ServiceRecord 时不撤前台通知）。所以这里再显式 cancel 一次。
         * 2. **优先发 `ACTION_STOP` 服务消息（startService），而不是直接 `stopService`**：
         *    若服务刚被拉起、还没完成"转前台"，`stopService` 会把它提前撤掉，系统判定
         *    `startForegroundService()` 的 5 秒义务落空 → `ForegroundServiceDidNotStartInTimeException`
         *    （Android 12+ 系统级崩溃，见 2026-10-09 的诊断报告：读不了的文件点"开始"时触发）。
         *    走服务消息则"转前台 → 停止"按序执行、义务自然满足；后台受限
         *    （background start restrictions）时 `startService` 会失败，回退 `stopService`——
         *    那种场景服务早已完成过前台化，直接停是安全的。
         */
        fun stop(context: Context) {
            val viaMessage = runCatching {
                context.startService(Intent(context, HashService::class.java).setAction(ACTION_STOP)) != null
            }.getOrDefault(false)
            if (!viaMessage) {
                runCatching { context.stopService(Intent(context, HashService::class.java)) }
            }
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ONGOING) }
        }

        /** 结束提醒：普通通知，可滑掉、点了回 App。 */
        // 同上：完成通知属 best-effort，权限缺失时丢弃即可，异常已由 runCatching 兜住。
        @SuppressLint("MissingPermission")
        fun notifyResult(context: Context, title: String, text: String, error: Boolean) {
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_cpu)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .setPriority(if (error) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIF_RESULT, notification) }
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun cancelIntent(context: Context): PendingIntent {
            val intent = Intent(context, HashService::class.java).setAction(ACTION_CANCEL)
            return PendingIntent.getService(
                context,
                1,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}