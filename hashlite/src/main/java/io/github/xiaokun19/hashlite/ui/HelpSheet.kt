package io.github.xiaokun19.hashlite.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 帮助页：从底部升起的说明面板。
 *
 * 内容分三层：为什么要用哈希 → 这几个算法是什么 → 硬件加速到底意味着什么。
 * 速度数字全部取自本机实测（见仓库 README 的测量表），不写宣传口径。
 */
@Composable
fun HelpSheet(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
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
                Text("帮助", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }

            // 关键：滚动区必须有"有界高度"（weight(1f)），否则 verticalScroll 的量算会跑偏。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section("哈希是干什么的")
                Body("哈希就是把任意大小的文件压成一串固定长度的“指纹”。同一个文件永远算出同一个值；" +
                    "改动一个字节，指纹就面目全非；反过来，拿到指纹也推不出文件内容。")
                Bullet("校验完整性", "下载、拷贝、备份之后，和发布方给的指纹比对一下，就能确认文件没传坏、没被篡改。")
                Bullet("判断是否相同", "两台设备上不用搬运文件，比指纹就知道是不是同一份内容。")
                Bullet("留档与取证", "记下某时刻的指纹，日后可以证明这份文件“没变过”。")
                Bullet("去重", "把指纹当文件的身份证，重复内容一眼就能认出来。")

                Section("这几个算法分别是什么")
                Body("它们都是公开的标准算法，安全性来自“单向”和“抗碰撞”，而不是保密。")
                MetricRow("MD5", "128 位", "老算法、很快；但已被证明能人为构造碰撞，只用来兼容旧校验值")
                MetricRow("SHA-1", "160 位", "同样已被实际攻破（2017 年 SHAttered），新场景不要用")
                MetricRow("SHA-256", "256 位", "目前最通用、最稳妥的默认选择")
                MetricRow("SHA-224 / 384 / 512", "224–512 位", "SHA-2 家族的不同档位；512 内部用 64 位字，结构不同")
                Body("所谓“碰撞”，就是两个不同的文件算出同一个指纹。能做碰撞不等于能反推文件内容，" +
                    "但对“防篡改”这个用途来说，已经足够致命。")

                Section("硬件加速是什么意思")
                Body("现代 CPU（ARM）里专门加了几条“算哈希”的指令。本机检测到 SHA-1 / SHA-2 的指令扩展，" +
                    "系统自带的加密库会在运行时自动切到指令实现，而不是用普通指令一条条去算。")
                Body("本机实测（内存基准，已剔除磁盘因素）：")
                MetricRow("SHA-256", "≈ 2.7 GB/s", "硬件指令")
                MetricRow("SHA-1", "≈ 2.6 GB/s", "硬件指令")
                MetricRow("SHA-512", "≈ 1.7 GB/s", "硬件指令")
                MetricRow("SHA3-256", "≈ 0.28 GB/s", "无指令、纯软件（本应用未收录）")
                Body("意义：1 GB 文件用 SHA-256 大约 0.4 秒；如果只能走纯软件实现，会慢一个数量级——" +
                    "大文件校验和批量校验基本就不可行了。指令路径还更省电、发热更少。")
                Body("哪些没有加速：芯片厂商只给最常用的算法做指令。SHA-3 / Keccak 在 Android 平台上" +
                    "连库都没有，谈不上加速，所以本应用只带上面这些算法——它们全都能吃到硬件加速。")

                Section("本应用的几个取舍")
                Bullet("默认只勾 SHA-256", "够用，而且是最快的一档。")
                Bullet("保留 MD5 / SHA-1", "唯一理由是老网站、老文档只提供它们的值——要能比对，就得算得出来。")
                Bullet("校验不区分大小写", "十六进制 A–F 的大小写只是书写习惯，同一个字节的两种写法，比对时会统一处理。")

                Spacer(Modifier.height(4.dp))
                Text(
                    "以上速度数据来自本机实测（型号相关）；换机器会不同，但“硬件指令 ≫ 纯软件”的结论不变。",
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
