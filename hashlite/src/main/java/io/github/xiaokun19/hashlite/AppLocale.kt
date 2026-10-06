package io.github.xiaokun19.hashlite

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言切换：把用户选的“界面语言”套到 Context 上。
 *
 * 做法是经典且全版本通用的一招：在 `Activity.attachBaseContext` 里读偏好、把 locale
 * 覆写进 Configuration。因此：
 * - 切换语言 = 存偏好 + `Activity.recreate()`（资源随之重载）；
 * - “跟随系统”时原样返回 base，尊重系统语言（含 Android 13+ 的系统级单应用语言）。
 */
object AppLocale {

    fun wrap(base: Context): Context {
        val pref = base.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
            .getString(AppSettings.KEY_LANGUAGE, AppSettings.LANG_SYSTEM)
        val locale: Locale = when (pref) {
            AppSettings.LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            AppSettings.LANG_EN -> Locale.ENGLISH
            else -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}