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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaokun19.hashlite.HashService
import io.github.xiaokun19.hashlite.core.LiteAlgorithm

/**
 * 设置面板（自底升起，和帮助页同一套容器语言）。
 *
 * 三个开关都围绕"长任务体验"：屏幕常亮 / 常驻通知（前台服务）/ 完成通知。
 */
@Composable
fun SettingsSheet(
    keepScreenOn: Boolean,
    notifyProgress: Boolean,
    notifyResult: Boolean,
    notifyPermissionGranted: Boolean,
    batteryUnrestricted: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onNotifyProgress: (Boolean) -> Unit,
    onNotifyResult: (Boolean) -> Unit,
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
                Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionTitle("计算体验")

                SwitchRow(
                    title = "计算时保持屏幕常亮",
                    subtitle = "不用任何权限；灭屏后系统会把 CPU 压频（实测 SHA3 会从 1.0 GB/s 掉到 0.25 GB/s）",
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOn,
                )

                SwitchRow(
                    title = "常驻通知（进度 / 取消）",
                    subtitle = buildString {
                        append("计算期间挂前台服务：防止被系统冻结、掉频，也能一键取消")
                        if (!notifyPermissionGranted) append("\n⚠ 系统通知权限未授予：抽屉里看不到这条通知（服务本身仍会运行）")
                    },
                    checked = notifyProgress,
                    onCheckedChange = onNotifyProgress,
                    warn = !notifyPermissionGranted && notifyProgress,
                )

                SwitchRow(
                    title = "完成后通知",
                    subtitle = "成功 / 校验不通过 / 失败时提醒一次，可点开回到 App",
                    checked = notifyResult,
                    onCheckedChange = onNotifyResult,
                    warn = !notifyPermissionGranted && notifyResult,
                )

                // 电池优化白名单：灭屏冻结的正解之一（本机 ROM 连前台服务都会冻）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("不受电池优化限制", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (batteryUnrestricted) {
                                "已加入白名单：灭屏 / 后台不会被系统冻结"
                            } else {
                                "未加入：本机 ROM 灭屏后会冻结 App（实测连前台服务也会被冻住，" +
                                    "长任务会停在半路）——建议加入白名单"
                            },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = if (batteryUnrestricted) colors.onSurfaceVariant else colors.error,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (batteryUnrestricted) {
                        Text("✓", fontSize = 15.sp, color = colors.primary)
                    } else {
                        TextButton(onClick = onRequestUnrestricted) { Text("去设置", fontSize = 13.sp) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionTitle("关于")

                InfoRow("版本", versionName(context))
                InfoRow("包名", context.packageName)
                InfoRow("SHA3 实现", LiteAlgorithm.SHA3_256.implementation)
                InfoRow("权限", "不申请存储权限；文件通过 SAF 只读访问")
                InfoRow("通知渠道", "${HashService.CHANNEL_PROGRESS}（进度·静音） / ${HashService.CHANNEL_RESULT}（提醒）")
            }
        }
    }
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
 * （长任务跑一半停住、CPU 不再推进）。白名单是唯一能改变这个行为的开关。
 */
fun requestBatteryUnrestricted(context: Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:" + context.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}