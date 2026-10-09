package io.github.xiaokun19.hashlite.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaokun19.hashlite.AppSettings
import io.github.xiaokun19.hashlite.Diagnostics
import io.github.xiaokun19.hashlite.HashService
import io.github.xiaokun19.hashlite.R
import io.github.xiaokun19.hashlite.ThemeMode
import io.github.xiaokun19.hashlite.core.LiteAlgorithm

/**
 * 设置面板（自底升起，和帮助页同一套容器语言）。
 *
 * 分三块：
 * - 外观与语言：深浅色（跟随系统 / 浅色 / 深色）、界面语言（跟随系统 / 中文 / English）；
 * - 计算体验：屏幕常亮 / 常驻通知（前台服务）/ 完成通知 / 保持唤醒 / 电池白名单；
 * - 关于：版本、包名、SHA3 实现、权限、通知渠道。
 */
@Composable
fun SettingsSheet(
    keepScreenOn: Boolean,
    notifyProgress: Boolean,
    notifyResult: Boolean,
    notifyPermissionGranted: Boolean,
    batteryUnrestricted: Boolean,
    keepAwake: Boolean,
    themeMode: ThemeMode,
    language: String,
    languageEnabled: Boolean,
    diagnosticsEnabled: Boolean,
    diagCrashCount: Int,
    diagErrorCount: Int,
    diagLatestLabel: String?,
    onKeepScreenOn: (Boolean) -> Unit,
    onNotifyProgress: (Boolean) -> Unit,
    onNotifyResult: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (String) -> Unit,
    onDiagnosticsEnabled: (Boolean) -> Unit,
    onOpenDiag: () -> Unit,
    onRequestUnrestricted: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = colors.surface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部拖柄 + 标题
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurface.copy(alpha = 0.18f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }

            // navigationBarsPadding：三键导航栏出现时，底部内容不会被挡住（内容可滚到其上方）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionTitle(stringResource(R.string.settings_section_appearance))

                ChoiceRow(
                    title = stringResource(R.string.settings_theme),
                    subtitle = stringResource(R.string.settings_theme_hint),
                    options = ThemeMode.entries.map { it to themeLabel(it) },
                    selected = themeMode,
                    onSelect = onThemeMode,
                )

                ChoiceRow(
                    title = stringResource(R.string.settings_language),
                    subtitle = stringResource(R.string.settings_language_hint),
                    options = listOf(
                        AppSettings.LANG_SYSTEM to stringResource(R.string.lang_system),
                        AppSettings.LANG_ZH to "中文",
                        AppSettings.LANG_EN to "English",
                    ),
                    selected = language,
                    onSelect = onLanguage,
                    enabled = languageEnabled,
                )

                SectionTitle(stringResource(R.string.settings_section_compute))

                SwitchRow(
                    title = stringResource(R.string.settings_keep_screen_on),
                    subtitle = stringResource(R.string.settings_keep_screen_on_sub),
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOn,
                )

                val notifyProgressSub = stringResource(R.string.settings_notify_progress_sub) +
                    if (!notifyPermissionGranted) stringResource(R.string.settings_notify_perm_warning) else ""
                SwitchRow(
                    title = stringResource(R.string.settings_notify_progress),
                    subtitle = notifyProgressSub,
                    checked = notifyProgress,
                    onCheckedChange = onNotifyProgress,
                    warn = !notifyPermissionGranted && notifyProgress,
                )

                SwitchRow(
                    title = stringResource(R.string.settings_notify_result),
                    subtitle = stringResource(R.string.settings_notify_result_sub),
                    checked = notifyResult,
                    onCheckedChange = onNotifyResult,
                    warn = !notifyPermissionGranted && notifyResult,
                )

                SwitchRow(
                    title = stringResource(R.string.settings_keep_awake),
                    subtitle = stringResource(R.string.settings_keep_awake_sub),
                    checked = keepAwake,
                    onCheckedChange = onKeepAwake,
                )

                // 电池优化白名单：灭屏冻结的正解之一（本机 ROM 连前台服务都会冻）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_battery),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(
                                if (batteryUnrestricted) R.string.settings_battery_on else R.string.settings_battery_off,
                            ),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = if (batteryUnrestricted) colors.onSurfaceVariant else colors.error,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (batteryUnrestricted) {
                        Text("✓", fontSize = 15.sp, color = colors.primary)
                    } else {
                        TextButton(onClick = onRequestUnrestricted) {
                            Text(stringResource(R.string.action_go_settings), fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle(stringResource(R.string.settings_section_about))

                // 诊断开关：关闭后不再记录（已有报告仍可查看 / 删除）
                SwitchRow(
                    title = stringResource(R.string.settings_diag_toggle),
                    subtitle = stringResource(R.string.settings_diag_toggle_sub),
                    checked = diagnosticsEnabled,
                    onCheckedChange = onDiagnosticsEnabled,
                )

                // 诊断日志（崩溃 / 错误报告）：记录、查看、导出
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.diag_sheet_title),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (diagCrashCount + diagErrorCount == 0) {
                                stringResource(R.string.settings_diag_sub_empty)
                            } else {
                                stringResource(
                                    R.string.settings_diag_sub,
                                    diagCrashCount,
                                    diagErrorCount,
                                    diagLatestLabel ?: "—",
                                )
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = onOpenDiag, enabled = diagCrashCount + diagErrorCount > 0) {
                        Text(stringResource(R.string.diag_view), fontSize = 13.sp)
                    }
                }

                InfoRow(stringResource(R.string.about_version), versionName(context))
                InfoRow(stringResource(R.string.about_package), context.packageName)
                InfoRow(
                    stringResource(R.string.about_sha3),
                    stringResource(
                        if (LiteAlgorithm.SHA3_256.nativeAccelerated) R.string.impl_native else R.string.impl_bouncy,
                    ),
                )
                InfoRow(stringResource(R.string.about_permissions), stringResource(R.string.about_permissions_value))
                InfoRow(
                    stringResource(R.string.about_channels),
                    stringResource(
                        R.string.about_channels_value,
                        HashService.CHANNEL_PROGRESS,
                        HashService.CHANNEL_RESULT,
                    ),
                )
            }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/** 单选：一排等宽 chips（主题、语言都用它）。 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = {
                        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    warn: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = if (warn) colors.error else colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.scale(0.85f))
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(name, fontSize = 11.sp, color = colors.onSurfaceVariant)
        Text(value, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

private fun versionName(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName}（vc${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()}）"
}.getOrDefault("—")

/** Android 13+ 才需要运行时申请；低版本恒为"已授予"。 */
fun notificationPermissionGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

/** 是否已在"不受电池优化限制"白名单里（拿不到 PowerManager 时按"是"处理，避免误报）。 */
fun batteryUnrestricted(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(android.os.PowerManager::class.java)
    pm == null || pm.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(true)

/**
 * 拉起系统的"忽略电池优化"授权页。
 *
 * 为什么需要它：本机 ROM 在灭屏后会冻结 App——实测**连前台服务也一起冻**
 * （长任务跑一半停住、CPU 不再推进）。白名单是 App 侧能改变这个行为的开关里最标准的一个。
 */
fun requestBatteryUnrestricted(context: Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:" + context.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Diagnostics.recordError(context, "打开电池优化设置页失败", it.message ?: it.toString())
    }
}