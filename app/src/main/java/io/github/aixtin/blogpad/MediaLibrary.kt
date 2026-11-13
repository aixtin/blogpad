package io.github.aixtin.blogpad

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 附件库里的一个远端文件 */
data class MediaFile(
    val remotePath: String,
    val urlPath: String,
    val size: Long,
    val modified: Long
)

/**
 * 一次扫描的结果。
 * refs 的键是站内 URL（形如 /upload/images/a.jpg），值是按 urlPath 引用它的文章（相对 content 的路径，去掉 .md）。
 */
data class MediaScan(
    val files: List<MediaFile>,
    val refs: Map<String, List<String>>,
    val postCount: Int
) {
    val orphans: List<MediaFile> get() = files.filter { refs[it.urlPath].isNullOrEmpty() }
    val totalBytes: Long get() = files.fold(0L) { sum, f -> sum + f.size }
    val orphanBytes: Long get() = orphans.fold(0L) { sum, f -> sum + f.size }

    fun refCount(file: MediaFile): Int = refs[file.urlPath]?.size ?: 0
    fun refPosts(file: MediaFile): List<String> = refs[file.urlPath].orEmpty()
}

/**
 * 附件库：只认"站点内的附件库"这一件事。
 *
 * 判断孤儿的口径是保守的——先把所有 md 的全文读回来，再用附件库 URL 前缀去全文里捞引用，
 * 宁可把某个文件误判成"被引用"（少删一次），也不把真在用的文件列成孤儿。
 * 因此扫描只负责"列出来"，是否删除始终由人确认。
 */
object MediaLibrary {

    private const val TRASH_DIR = ".blogpad-trash"
    private const val MARK_FILE = "@@@MEDIAFILE "
    private const val MARK_END = "@@@MEDIAEND"

    /** 绝对 URL 先剥掉协议和域名，只留路径，这样历史文章里的 https://站点/upload/x.jpg 也能被前缀匹配到 */
    private val ABSOLUTE_HOST = Regex("https?://[^/\\s\"'<>)]+")

    /** 扫描附件库并比对全站引用 */
    fun scan(config: Config): MediaScan {
        val siteRoot = config.siteRoot.trimEnd('/')
        if (siteRoot.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        config.mediaReady()?.let { throw PublishException(it) }

        val rootRel = config.mediaRoot.trim().trim('/').ifEmpty { Config.DEFAULT_MEDIA_ROOT }
        if (rootRel == "static") {
            throw PublishException("附件库不能是整个 static 目录，请在设置里指定一个子目录（如 static/upload）")
        }
        val urlPrefix = config.mediaUrlRoot()
        val mediaAbs = "$siteRoot/$rootRel"

        val session = SshPublisher.open(config)
        var sftp: ChannelSftp? = null
        try {
            val posts = readPosts(session, config)
            val pattern = Regex(Regex.escape(urlPrefix) + "/[^\\s()\\[\\]<>\"'`,;]+")
            val refs = LinkedHashMap<String, MutableList<String>>()
            posts.forEach { (slug, text) ->
                val flattened = text.replace(ABSOLUTE_HOST, "")
                pattern.findAll(flattened).forEach { match ->
                    val url = normalizeUrl(match.value)
                    if (url.length > urlPrefix.length + 1) {
                        val list = refs.getOrPut(url) { ArrayList() }
                        if (!list.contains(slug)) list.add(slug)
                    }
                }
            }

            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)
            val files = ArrayList<MediaFile>()
            walk(sftp, mediaAbs, urlPrefix, files)
            // 附件库按修改时间倒序（新的在上面），同一时间戳再按路径排，保证顺序稳定
            return MediaScan(
                files.sortedWith(
                    compareByDescending<MediaFile> { it.modified }
                        .thenBy { it.urlPath }
                ),
                refs,
                posts.size
            )
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    // ---------- 内部 ----------

    /** 一次 SSH 往返取回全站 md 的全文，正文里出现同名标记行会漏一篇文章，概率低到可以接受 */
    private fun readPosts(session: Session, config: Config): List<Pair<String, String>> {
        val dir = PostRepository.postDir(config)
        val script = "cd '" + SshPublisher.escape(dir) + "' 2>/dev/null && " +
            "find . -type f -name '*.md' | LC_ALL=C sort | while IFS= read -r f; do " +
            "printf '$MARK_FILE%s\\n' \"\$f\"; cat \"\$f\"; printf '\\n$MARK_END\\n'; done"
        val (code, out) = SshPublisher.exec(session, script)
        if (code != 0 && (out.contains("No such file") || out.isBlank())) return emptyList()

        val posts = ArrayList<Pair<String, String>>()
        var name: String? = null
        val body = StringBuilder()
        for (line in out.lineSequence()) {
            when {
                line.startsWith(MARK_FILE) -> {
                    name = line.removePrefix(MARK_FILE).trim()
                    body.setLength(0)
                }
                line.trim() == MARK_END -> {
                    val current = name
                    if (current != null) {
                        val slug = current.removePrefix("./").removeSuffix(".md")
                        posts.add(slug to body.toString())
                    }
                    name = null
                    body.setLength(0)
                }
                else -> if (name != null) body.append(line).append('\n')
            }
        }
        return posts
    }

    private fun walk(sftp: ChannelSftp, absDir: String, urlDir: String, out: MutableList<MediaFile>) {
        val entries = try { sftp.ls(absDir) } catch (_: Exception) { return }
        for (entry in entries) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val abs = "$absDir/$name"
            val url = "$urlDir/$name"
            if (entry.attrs.isDir) {
                walk(sftp, abs, url, out)
            } else {
                out.add(MediaFile(abs, url, entry.attrs.size, entry.attrs.mTime.toLong()))
            }
        }
    }

    /** 去掉 ?v=xxx、#锚点，并还原 %20 这类转义，保证和文件名的字面值对得上 */
    private fun normalizeUrl(raw: String): String {
        var url = raw.trim().trimEnd('.', ',', ';', ')', ']')
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) url = url.substring(0, cut)
        if (url.contains('%')) {
            url = try {
                URLDecoder.decode(url.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) {
                url
            }
        }
        return url
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date())

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun formatTime(epochSeconds: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return format.format(Date(epochSeconds * 1000))
    }
}
