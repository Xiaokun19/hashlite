package io.github.xiaokun19.hashlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaokun19.hashlite.Diagnostics
import io.github.xiaokun19.hashlite.R

/**
 * 主界面顶部的"有未查看的诊断日志"提示卡。
 *
 * 崩溃与错误报告都走这里：查看 / 直接分享 / 忽略（忽略=标记已看过，文件保留）。
 */
@Composable
fun DiagnosticsCard(
    entry: Diagnostics.Entry,
    onView: () -> Unit,
    onShare: () -> Unit,
    onIgnore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.10f)),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Text(
                "⚠ " + stringResource(
                    if (entry.kind == Diagnostics.Kind.CRASH) R.string.diag_card_crash_title else R.string.diag_card_error_title,
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.error,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.diag_card_sub, Diagnostics.displayTime(entry.timeMillis)),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onView) { Text(stringResource(R.string.diag_view), fontSize = 13.sp) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.diag_share), fontSize = 13.sp) }
                TextButton(onClick = onIgnore) { Text(stringResource(R.string.diag_ignore), fontSize = 13.sp) }
            }
        }
    }
}

/**
 * 诊断日志面板：多份时可切换（chips）+ 内容（等宽可选中）+ 分享 / 保存 / 复制 / 删除。
 *
 * 与帮助页 / 设置面板同一套容器语言；底部 navigationBarsPadding，三键导航栏不会遮挡操作区。
 */
@Composable
fun DiagnosticsSheet(
    entries: List<Diagnostics.Entry>,
    selected: Diagnostics.Entry?,
    text: String,
    copied: Boolean,
    saved: Boolean,
    onSelect: (Diagnostics.Entry) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf(false) }

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
                    stringResource(R.string.diag_sheet_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }

            // 操作行：分享 / 保存 / 复制 / 删除
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onShare) { Text(stringResource(R.string.diag_share), fontSize = 13.sp) }
                TextButton(onClick = onSave) {
                    Text(
                        stringResource(if (saved) R.string.state_saved else R.string.diag_save),
                        fontSize = 13.sp,
                    )
                }
                TextButton(onClick = onCopy) {
                    Text(
                        stringResource(if (copied) R.string.action_copied else R.string.diag_copy),
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.diag_delete), fontSize = 13.sp, color = colors.error)
                }
            }

            // 多份日志：横向 chips 切换
            if (entries.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.forEach { entry ->
                        FilterChip(
                            selected = entry.name == selected?.name,
                            onClick = { onSelect(entry) },
                            label = {
                                Text(
                                    kindLabel(entry.kind) + " " + Diagnostics.displayTime(entry.timeMillis),
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }

            // 内容
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
            ) {
                SelectionContainer {
                    Text(
                        text,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.diag_delete)) },
            text = { Text(stringResource(R.string.diag_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.diag_delete), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun kindLabel(kind: Diagnostics.Kind): String =
    stringResource(if (kind == Diagnostics.Kind.CRASH) R.string.diag_kind_crash else R.string.diag_kind_error)