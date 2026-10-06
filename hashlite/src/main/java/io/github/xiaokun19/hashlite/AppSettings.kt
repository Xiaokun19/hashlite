package io.github.xiaokun19.hashlite

import android.content.Context

/**
 * 用户设置（SharedPreferences，与"大写开关"共用同一份 prefs 文件）。
 *
 * 三项都与"长任务体验"有关：
 * - [keepScreenOn]：计算期间保持屏幕常亮（不用任何权限，只保屏幕不保 CPU 锁）；
 * - [notifyProgress]：计算期间挂**常驻通知**（并拉起前台服务——防杀、防冻结压频）；
 * - [notifyResult]：结束后弹**普通通知**（成功/不匹配/失败）。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var notifyProgress: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_PROGRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_PROGRESS, value).apply()

    var notifyResult: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_RESULT, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_RESULT, value).apply()

    companion object {
        const val PREFS = "hashlite"
        const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        const val KEY_NOTIFY_PROGRESS = "notifyProgress"
        const val KEY_NOTIFY_RESULT = "notifyResult"

        fun of(context: Context): AppSettings = AppSettings(context)
    }
}