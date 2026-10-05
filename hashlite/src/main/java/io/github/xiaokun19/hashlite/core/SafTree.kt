package io.github.xiaokun19.hashlite.core

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * SAF 目录树（`ACTION_OPEN_DOCUMENT_TREE`）的遍历与读写。
 *
 * 只读、不申请任何存储权限——目录树授权本身就够用；`takePersistable` 之后重启也还在。
 */
object SafTree {

    /** 目录里的一个文件。[name] 是相对目录根的路径（`sub/a.bin`）。 */
    data class Doc(val uri: Uri, val name: String, val size: Long)

    /** 一次最多列这么多文件：用户误选整个内部存储时不能把内存吃光。 */
    const val MAX_FILES = 4000

    fun takePersistablePermission(resolver: ContentResolver, treeUri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** 递归列出目录树下所有文件（跳过目录本身），按相对路径排序。 */
    fun listFiles(resolver: ContentResolver, treeUri: Uri, maxFiles: Int = MAX_FILES): List<Doc> {
        val out = ArrayList<Doc>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (rootId == null) {
            // 少数 provider 不给 tree document id：退化为把树 URI 本身当文档试一次
            return out
        }
        walk(resolver, treeUri, rootId, "", out, maxFiles)
        out.sortBy { it.name.lowercase(Locale.US) }
        return out
    }

    private fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        parentId: String,
        prefix: String,
        out: MutableList<Doc>,
        maxFiles: Int,
    ) {
        if (out.size >= maxFiles) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (t: Throwable) {
            null
        } ?: return

        cursor.use { c ->
            while (c.moveToNext() && out.size < maxFiles) {
                val id = c.stringOrNull(0) ?: continue
                val displayName = c.stringOrNull(1) ?: continue
                val mime = c.stringOrNull(2)
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                val relative = if (prefix.isEmpty()) displayName else "$prefix/$displayName"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(resolver, treeUri, id, relative, out, maxFiles)
                } else {
                    out.add(Doc(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), relative, size))
                }
            }
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else runCatching { getString(index) }.getOrNull()

    /** 目录根自身的显示名（`Download` / `我的文件`…）。 */
    fun rootName(resolver: ContentResolver, treeUri: Uri): String {
        runCatching {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            return queryName(resolver, uri) ?: id.substringAfterLast(':')
        }
        return treeUri.lastPathSegment ?: "已选目录"
    }

    fun queryName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.stringOrNull(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    /** 读文本（校验文件）。超过 [maxBytes] 就截断——正常清单不会那么大。 */
    @Throws(IOException::class)
    fun readText(resolver: ContentResolver, uri: Uri, maxBytes: Int = 8 * 1024 * 1024): String {
        val input: InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开清单文件")
        input.use { stream ->
            val out = ByteArrayOutputStream(16 * 1024)
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (total < maxBytes) {
                val n = stream.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return out.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
        }
    }

    /** 写文本（导出清单）。优先 `wt`（截断写），provider 不支持时退回 `w`。 */
    fun writeText(resolver: ContentResolver, uri: Uri, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (mode in listOf("wt", "w")) {
            val ok = runCatching {
                resolver.openOutputStream(uri, mode)?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                    true
                } ?: false
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}

/**
 * 批量模式下的 SAF 文件。
 *
 * 每次 [open] 都重新 `openFileDescriptor`：文件描述符只在该文件被算的时候短暂持有，
 * 4000 个文件的目录也不会把 fd 用光。
 */
class AndroidBatchFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val expected: String? = null,
    override val expectedAlgorithm: LiteAlgorithm? = null,
) : BatchFile {

    override fun open(): HashSource {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开 $name")
        return AndroidFileSource(pfd, name, size)
    }

    companion object {
        fun fromDoc(
            resolver: ContentResolver,
            doc: SafTree.Doc,
            expected: String? = null,
            expectedAlgorithm: LiteAlgorithm? = null,
        ): AndroidBatchFile = AndroidBatchFile(
            resolver = resolver,
            uri = doc.uri,
            name = doc.name,
            size = doc.size,
            expected = expected,
            expectedAlgorithm = expectedAlgorithm,
        )
    }
}