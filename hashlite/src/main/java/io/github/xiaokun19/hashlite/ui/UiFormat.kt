package io.github.xiaokun19.hashlite.ui

import android.content.Context
import io.github.xiaokun19.hashlite.R
import io.github.xiaokun19.hashlite.core.HashParse

/** ETA 的本地化文本（空串 = 目前算不出 ETA）。 */
fun etaText(context: Context, seconds: Double): String {
    val parts = HashParse.etaParts(seconds) ?: return ""
    return if (parts.first == 0L) {
        context.getString(R.string.eta_seconds, parts.second)
    } else {
        context.getString(R.string.eta_minutes_seconds, parts.first, parts.second)
    }
}