package io.github.xiaokun19.hashlite

import android.content.Context

/** 深浅色模式：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode(val pref: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromPref(value: String?): ThemeMode = entries.firstOrNull { it.pref == value } ?: SYSTEM
    }
}

/**
 * 用户设置（SharedPreferences，与“大写开关”共用同一份 prefs 文件）。
 *
 * 长任务三项：
 * - [keepScreenOn]：计算期间保持屏幕常亮（不用任何权限，只保屏幕不保 CPU 锁）；
 * - [notifyProgress]：计算期间挂**常驻通知**（并拉起前台服务——防杀、防冻结压频）；
 * - [notifyResult]：结束后弹**普通通知**（成功/不匹配/失败）。
 *
 * 另两项：
 * - [keepAwake]：熄屏后继续计算（长任务期间持部分唤醒锁）；
 * - [themeMode] / [language]：外观与语言（见 [AppLocale]）。
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

    /**
     * 熄屏后继续计算：长任务期间持有**部分唤醒锁**（PARTIAL_WAKE_LOCK）。
     *
     * 为什么需要它：`FLAG_KEEP_SCREEN_ON` 只挡"自动熄屏"，挡不住用户**手动按电源键**；
     * 而熄屏后本机 ROM 会把后台进程冻结（实测连前台服务一起冻）。持有唤醒锁时系统通常
     * 会把 App 当"活跃任务"，不进 frozen 队列——这是 App 侧最后一张牌。
     */
    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /** 深浅色模式（默认跟随系统）。 */
    var themeMode: ThemeMode
        get() = ThemeMode.fromPref(prefs.getString(KEY_THEME_MODE, null))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.pref).apply()

    /** 界面语言："system" / "zh" / "en"（实际生效见 [AppLocale]）。 */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    companion object {
        const val PREFS = "hashlite"
        const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        const val KEY_NOTIFY_PROGRESS = "notifyProgress"
        const val KEY_NOTIFY_RESULT = "notifyResult"
        const val KEY_KEEP_AWAKE = "keepAwake"
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_LANGUAGE = "language"

        const val LANG_SYSTEM = "system"
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        fun of(context: Context): AppSettings = AppSettings(context)
    }
}