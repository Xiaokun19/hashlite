package io.github.xiaokun19.hashlite.core

import java.util.Locale

/** 校验文件的三种主流格式。 */
enum class ChecksumFormat(val displayName: String) {
    /** GNU coreutils：`<hex>  <文件名>`（`*` 前缀 = 二进制模式）。sha256sum / md5sum 的输出。 */
    COREUTILS("coreutils"),

    /** BSD / macOS：`SHA256 (文件名) = <hex>`。 */
    BSD("BSD"),

    /** Simple File Verification：`<文件名> <8位CRC32>`，`;` 开头为注释。 */
    SFV("SFV"),
}

/** 校验文件里的一行 = 一个期望值。 */
data class ChecksumEntry(
    /** 一律小写十六进制。 */
    val hash: String,
    /** 清单里写的文件名，可能是相对路径（`sub/dir/file.bin`）。 */
    val name: String,
    /** 由行格式或长度推断出的算法；认不出来时为 null（仍然保留，便于报"这行看不懂"）。 */
    val algorithm: LiteAlgorithm?,
    val lineNumber: Int,
    val raw: String,
)

/**
 * 一份解析完成的校验文件。
 *
 * 匹配一律走 [normalize]（去 `./`、反斜杠转正斜杠、小写），所以：
 * - 清单写完整路径而目录里只有文件名（或反过来）都能对上；
 * - Windows 写出的 `dir\file.bin` 也能对上。
 */
class ChecksumList(
    val format: ChecksumFormat,
    val entries: List<ChecksumEntry>,
    /** 解析不了的行（最多留 20 条，够界面提示了）。 */
    val badLines: List<String> = emptyList(),
) {
    /** 整份清单统一的算法；不一致或认不出来就是 null。 */
    val algorithm: LiteAlgorithm? get() = entries.mapNotNull { it.algorithm }.distinct().singleOrNull()

    /** 有没有"认不出算法"的条目（界面提示用）。 */
    val hasUnknownAlgorithm: Boolean get() = entries.any { it.algorithm == null }

    private val byPath: Map<String, ChecksumEntry> by lazy { entries.associateBy { normalize(it.name) } }
    private val byBase: Map<String, ChecksumEntry> by lazy {
        val map = HashMap<String, ChecksumEntry>(entries.size)
        for (entry in entries) map.putIfAbsent(normalize(entry.name).substringAfterLast('/'), entry)
        map
    }

    /** 按名字找期望值：先按完整路径，再退化为按文件名。 */
    fun lookup(name: String): ChecksumEntry? {
        val key = normalize(name)
        byPath[key]?.let { return it }
        return byBase[key.substringAfterLast('/')]
    }

    /** 清单里有、但文件集合里找不到的条目（= 缺失的文件）。 */
    fun missing(files: Collection<String>): List<ChecksumEntry> {
        if (entries.isEmpty()) return emptyList()
        val paths = files.mapTo(HashSet()) { normalize(it) }
        val bases = files.mapTo(HashSet()) { normalize(it).substringAfterLast('/') }
        return entries.filter { entry ->
            val key = normalize(entry.name)
            key !in paths && key.substringAfterLast('/') !in bases
        }
    }

    companion object {
        fun normalize(name: String): String = name.trim()
            .removePrefix("./")
            .replace('\\', '/')
            .lowercase()
    }
}

/**
 * 校验文件的解析与生成。
 *
 * 设计原则：**尽量解析、不整份作废**。看不懂的行进 [ChecksumList.badLines]，
 * 让界面告诉用户"这几行没看懂"，而不是把整份清单判为无效。
 */
object ChecksumFile {

    /** 按"最可能"排序的算法偏好：同一长度的候选（64 位十六进制 = SHA-256 / SHA3-256 / SM3）取第一个。 */
    private val PREFERENCE = listOf(
        LiteAlgorithm.MD5,
        LiteAlgorithm.SHA1,
        LiteAlgorithm.SHA256,
        LiteAlgorithm.SHA512,
        LiteAlgorithm.SHA224,
        LiteAlgorithm.SHA384,
        LiteAlgorithm.SHA3_256,
        LiteAlgorithm.SHA3_512,
        LiteAlgorithm.SM3,
        LiteAlgorithm.CRC32,
    )

