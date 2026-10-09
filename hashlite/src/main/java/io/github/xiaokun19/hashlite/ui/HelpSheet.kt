package io.github.xiaokun19.hashlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaokun19.hashlite.R

/**
 * 帮助页：从底部升起的说明面板。
 *
 * 内容分四层：为什么要用哈希 → 这些算法分别是什么 → 硬件加速是什么意思 →
 * 长任务与通知 → 本应用的取舍。速度数字全部取自本机实测（见仓库 README 的测量表），
 * 标注了“随频率档位浮动”，不写宣传口径。
 */
@Composable
fun HelpSheet(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = colors.surface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                    stringResource(R.string.help_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }

            // 关键：滚动区必须有"有界高度"（weight(1f)），否则 verticalScroll 的量算会跑偏。
            // navigationBarsPadding：三键导航栏出现时，底部文字不会被挡住。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section(stringResource(R.string.help_why_title))
                Body(stringResource(R.string.help_why_intro))
                Bullet(
                    stringResource(R.string.help_why_integrity_title),
                    stringResource(R.string.help_why_integrity_body),
                )
                Bullet(
                    stringResource(R.string.help_why_compare_title),
                    stringResource(R.string.help_why_compare_body),
                )
                Bullet(
                    stringResource(R.string.help_why_archive_title),
                    stringResource(R.string.help_why_archive_body),
                )
                Bullet(
                    stringResource(R.string.help_why_dedupe_title),
                    stringResource(R.string.help_why_dedupe_body),
                )

                Section(stringResource(R.string.help_algos_title))
                Body(stringResource(R.string.help_algos_intro))
                MetricRow("MD5", stringResource(R.string.unit_bits, "128"), stringResource(R.string.help_algo_md5_note))
                MetricRow("SHA-1", stringResource(R.string.unit_bits, "160"), stringResource(R.string.help_algo_sha1_note))
                MetricRow("SHA-256", stringResource(R.string.unit_bits, "256"), stringResource(R.string.help_algo_sha256_note))
                MetricRow(
                    "SHA-224 / 384 / 512",
                    stringResource(R.string.unit_bits, "224–512"),
                    stringResource(R.string.help_algo_sha2_note),
                )
                MetricRow(
                    "SHA3-256 / SHA3-512",
                    stringResource(R.string.unit_bits, "256 / 512"),
                    stringResource(R.string.help_algo_sha3_note),
                )
                MetricRow("SM3", stringResource(R.string.unit_bits, "256"), stringResource(R.string.help_algo_sm3_note))
                MetricRow("CRC32", stringResource(R.string.unit_bits, "32"), stringResource(R.string.help_algo_crc32_note))
                Body(stringResource(R.string.help_collision_body))

                Section(stringResource(R.string.help_hw_title))
                Body(stringResource(R.string.help_hw_intro))
                Body(stringResource(R.string.help_hw_bench_intro))
                MetricRow("SHA-256", "≈ 1.5–2.7 GB/s", stringResource(R.string.help_hw_note_native))
                MetricRow("SHA-1", "≈ 1.2–2.6 GB/s", stringResource(R.string.help_hw_note_native))
                MetricRow("SHA-512", "≈ 1.0–1.7 GB/s", stringResource(R.string.help_hw_note_native))
                MetricRow(
                    "SHA3-256",
                    stringResource(R.string.help_hw_sha3_value),
                    stringResource(R.string.help_hw_sha3_note),
                )
                Body(stringResource(R.string.help_hw_meaning))
                Body(stringResource(R.string.help_hw_not_accel))

                Section(stringResource(R.string.help_tasks_title))
                Body(stringResource(R.string.help_tasks_1))
                Body(stringResource(R.string.help_tasks_2))
                Body(stringResource(R.string.help_tasks_3))

                Section(stringResource(R.string.help_tradeoffs_title))
                Bullet(
                    stringResource(R.string.help_tradeoff_default_title),
                    stringResource(R.string.help_tradeoff_default_body),
                )
                Bullet(
                    stringResource(R.string.help_tradeoff_legacy_title),
                    stringResource(R.string.help_tradeoff_legacy_body),
                )
                Bullet(
                    stringResource(R.string.help_tradeoff_case_title),
                    stringResource(R.string.help_tradeoff_case_body),
                )
                Bullet(
                    stringResource(R.string.help_tradeoff_parallel_title),
                    stringResource(R.string.help_tradeoff_parallel_body),
                )

                Section(stringResource(R.string.help_project_title))
                Body(stringResource(R.string.help_project_body))
                Text(
                    "github.com/Xiaokun19/hashlite",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri("https://github.com/Xiaokun19/hashlite") }
                        .padding(vertical = 3.dp),
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.help_footer),
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(top = 14.dp)) {
        Box(
            Modifier
                .width(22.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Bullet(title: String, text: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Text("·", fontSize = 13.sp, color = colors.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
        }
    }
}

/**
 * 两行式条目：第一行“名称 + 右侧数值”，第二行说明占满整宽。
 *
 * 一开始写的是三列表格，但手机宽度下第三列会被挤出屏幕（说明文字又长）。
 */
@Composable
private fun MetricRow(name: String, value: String, note: String) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                name,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.primary)
        }
        Text(
            note,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurfaceVariant,
        )
    }
}