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

/** 回收站里的一个文件：relPath 是相对站点根的原始位置，还原时按它搬回去 */
data class TrashFile(
    val remotePath: String,
    val relPath: String,
    val batch: String,
    val size: Long
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
 * 附件管家：只认"站点内的附件库"这一件事。
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
            return MediaScan(files.sortedBy { it.urlPath }, refs, posts.size)
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 把选中的文件搬进回收站（站点根下的 .blogpad-trash/批次/原路径），返回成功搬走的数量 */
    fun moveToTrash(config: Config, targets: List<MediaFile>): Int {
        val siteRoot = config.siteRoot.trimEnd('/')
        if (siteRoot.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        if (targets.isEmpty()) return 0
        val base = "$siteRoot/$TRASH_DIR/${timestamp()}"
        val session = SshPublisher.open(config)
        var moved = 0
        try {
            for (file in targets) {
                val prefix = "$siteRoot/"
                if (!file.remotePath.startsWith(prefix)) continue
                val rel = file.remotePath.removePrefix(prefix)
                // 只处理站点内的正常相对路径，任何 .. 一律不碰
                if (rel.isBlank() || rel.split('/').any { it.isEmpty() || it == ".." || it == "." }) continue
                val dest = "$base/$rel"
                val command = "mkdir -p '" + SshPublisher.escape(dest.substringBeforeLast('/')) + "' && " +
                    "mv -f '" + SshPublisher.escape(file.remotePath) + "' '" +
                    SshPublisher.escape(dest) + "' && echo BLOGPAD_MV_OK"
                val (code, out) = SshPublisher.exec(session, command)
                if (code == 0 && out.contains("BLOGPAD_MV_OK")) moved++
            }
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
        return moved
    }

    /** 列出回收站里的文件，最近一批排前面 */
    fun listTrash(config: Config): List<TrashFile> {
        val siteRoot = config.siteRoot.trimEnd('/')
        if (siteRoot.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        val root = "$siteRoot/$TRASH_DIR"
        val session = SshPublisher.open(config)
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)
            val out = ArrayList<TrashFile>()
            walkTrash(sftp, root, "", out)
            return out.sortedWith(compareByDescending<TrashFile> { it.batch }.thenBy { it.relPath })
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 从回收站搬回原位，返回成功还原的数量 */
    fun restore(config: Config, files: List<TrashFile>): Int {
        val siteRoot = config.siteRoot.trimEnd('/')
        if (siteRoot.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        if (files.isEmpty()) return 0
        val session = SshPublisher.open(config)
        var restored = 0
        try {
            for (file in files) {
                val rel = file.relPath
                if (rel.isBlank() || rel.split('/').any { it.isEmpty() || it == ".." || it == "." }) continue
                val dest = "$siteRoot/$rel"
                val command = "mkdir -p '" + SshPublisher.escape(dest.substringBeforeLast('/')) + "' && " +
                    "mv -f '" + SshPublisher.escape(file.remotePath) + "' '" +
                    SshPublisher.escape(dest) + "' && echo BLOGPAD_MV_OK"
                val (code, out) = SshPublisher.exec(session, command)
                if (code == 0 && out.contains("BLOGPAD_MV_OK")) restored++
            }
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
        return restored
    }

    /** 彻底删除回收站里的选中项，返回删除数量 */
    fun purge(config: Config, files: List<TrashFile>): Int {
        if (files.isEmpty()) return 0
        val siteRoot = config.siteRoot.trimEnd('/')
        val trashRoot = "$siteRoot/$TRASH_DIR/"
        val session = SshPublisher.open(config)
        var sftp: ChannelSftp? = null
        var removed = 0
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)
            for (file in files) {
                // 兜底：只允许删回收站里的路径
                if (!file.remotePath.startsWith(trashRoot)) continue
                if (file.remotePath.split('/').any { it == ".." }) continue
                try {
                    sftp.rm(file.remotePath)
                    removed++
                } catch (_: Exception) {
                    // 单个失败不打断整批，最终以返回数量为准
                }
            }
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
        return removed
    }

    /** 清空整个回收站 */
    fun purgeAll(config: Config): Boolean {
        val siteRoot = config.siteRoot.trimEnd('/')
        if (siteRoot.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        if (!siteRoot.startsWith("/")) throw PublishException("站点根目录必须是绝对路径")
        val trashRoot = "$siteRoot/$TRASH_DIR"
        if (!trashRoot.endsWith("/$TRASH_DIR")) throw PublishException("回收站路径校验失败，已取消")
        val session = SshPublisher.open(config)
        try {
            val (code, out) = SshPublisher.exec(session, "rm -rf '" + SshPublisher.escape(trashRoot) + "' && echo BLOGPAD_RM_OK")
            return code == 0 && out.contains("BLOGPAD_RM_OK")
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
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

    private fun walkTrash(sftp: ChannelSftp, absDir: String, relDir: String, out: MutableList<TrashFile>) {
        val entries = try { sftp.ls(absDir) } catch (_: Exception) { return }
        for (entry in entries) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val abs = "$absDir/$name"
            val rel = if (relDir.isEmpty()) name else "$relDir/$name"
            if (entry.attrs.isDir) {
                walkTrash(sftp, abs, rel, out)
            } else {
                // 结构是 .blogpad-trash/批次/原相对路径
                val slash = rel.indexOf('/')
                if (slash <= 0) continue
                out.add(TrashFile(abs, rel.substring(slash + 1), rel.substring(0, slash), entry.attrs.size))
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