    /** `SHA256 (file.bin) = hex`（也容忍 `MD5(f)=hex` 与带引号的文件名）。 */
    private val BSD_LINE = Regex(
        """^\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*\(\s*"?([^")]*?)"?\s*\)\s*=\s*([0-9a-fA-F]{8,128})\s*$""",
    )

    /** `<文件名> <8位CRC32>`（名字在前、校验值在后；尾部允许 `;注释`）。 */
    private val SFV_LINE = Regex("""^\s*"?(.+?)"?[ \t]+([0-9a-fA-F]{8})[ \t]*(?:;.*)?$""")

    // ---------------------------------------------------------------- 尺寸辅助

    /** 8 位十六进制以上的串才可能是校验值（CRC32 正好 8 位）。 */
    private const val MIN_HEX = 8
    private const val MAX_HEX = 128

    // ---------------------------------------------------------------- 解析

    /**
     * 解析一份校验文件。[fileNameHint] 传清单自己的文件名，用于消歧
     * （`.sfv` → 优先按 SFV 解析；`foo.sha3-256` → 64 位十六进制优先认成 SHA3-256 而不是 SHA-256）。
     */
    fun parse(text: String, fileNameHint: String? = null): ChecksumList {
        val hintAlgorithm = fileNameHint?.let { algorithmFromFileName(it) }
        val hintFormat = fileNameHint?.let { formatFromFileName(it) }
        val entries = ArrayList<ChecksumEntry>()
        val bad = ArrayList<String>()
        val formatVotes = HashMap<ChecksumFormat, Int>()

        text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
            val parsed = parseLine(line, hintAlgorithm, hintFormat)
            if (parsed == null) {
                if (bad.size < 20) bad.add(line.take(120))
            } else {
                formatVotes[parsed.format] = (formatVotes[parsed.format] ?: 0) + 1
                entries.add(
                    ChecksumEntry(
                        hash = parsed.hash,
                        name = parsed.name,
                        algorithm = parsed.algorithm,
                        lineNumber = index + 1,
                        raw = line,
                    ),
                )
            }
        }

