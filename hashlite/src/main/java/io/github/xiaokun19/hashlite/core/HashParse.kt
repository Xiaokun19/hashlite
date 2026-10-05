package io.github.xiaokun19.hashlite.core

import java.util.Locale

/** 十六进制、单位、耗时格式化，以及"粘贴的校验值"解析与比对。 */
object HashParse {

    private const val DIGITS = "0123456789abcdef"

    /** ≥32 位的十六进制串：MD5 / SHA 家族都能靠它认出。 */
    private val HEX_RUN = Regex("[0-9a-fA-F]{32,128}")

    /**
     * 8 位的十六进制串（CRC32 用）。必须左右都不是十六进制字符，
     * 避免把一段更长的串或文件名里的片段误认成校验值。
     * 且只有用户勾选了 CRC32 时才会启用（见 [extractHex] 的 allowShort）。
     */
    private val SHORT_HEX = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{8}(?![0-9a-fA-F])")

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 从粘贴内容里抠出校验值：容忍 0x 前缀、大小写、空格、`SHA256 (f) = xxx`、整行 sha256sum 输出。
     *
     * [allowShort] 为 true 时（即用户勾了 CRC32）才认 8 位短校验值。
     */
    fun extractHex(raw: String, allowShort: Boolean = false): String? {
        val cleaned = raw.trim().removePrefix("0x").removePrefix("0X")
        for (match in HEX_RUN.findAll(cleaned)) {
            val candidate = match.value.lowercase()
            if (LiteAlgorithm.byHexLength(candidate.length).isNotEmpty()) return candidate
        }
        if (allowShort) {
            for (match in SHORT_HEX.findAll(cleaned)) {
                val candidate = match.value.lowercase()
                if (LiteAlgorithm.byHexLength(candidate.length).isNotEmpty()) return candidate
            }
        }
        return null
    }

    fun candidates(hex: String): List<LiteAlgorithm> = LiteAlgorithm.byHexLength(hex.length)

    /** 显示用的大小写转换。内部一律按小写存储与比对，只在"给人看"的时候转换。 */
    fun display(hex: String, uppercase: Boolean): String =
        if (uppercase) hex.uppercase() else hex.lowercase()

    /**
     * 校验比对：**忽略大小写**，并且容忍粘贴内容里的前后缀、空格、文件名
     * （`SHA256 (f) = XXX`、`XXX  file.bin`、`0xXXX` 都能识别）。
     */
    fun matches(expectedRaw: String, actualHex: String, allowShort: Boolean = false): Boolean {
        val expected = extractHex(expectedRaw, allowShort) ?: return false
        return expected.equals(actualHex, ignoreCase = true)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100.0) {
            String.format(Locale.US, "%.0f %s", value, units[unit])
        } else {
            String.format(Locale.US, "%.2f %s", value, units[unit])
        }
    }

    fun formatSpeed(bytesPerSec: Double): String =
        if (bytesPerSec <= 0.0) "—" else formatBytes(bytesPerSec.toLong()) + "/s"

    fun formatDuration(nanos: Long): String = when {
        nanos <= 0L -> "—"
        nanos < 1_000_000L -> String.format(Locale.US, "%.0f µs", nanos / 1_000.0)
        nanos < 1_000_000_000L -> String.format(Locale.US, "%.0f ms", nanos / 1_000_000.0)
        else -> String.format(Locale.US, "%.2f s", nanos / 1_000_000_000.0)
    }

    fun formatEta(seconds: Double): String = when {
        seconds <= 0.0 -> ""
        seconds < 60 -> String.format(Locale.US, "约 %.0f 秒", seconds)
        else -> String.format(Locale.US, "约 %d 分 %02d 秒", (seconds / 60).toInt(), (seconds % 60).toInt())
    }
}