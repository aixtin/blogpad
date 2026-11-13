package io.github.aixtin.blogpad

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 暂存在 App 私有目录里的一个附件。id 同时是正文占位符里的标识 */
data class InboxItem(
    val id: String,
    val fileName: String,
    val displayName: String,
    val kind: MediaKind,
    val size: Long
) {
    fun file(context: Context): File = File(AttachmentInbox.dir(context), fileName)
}

/**
 * 发布前的附件暂存区。
 *
 * 选完附件只把字节复制进私有目录，正文里留一个 blogpad-attachment://<id> 占位；
 * 点发布时统一上传、把占位换成站内路径，再写 md。
 *
 * 两点必须如此，不能省：
 *  - content:// 的读取权限是一次性的，不复制进私有目录，进程重启后原图就读不到了；
 *  - 边选边传会让「反复换一张图」在服务器上留下一串孤儿文件，暂存到发布才算账。
 */
object AttachmentInbox {

    const val SCHEME = "blogpad-attachment://"

    /** 单个附件上限。要整份读进内存再落盘，超过这个量级在低端机上容易 OOM */
    const val MAX_BYTES = 64L * 1024 * 1024

    private const val DIR_NAME = "inbox"
    private const val INDEX_NAME = "index.json"

    private val PLACEHOLDER =
        Regex(Regex.escape(SCHEME) + "([A-Za-z0-9]{1,64})")

    fun dir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun indexFile(context: Context) = File(dir(context), INDEX_NAME)

    /** 索引里登记过、且文件确实还在的项；文件被系统清掉时自动不算数 */
    fun list(context: Context): List<InboxItem> {
        val raw = runCatching { indexFile(context).readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = ArrayList<InboxItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val item = InboxItem(
                id = obj.optString("id"),
                fileName = obj.optString("file"),
                displayName = obj.optString("name"),
                kind = runCatching { MediaKind.valueOf(obj.optString("kind")) }
                    .getOrDefault(MediaKind.IMAGE),
                size = obj.optLong("size", 0L)
            )
            if (item.id.isEmpty() || item.fileName.isEmpty()) continue
            if (!item.file(context).exists()) continue
            items.add(item)
        }
        return items
    }

    fun find(context: Context, id: String?): InboxItem? {
        if (id.isNullOrBlank()) return null
        return list(context).firstOrNull { it.id == id }
    }

    /** 把内容复制进暂存区并登记。这一步之后 content:// 就算失效也不影响发布 */
    fun add(context: Context, uri: Uri, kind: MediaKind): InboxItem {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("读不到这个文件，可能是授权失效，重选一次试试")
        if (bytes.isEmpty()) throw IllegalStateException("这个文件是空的")
        if (bytes.size > MAX_BYTES) {
            throw IllegalStateException("文件超过 64MB，建议先压缩再传")
        }

        val name = displayName(resolver, uri)
        val ext = extensionOf(name, kind)
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val file = File(dir(context), "$id.$ext")
        file.writeBytes(bytes)

        val item = InboxItem(
            id = id,
            fileName = file.name,
            displayName = name.ifBlank { "附件.$ext" },
            kind = kind,
            size = bytes.size.toLong()
        )
        writeIndex(context, list(context) + item)
        return item
    }

    /** 发布成功后删掉已经上传的副本；失败时不要调，留着下次重试 */
    fun remove(context: Context, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val keep = ArrayList<InboxItem>()
        list(context).forEach { item ->
            if (ids.contains(item.id)) {
                runCatching { item.file(context).delete() }
            } else {
                keep.add(item)
            }
        }
        writeIndex(context, keep)
    }

    /** 正文里出现过的占位 id，可能重复，已去重 */
    fun referencedIds(body: String): List<String> {
        val out = LinkedHashSet<String>()
        PLACEHOLDER.findAll(body).forEach { out.add(it.groupValues[1]) }
        return out.toList()
    }

    /**
     * 清掉没被正文引用的暂存文件：选完又删掉那段占位的情况。
     * 返回删掉的个数，交给 UI 报一句。
     */
    fun cleanupUnused(context: Context, body: String): Int {
        val used = referencedIds(body).toHashSet()
        val drop = list(context).filter { !used.contains(it.id) }
        remove(context, drop.map { it.id })
        return drop.size
    }

    fun totalSize(context: Context): Long = list(context).sumOf { it.size }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun writeIndex(context: Context, items: List<InboxItem>) {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("file", item.fileName)
            obj.put("name", item.displayName)
            obj.put("kind", item.kind.name)
            obj.put("size", item.size)
            array.put(obj)
        }
        runCatching { indexFile(context).writeText(array.toString()) }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
            // 拿不到名字不影响暂存，扩展名会回落到按类型给的默认值
        }
        return uri.lastPathSegment.orEmpty()
    }

    /** 只认字母数字扩展名，最长 6 位；拿不到就按类型给个保守默认 */
    private fun extensionOf(displayName: String, kind: MediaKind): String {
        val fromName = displayName
            .substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(6)
        if (fromName.isNotEmpty()) return fromName
        return when (kind) {
            MediaKind.IMAGE, MediaKind.COVER -> "jpg"
            MediaKind.VIDEO -> "mp4"
            MediaKind.AUDIO -> "mp3"
        }
    }
}