        val format = formatVotes.maxByOrNull { it.value }?.key
            ?: hintFormat
            ?: ChecksumFormat.COREUTILS
        return ChecksumList(format, entries, bad)
    }

    private data class Parsed(
        val hash: String,
        val name: String,
        val algorithm: LiteAlgorithm?,
        val format: ChecksumFormat,
    )

    private fun parseLine(
        line: String,
        hintAlgorithm: LiteAlgorithm?,
        hintFormat: ChecksumFormat?,
    ): Parsed? {
        // GNU 逃逸行：文件名里有换行/反斜杠时，coreutils 会在行首加 '\'
        if (line.startsWith("\\")) {
            coreutilsParts(line.substring(1))?.let { (hash, name) ->
                return Parsed(hash, unescapeName(name), inferAlgorithm(hash, null, hintAlgorithm), ChecksumFormat.COREUTILS)
            }
        }

        BSD_LINE.find(line)?.let { match ->
            val name = match.groupValues[2].trim()
            if (name.isNotEmpty()) {
                return Parsed(
                    hash = match.groupValues[3].lowercase(Locale.US),
                    name = name,
                    algorithm = algorithmFromLabel(match.groupValues[1]) ?: inferAlgorithm(match.groupValues[3], null, hintAlgorithm),
                    format = ChecksumFormat.BSD,
                )
            }
        }

        // 清单扩展名是 .sfv：先按"名字在前"解释，避免 CRC32 行被误当成路径
        if (hintFormat == ChecksumFormat.SFV) {
            SFV_LINE.find(line)?.let { match ->
                val name = match.groupValues[1].trim()
                if (name.isNotEmpty()) {
                    return Parsed(match.groupValues[2].lowercase(Locale.US), name, LiteAlgorithm.CRC32, ChecksumFormat.SFV)
                }
            }
        }

        // coreutils：校验值在前
        coreutilsParts(line)?.let { (hash, name) ->
            return Parsed(hash, name, inferAlgorithm(hash, null, hintAlgorithm), ChecksumFormat.COREUTILS)
        }

        // 兜底：名字在前、8 位校验值在后
        SFV_LINE.find(line)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty() && !looksLikeHash(name)) {
                return Parsed(match.groupValues[2].lowercase(Locale.US), name, LiteAlgorithm.CRC32, ChecksumFormat.SFV)
            }
        }

        return null
    }

    /** 手写而不是正则：`<hex><空白><可选 *><名字>`，避免正则把文件名里的空格吃掉。 */
    private fun coreutilsParts(line: String): Pair<String, String>? {
        var i = 0
        while (i < line.length && isHexDigit(line[i])) i++
        if (i < MIN_HEX || i > MAX_HEX) return null
        val hash = line.substring(0, i).lowercase(Locale.US)
        var j = i
        while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
        if (j == i) return null // 校验值后面必须至少有一个空白
        if (j < line.length && line[j] == '*') j++ // 二进制模式标记
        val name = line.substring(j).trimEnd()
        if (name.isEmpty()) return null
        return hash to name
    }

    private fun looksLikeHash(text: String): Boolean =
        text.length in MIN_HEX..MAX_HEX && text.all { isHexDigit(it) }

    private fun isHexDigit(c: Char): Boolean =
        (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

    private fun inferAlgorithm(hash: String, label: String?, hint: LiteAlgorithm?): LiteAlgorithm? {
        label?.let { algorithmFromLabel(it) }?.let { return it }
        val candidates = LiteAlgorithm.byHexLength(hash.length)
        if (hint != null && hint in candidates) return hint
        return PREFERENCE.firstOrNull { it in candidates }
    }

    /** `SHA256` / `sha-1` / `SHA3-256` / `MD5SUM` 都能认。 */
    fun algorithmFromLabel(label: String): LiteAlgorithm? {
        var key = label.lowercase(Locale.US).filter { it.isLetterOrDigit() }
        if (key.endsWith("sum")) key = key.dropLast(3)
        return when (key) {
            "md5" -> LiteAlgorithm.MD5
            "sha1", "sha" -> LiteAlgorithm.SHA1
            "sha224" -> LiteAlgorithm.SHA224
            "sha256" -> LiteAlgorithm.SHA256
            "sha384" -> LiteAlgorithm.SHA384
            "sha512" -> LiteAlgorithm.SHA512
            "sha3256" -> LiteAlgorithm.SHA3_256
            "sha3512" -> LiteAlgorithm.SHA3_512
            "sm3" -> LiteAlgorithm.SM3
            "crc32", "sfv" -> LiteAlgorithm.CRC32
            else -> null
        }
    }

    /** 从清单文件名猜算法：`foo.sha256`、`SHA256SUMS`、`checksums.sha3-256.txt` 都能认。 */
    fun algorithmFromFileName(name: String): LiteAlgorithm? {
        val lower = name.lowercase(Locale.US)
        algorithmFromLabel(lower.substringAfterLast('.', ""))?.let { return it }
        return KEYWORDS.firstOrNull { lower.contains(it.first) }?.second
    }

    /** 长关键词必须排前面：`sha3-256` 要在 `sha256` 之前判定。 */
    private val KEYWORDS = listOf(
        "sha3-256" to LiteAlgorithm.SHA3_256,
        "sha3_256" to LiteAlgorithm.SHA3_256,
        "sha3256" to LiteAlgorithm.SHA3_256,
        "sha3-512" to LiteAlgorithm.SHA3_512,
        "sha3_512" to LiteAlgorithm.SHA3_512,
        "sha3512" to LiteAlgorithm.SHA3_512,
        "sha512" to LiteAlgorithm.SHA512,
        "sha384" to LiteAlgorithm.SHA384,
        "sha256" to LiteAlgorithm.SHA256,
        "sha224" to LiteAlgorithm.SHA224,
        "sha1" to LiteAlgorithm.SHA1,
        "md5" to LiteAlgorithm.MD5,
        "sm3" to LiteAlgorithm.SM3,
        "crc32" to LiteAlgorithm.CRC32,
        "sfv" to LiteAlgorithm.CRC32,
    )

    /** 由扩展名/命名惯例猜格式（只用来消歧，真正的格式以解析结果投票为准）。 */
    fun formatFromFileName(name: String): ChecksumFormat? {
        val lower = name.lowercase(Locale.US)
        return when (lower.substringAfterLast('.', "")) {
            "sfv" -> ChecksumFormat.SFV
            "md5", "sha1", "sha256", "sha512", "sha224", "sha384", "sha3-256", "sha3-512", "sm3",
            "txt", "sum", "sums", "checksum", "checksums",
            -> ChecksumFormat.COREUTILS

            "" -> when {
                lower.endsWith("sums") || lower.endsWith("sum") -> ChecksumFormat.COREUTILS
                else -> null
            }

            else -> null
        }
    }

    // ---------------------------------------------------------------- 生成

    /** 导出时的一行（名字 + 已算好的十六进制）。 */
    data class ChecksumLine(val name: String, val hex: String)

    /**
     * 生成一份校验文件的文本（行尾统一 `\n`）。
     *
     * - COREUTILS：`<小写hex>  <名字>`
     * - BSD：`SHA256 (<名字>) = <小写hex>`
     * - SFV：`<名字> <大写CRC32>`，文件头带一行 `;` 注释
     */
    fun build(lines: List<ChecksumLine>, algorithm: LiteAlgorithm, format: ChecksumFormat): String {
        val sb = StringBuilder(lines.size * (algorithm.hexChars + 24))
        when (format) {
            ChecksumFormat.COREUTILS -> for (line in lines) {
                val escaped = escapeName(line.name)
                if (escaped != line.name) sb.append('\\')
                sb.append(line.hex.lowercase(Locale.US)).append("  ").append(escaped).append('\n')
            }

            ChecksumFormat.BSD -> for (line in lines) {
                sb.append(bsdLabel(algorithm)).append(" (").append(line.name).append(") = ")
                    .append(line.hex.lowercase(Locale.US)).append('\n')
            }

            ChecksumFormat.SFV -> {
                sb.append("; SFV checksum file\n")
                for (line in lines) {
                    sb.append(line.name).append(' ').append(line.hex.uppercase(Locale.US)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** BSD 惯例：SHA-1 写成 `SHA1`，SHA-3 保留连字符。 */
    private fun bsdLabel(algorithm: LiteAlgorithm): String = when (algorithm) {
        LiteAlgorithm.SHA3_256 -> "SHA3-256"
        LiteAlgorithm.SHA3_512 -> "SHA3-512"
        else -> algorithm.label.replace("-", "")
    }

    /** coreutils 的逃逸规则：文件名含换行/反斜杠时转义，并在行首补 `\`（由 [build] 负责补）。 */
    private fun escapeName(name: String): String {
        if (name.indexOf('\n') < 0 && name.indexOf('\r') < 0 && name.indexOf('\\') < 0) return name
        return name.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun unescapeName(name: String): String {
        if (name.indexOf('\\') < 0) return name
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '\\' && i + 1 < name.length) {
                when (val next = name[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 导出时的默认文件名：`[基准名].sha256` / `[基准名].sfv`；没有基准名就用 `checksums.<ext>`。 */
    fun suggestFileName(base: String?, algorithm: LiteAlgorithm, format: ChecksumFormat): String {
        val ext = if (format == ChecksumFormat.SFV) "sfv" else extensionFor(algorithm)
        val stem = base?.trim()?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return if (stem == null) "checksums.$ext" else "$stem.$ext"
    }

    fun extensionFor(algorithm: LiteAlgorithm): String = when (algorithm) {
        LiteAlgorithm.MD5 -> "md5"
        LiteAlgorithm.SHA1 -> "sha1"
        LiteAlgorithm.SHA224 -> "sha224"
        LiteAlgorithm.SHA256 -> "sha256"
        LiteAlgorithm.SHA384 -> "sha384"
        LiteAlgorithm.SHA512 -> "sha512"
        LiteAlgorithm.SHA3_256 -> "sha3-256"
        LiteAlgorithm.SHA3_512 -> "sha3-512"
        LiteAlgorithm.SM3 -> "sm3"
        LiteAlgorithm.CRC32 -> "sfv"
    }
}
