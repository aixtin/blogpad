package io.github.aixtin.blogpad

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 回收站的两份目录：附件一份、文章一份，各扫各自的，互不可见 */
enum class TrashKind(val dir: String) {
    MEDIA("media"),
    POSTS("posts")
}

/** 回收站里的一个文件：relPath 是相对站点根的原始位置，还原时按它搬回去 */
data class TrashFile(
    val remotePath: String,
    val relPath: String,
    val batch: String,
    val size: Long,
    val kind: TrashKind = TrashKind.MEDIA,
    val title: String = ""
) {
    val fileName: String get() = relPath.substringAfterLast('/')
}

/** 文章回收站里的一个批次：一篇文章 + 当时跟着它一起下线的独占附件 */
data class TrashPost(
    val remotePath: String,
    val relPath: String,
    val batch: String,
    val title: String,
    val attachmentCount: Int,
    val size: Long,
    val time: String
)

data class DeletePostResult(val title: String, val batch: String, val attachments: Int)

data class RestoreResult(val restored: Int, val conflicts: List<String>, val buildOutput: String)

/**
 * 回收站：站点根下 .blogpad-trash 是 App 自己建的普通目录，不是系统回收站，
 * 所以结构怎么分完全由我们定——
 *
 *     .blogpad-trash/media/<批次>/<原相对路径>   附件回收站只读这里
 *     .blogpad-trash/posts/<批次>/<原相对路径>   文章回收站只读这里
 *
 * 批次名带毫秒时间戳，同一次删除的 md 和它带走的附件用同一个批次名，靠这个把两边对上。
 * 历史遗留的 .blogpad-trash/<批次>/ 没有 media 前缀，一律当附件批次读，不迁移。
 */
object TrashStore {

    private const val TRASH_DIR = ".blogpad-trash"
    private const val SOURCE = ".blogpad-source"
    private const val MARK_FILE = "@@@FILE "
    private const val MARK_END = "@@@END"
    private const val MARK_POST = "@@@POST "
    private const val MARK_MEDIA = "@@@MEDIA "
    private const val MARK_SRC = "@@@SRC "

    private val ABSOLUTE_HOST = Regex("https?://[^/\\s\"'<>)]+")
    private val SLUG_CLEAN = Regex("[^A-Za-z0-9._-]")

    // ---------- 对外 ----------

    /** 新批次名：毫秒级时间戳 + slug，同一次删除里的附件和 md 共用它 */
    fun newBatch(slug: String? = null): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        val tailPart = SLUG_CLEAN.replace(slug.orEmpty(), "-").trim('-').take(40)
        return if (tailPart.isEmpty()) stamp else "$stamp-$tailPart"
    }

    /** 附件进回收站（媒体库里的删除），返回批次名；没搬走任何文件时返回空串 */
    fun moveMediaToTrash(config: Config, targets: List<MediaFile>, title: String = ""): String {
        if (targets.isEmpty()) return ""
        val root = siteRoot(config)
        val batch = newBatch()
        val batchDir = "$root/$TRASH_DIR/${TrashKind.MEDIA.dir}/$batch"
        val session = SshPublisher.open(config)
        try {
            var moved = 0
            for (file in targets) {
                val rel = file.remotePath.removePrefix("$root/")
                if (!isSafeRel(rel)) continue
                if (moveTo(session, file.remotePath, "$batchDir/$rel")) moved++
            }
            if (moved > 0) writeSource(config, session, batchDir, title)
            return if (moved > 0) batch else ""
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * 删除文章 = 移入回收站：md 进 posts/<批次>，只被它引用的附件进 media/<同名批次>，
     * 还被别的文章（含回收站里那些）引用的图原地不动。移完立即构建，线上当场 404。
     */
    fun deletePostToTrash(config: Config, path: String): DeletePostResult {
        val root = siteRoot(config)
        if (!isSafeRel(path.removePrefix("$root/"))) throw PublishException("文章路径不合法，已取消")
        val session = SshPublisher.open(config)
        val moves = ArrayList<Pair<String, String>>()
        val trashRoot = "$root/$TRASH_DIR"
        try {
            val raw = readText(session, path)
                ?: throw PublishException("远端已经没有这个文件，可能已被其它方式删除：$path")

            val relPost = path.removePrefix("$root/")
            val slug = relPost.substringAfterLast('/').removeSuffix(".md")
            val title = titleOf(raw, slug)
            val batch = newBatch(slug)

            // 自己引用了哪些图
            val prefix = config.mediaUrlRoot()
            val ownRefs = extractRefs(raw, prefix)

            // 别人（线上文章 + 回收站文章）还引用着哪些图
            val others = HashSet<String>()
            for (text in readAllMarkdown(session, PostRepository.postDir(config))) {
                if (text == raw) continue
                others.addAll(extractRefs(text, prefix))
            }
            for (text in readAllMarkdown(session, "$trashRoot/${TrashKind.POSTS.dir}")) {
                others.addAll(extractRefs(text, prefix))
            }

            val exclusive = ArrayList<String>()
            if (config.mediaReady() == null) {
                for (url in ownRefs) {
                    if (others.contains(url)) continue
                    val rel = urlToRel(config, url) ?: continue
                    if (rel.isEmpty()) continue
                    exclusive.add(rel)
                }
            }

            // 搬 md
            val postDir = "$trashRoot/${TrashKind.POSTS.dir}/$batch"
            val postDest = "$postDir/$relPost"
            if (!moveTo(session, path, postDest)) throw PublishException("移入回收站失败，文章未改动")
            moves.add(path to postDest)
            writeSource(config, session, postDir, title)

            // 搬独占附件
            var attachments = 0
            val mediaBatchDir = "$trashRoot/${TrashKind.MEDIA.dir}/$batch"
            for (rel in exclusive) {
                val src = "$root/$rel"
                val dest = "$mediaBatchDir/$rel"
                if (moveTo(session, src, dest)) {
                    moves.add(src to dest)
                    attachments++
                }
            }
            if (attachments > 0) writeSource(config, session, mediaBatchDir, title)

            // 移入即下线：构建失败就把整批搬回去，线上保持原样
            val (code, output) = build(session, root, config.buildCmd)
            if (code != 0) {
                rollback(session, moves)
                try { build(session, root, config.buildCmd) } catch (_: Exception) { }
                throw PublishException("构建失败，已整批退回，文章没有变动。\n\n${tail(output)}")
            }
            return DeletePostResult(title, batch, attachments)
        } catch (e: PublishException) {
            rollbackQuietly(session, moves)
            throw e
        } catch (e: Exception) {
            rollbackQuietly(session, moves)
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 附件回收站：media 下的新批次 + 历史遗留的顶层批次 */
    fun listMediaTrash(config: Config): List<TrashFile> {
        val root = siteRoot(config)
        val entries = scanTrash(config, root, wantPosts = false)
        val titles = entries.titles
        val out = ArrayList<TrashFile>()
        for (item in entries.files) {
            val parts = item.rel.split('/')
            if (parts.size < 2) continue
            if (parts[0] == TrashKind.POSTS.dir) continue
            // 新结构 media/<批次>/<原路径…>；旧结构 <批次>/<原路径…>
            val batch: String
            val relPath: String
            if (parts[0] == TrashKind.MEDIA.dir) {
                batch = parts[1]
                relPath = parts.drop(2).joinToString("/")
            } else {
                batch = parts[0]
                relPath = parts.drop(1).joinToString("/")
            }
            out.add(TrashFile(item.abs, relPath, batch, item.size, TrashKind.MEDIA, titles[batch].orEmpty()))
        }
        return out.sortedWith(compareByDescending<TrashFile> { it.batch }.thenBy { it.relPath })
    }

    /** 文章回收站：只认 posts 下的批次 */
    fun listPostTrash(config: Config): List<TrashPost> {
        val root = siteRoot(config)
        val entries = scanTrash(config, root, wantPosts = true)
        return entries.posts.sortedByDescending { it.batch }
    }

    /** 附件还原：先整体查冲突，任何一个目标位置被占就整批拒绝，绝不半还原 */
    fun restoreMedia(config: Config, files: List<TrashFile>, build: Boolean): RestoreResult {
        if (files.isEmpty()) return RestoreResult(0, emptyList(), "")
        val root = siteRoot(config)
        val session = SshPublisher.open(config)
        try {
            val conflicts = ArrayList<String>()
            for (file in files) {
                if (!isSafeRel(file.relPath)) continue
                if (exists(session, "$root/${file.relPath}")) conflicts.add(file.relPath)
            }
            if (conflicts.isNotEmpty()) return RestoreResult(0, conflicts, "")
            var restored = 0
            for (file in files) {
                if (!isSafeRel(file.relPath)) continue
                if (moveTo(session, file.remotePath, "$root/${file.relPath}")) restored++
            }
            for (file in files) pruneDir(session, file.remotePath.substringBeforeLast('/'), batchDirOf(file))
            val output = if (build && restored > 0) {
                val (code, out) = build(session, root, config.buildCmd)
                if (code != 0) throw PublishException("已还原，但构建失败。\n\n${tail(out)}")
                out
            } else ""
            return RestoreResult(restored, emptyList(), output)
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 文章还原：md 回 content/post，同批次的附件一起回 upload，全或无 */
    fun restorePost(config: Config, post: TrashPost, build: Boolean): RestoreResult {
        val root = siteRoot(config)
        val session = SshPublisher.open(config)
        try {
            val batchFiles = batchMediaFiles(session, root, post.batch)
            val pairs = ArrayList<Pair<String, String>>()
            pairs.add(post.remotePath to "$root/${post.relPath}")
            for ((abs, rel) in batchFiles) pairs.add(abs to "$root/$rel")

            val conflicts = pairs.filter { exists(session, it.second) }.map { it.second.removePrefix("$root/") }
            if (conflicts.isNotEmpty()) return RestoreResult(0, conflicts, "")

            var restored = 0
            for ((src, dest) in pairs) {
                if (moveTo(session, src, dest)) restored++
            }
            pruneDir(session, "$root/$TRASH_DIR/${TrashKind.POSTS.dir}/${post.batch}")
            pruneDir(session, "$root/$TRASH_DIR/${TrashKind.MEDIA.dir}/${post.batch}")
            // 只搬不构建：线上还是 404，那就把文章退回草稿态——状态要和线上对得上，
            // 不能文件是 draft: false 而线上什么都没有
            if (!build && restored > 0) markAsDraft(session, "$root/${post.relPath}")
            val output = if (build && restored > 0) {
                val (code, out) = build(session, root, config.buildCmd)
                if (code != 0) throw PublishException("已还原，但构建失败。\n\n${tail(out)}")
                out
            } else ""
            return RestoreResult(restored, emptyList(), output)
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 彻底删除附件：只允许删回收站内的路径 */
    fun purgeMedia(config: Config, files: List<TrashFile>): Int {
        if (files.isEmpty()) return 0
        val root = siteRoot(config)
        val trashPrefix = "$root/$TRASH_DIR/"
        val session = SshPublisher.open(config)
        var sftp: ChannelSftp? = null
        var removed = 0
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)
            for (file in files) {
                if (!file.remotePath.startsWith(trashPrefix)) continue
                if (file.remotePath.split('/').any { it == ".." }) continue
                try {
                    sftp.rm(file.remotePath)
                    removed++
                } catch (_: Exception) {
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
        for (file in files) pruneDir(session, file.remotePath.substringBeforeLast('/'), batchDirOf(file))
        return removed
    }

    /** 彻底删除文章：整批丢掉（md + 它带走的附件），已经没有任何线上引用 */
    fun purgePost(config: Config, post: TrashPost): Boolean {
        val root = siteRoot(config)
        val session = SshPublisher.open(config)
        try {
            val postBatchDir = post.remotePath.substringBeforeLast('/')
            if (!postBatchDir.startsWith("$root/$TRASH_DIR/${TrashKind.POSTS.dir}/")) return false
            val mediaBatchDir = "$root/$TRASH_DIR/${TrashKind.MEDIA.dir}/${post.batch}"
            val command = "rm -rf '" + SshPublisher.escape(postBatchDir) + "' " +
                "'" + SshPublisher.escape(mediaBatchDir) + "' && echo BLOGPAD_RM_OK"
            val (code, out) = SshPublisher.exec(session, command)
            return code == 0 && out.contains("BLOGPAD_RM_OK")
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * 批量还原文章：一次 SSH 会话、一次构建。
     * 跟单篇一样是全或无——任何一篇的目标位置被占，整批不动。
     */
    fun restorePosts(config: Config, posts: List<TrashPost>, build: Boolean): RestoreResult {
        if (posts.isEmpty()) return RestoreResult(0, emptyList(), "")
        val root = siteRoot(config)
        val session = SshPublisher.open(config)
        try {
            val pairs = ArrayList<Pair<String, String>>()
            for (post in posts) {
                if (!isSafeRel(post.relPath)) continue
                pairs.add(post.remotePath to "$root/${post.relPath}")
                for ((abs, rel) in batchMediaFiles(session, root, post.batch)) {
                    if (isSafeRel(rel)) pairs.add(abs to "$root/$rel")
                }
            }
            val conflicts = pairs.filter { exists(session, it.second) }
                .map { it.second.removePrefix("$root/") }
                .distinct()
            if (conflicts.isNotEmpty()) return RestoreResult(0, conflicts, "")

            var restored = 0
            for ((src, dest) in pairs) {
                if (moveTo(session, src, dest)) restored++
            }
            for (post in posts) {
                pruneDir(session, "$root/$TRASH_DIR/${TrashKind.POSTS.dir}/${post.batch}")
                pruneDir(session, "$root/$TRASH_DIR/${TrashKind.MEDIA.dir}/${post.batch}")
            }
            // 只搬不构建：这批文章线上仍是 404，逐篇退回草稿态，标识才不会说谎
            if (!build && restored > 0) {
                posts.forEach { markAsDraft(session, "$root/${it.relPath}") }
            }
            val output = if (build && restored > 0) {
                val (code, out) = build(session, root, config.buildCmd)
                if (code != 0) throw PublishException("已还原，但构建失败。\n\n${tail(out)}")
                out
            } else ""
            return RestoreResult(restored, emptyList(), output)
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 批量彻底删除文章：只允许删 posts/media 回收站内的批次目录，返回真正删掉的篇数 */
    fun purgePosts(config: Config, posts: List<TrashPost>): Int {
        if (posts.isEmpty()) return 0
        val root = siteRoot(config)
        val session = SshPublisher.open(config)
        try {
            val parts = ArrayList<String>()
            posts.forEachIndexed { index, post ->
                val postBatchDir = post.remotePath.substringBeforeLast('/')
                if (!postBatchDir.startsWith("$root/$TRASH_DIR/${TrashKind.POSTS.dir}/")) return@forEachIndexed
                val mediaBatchDir = "$root/$TRASH_DIR/${TrashKind.MEDIA.dir}/${post.batch}"
                parts.add(
                    "rm -rf '" + SshPublisher.escape(postBatchDir) + "' '" +
                        SshPublisher.escape(mediaBatchDir) + "' && echo BLOGPAD_RM_OK_$index"
                )
            }
            if (parts.isEmpty()) return 0
            val (code, out) = SshPublisher.exec(session, "true; " + parts.joinToString("; "))
            if (code != 0 && out.isEmpty()) return 0
            return posts.indices.count { out.contains("BLOGPAD_RM_OK_$it") }
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 清空一份回收站，另一份不动 */
    fun purgeAll(config: Config, kind: TrashKind): Boolean {
        val root = siteRoot(config)
        val target = "$root/$TRASH_DIR/${kind.dir}"
        if (!target.endsWith("/${TRASH_DIR}/${kind.dir}")) throw PublishException("回收站路径校验失败，已取消")
        val session = SshPublisher.open(config)
        try {
            val command = "if [ -d '" + SshPublisher.escape(target) + "' ]; then rm -rf '" +
                SshPublisher.escape(target) + "' && echo BLOGPAD_RM_OK; else echo BLOGPAD_RM_OK; fi"
            val (code, out) = SshPublisher.exec(session, command)
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

    private fun siteRoot(config: Config): String {
        val root = config.siteRoot.trimEnd('/')
        if (root.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        if (!root.startsWith("/")) throw PublishException("站点根目录必须是绝对路径")
        return root
    }

    private fun isSafeRel(rel: String): Boolean {
        if (rel.isBlank()) return false
        return rel.split('/').none { it.isEmpty() || it == ".." || it == "." }
    }

    private fun moveTo(session: Session, src: String, dest: String): Boolean = try {
        val command = "mkdir -p '" + SshPublisher.escape(dest.substringBeforeLast('/')) + "' && " +
            "mv -f '" + SshPublisher.escape(src) + "' '" + SshPublisher.escape(dest) + "' && echo BLOGPAD_MV_OK"
        val (code, out) = SshPublisher.exec(session, command)
        code == 0 && out.contains("BLOGPAD_MV_OK")
    } catch (_: Exception) {
        false
    }

    /** 回滚：把搬过的都按相反方向搬回去 */
    private fun rollback(session: Session, moves: List<Pair<String, String>>) {
        for ((src, dest) in moves.asReversed()) {
            try {
                val command = "mkdir -p '" + SshPublisher.escape(src.substringBeforeLast('/')) + "' && " +
                    "mv -f '" + SshPublisher.escape(dest) + "' '" + SshPublisher.escape(src) + "'"
                SshPublisher.exec(session, command)
            } catch (_: Exception) {
            }
        }
    }

    private fun rollbackQuietly(session: Session, moves: List<Pair<String, String>>) {
        if (moves.isEmpty()) return
        rollback(session, moves)
    }

    private fun build(session: Session, root: String, buildCmd: String): Pair<Int, String> =
        SshPublisher.exec(session, "cd '" + SshPublisher.escape(root) + "' && $buildCmd")

    private fun exists(session: Session, path: String): Boolean = try {
        val (code, _) = SshPublisher.exec(session, "test -e '" + SshPublisher.escape(path) + "'")
        code == 0
    } catch (_: Exception) {
        false
    }

    /**
     * 搬完把空掉的目录收拾一下。这里是单层 rmdir，非空就失败走人——
     * 绝不能用 rmdir -p 沿父链上溯，否则一路删空能把 .blogpad-trash 自己删掉。
     */
    private fun pruneDir(session: Session, vararg dirs: String) {
        for (dir in dirs) {
            if (dir.isBlank()) continue
            try {
                SshPublisher.exec(session, "rmdir '" + SshPublisher.escape(dir) + "' 2>/dev/null")
            } catch (_: Exception) {
            }
        }
    }

    /** 回收站里一个文件对应的批次目录（新旧两种结构都能算出来） */
    private fun batchDirOf(file: TrashFile): String {
        val suffix = "/" + file.relPath
        return if (file.remotePath.endsWith(suffix)) {
            file.remotePath.dropLast(suffix.length)
        } else {
            file.remotePath.substringBeforeLast('/')
        }
    }

    /**
     * 把还原回来的文章改回草稿态（draft: true）。
     *
     * 搬运本身不动 front matter，搬回来的已发布文章仍然是 draft: false；可线上产物
     * 还停在它被删掉的那次构建里，什么都不改就会出现"列表说已发布、点开是 404"。
     * 只搬不构建时统一退回草稿，让文件状态和线上状态说的是同一件事；要重新上线，
     * 在列表里把它转为已发布即可（那一步会触发构建）。
     */
    private fun markAsDraft(session: Session, path: String) {
        val raw = readText(session, path) ?: return
        val updated = try {
            PostRepository.rewriteDraftFlag(raw, true)
        } catch (e: PublishException) {
            throw PublishException("文章已还原，但退回草稿状态失败：" + (e.message ?: "front matter 异常"))
        } ?: return
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)
            sftp.put(ByteArrayInputStream(updated.toByteArray(Charsets.UTF_8)), path)
        } catch (e: Exception) {
            throw PublishException("文章已还原，但退回草稿状态失败：" + SshPublisher.describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun writeSource(config: Config, session: Session, dir: String, title: String) {
        if (title.isBlank()) return
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            sftp.put(ByteArrayInputStream(title.toByteArray(Charsets.UTF_8)), "$dir/$SOURCE")
        } catch (_: Exception) {
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun readText(session: Session, path: String): String? = try {
        val (code, out) = SshPublisher.exec(session, "cat '" + SshPublisher.escape(path) + "' 2>/dev/null")
        if (code == 0) out else null
    } catch (_: Exception) {
        null
    }

    /** 一次 exec 拿回目录下所有 md 的全文，用来比对引用 */
    private fun readAllMarkdown(session: Session, dir: String): List<String> {
        val script = "cd '" + SshPublisher.escape(dir) + "' 2>/dev/null || exit 0; " +
            "find . -type f -name '*.md' | LC_ALL=C sort | while IFS= read -r f; do " +
            "printf '$MARK_FILE%s\\n' \"\$f\"; cat \"\$f\"; printf '\\n$MARK_END\\n'; done"
        val (code, out) = SshPublisher.exec(session, script)
        if (code != 0 && out.isBlank()) return emptyList()
        val texts = ArrayList<String>()
        val body = StringBuilder()
        var inside = false
        for (line in out.lineSequence()) {
            when {
                line.startsWith(MARK_FILE) -> {
                    inside = true
                    body.setLength(0)
                }
                line.trim() == MARK_END -> {
                    if (inside) texts.add(body.toString())
                    inside = false
                    body.setLength(0)
                }
                else -> if (inside) body.append(line).append('\n')
            }
        }
        return texts
    }

    private class ScannedFile(val abs: String, val rel: String, val size: Long)

    private class ScanResult(
        val files: List<ScannedFile>,
        val posts: List<TrashPost>,
        val titles: Map<String, String>
    )

    /** 一次 SSH 往返把回收站摸一遍：文件清单、文章头、批次来源标题 */
    private fun scanTrash(config: Config, root: String, wantPosts: Boolean): ScanResult {
        val trash = "$root/$TRASH_DIR"
        val script = buildString {
            append("cd '").append(SshPublisher.escape(trash)).append("' 2>/dev/null || exit 0; ")
            append("find . -type f ! -name '").append(SOURCE).append("' 2>/dev/null | LC_ALL=C sort | ")
            append("while IFS= read -r f; do printf '$MARK_FILE%s|%s\\n' \"\${f#./}\" \"\$(wc -c < \"\$f\" | tr -d ' ')\"; done; ")
            append("for f in ").append(TrashKind.MEDIA.dir).append("/*/").append(SOURCE)
            append(" ").append(TrashKind.POSTS.dir).append("/*/").append(SOURCE).append("; do ")
            append("[ -f \"\$f\" ] && { printf '$MARK_SRC%s\\n' \"\$(printf '%s' \"\$f\" | cut -d/ -f2)\"; cat \"\$f\"; printf '\\n$MARK_END\\n'; }; done; ")
            if (wantPosts) {
                append("if [ -d ").append(TrashKind.POSTS.dir).append(" ]; then cd ").append(TrashKind.POSTS.dir)
                append(" && find . -type f -name '*.md' | LC_ALL=C sort | while IFS= read -r f; do ")
                append("printf '$MARK_POST%s\\n' \"\$f\"; head -c 1500 \"\$f\"; printf '\\n$MARK_END\\n'; done; fi; ")
                append("cd '").append(SshPublisher.escape(trash)).append("' 2>/dev/null; ")
                append("find ").append(TrashKind.MEDIA.dir).append(" -type f ! -name '").append(SOURCE)
                append("' 2>/dev/null | while IFS= read -r f; do printf '$MARK_MEDIA%s\\n' \"\$f\"; done")
            }
        }
        val session = SshPublisher.open(config)
        try {
            val (_, out) = SshPublisher.exec(session, script)
            val files = ArrayList<ScannedFile>()
            val titles = HashMap<String, String>()
            val postHeads = LinkedHashMap<String, String>()
            val mediaCount = HashMap<String, Int>()

            var mode = 0 // 1=file 2=src 3=post
            var key = ""
            val body = StringBuilder()
            for (line in out.lineSequence()) {
                when {
                    line.startsWith(MARK_FILE) -> {
                        val payload = line.removePrefix(MARK_FILE)
                        val cut = payload.lastIndexOf('|')
                        if (cut > 0) {
                            val rel = payload.substring(0, cut)
                            val size = payload.substring(cut + 1).trim().toLongOrNull() ?: 0L
                            files.add(ScannedFile("$trash/$rel", rel, size))
                        }
                    }
                    line.startsWith(MARK_SRC) -> {
                        mode = 2
                        key = line.removePrefix(MARK_SRC).trim()
                        body.setLength(0)
                    }
                    line.startsWith(MARK_POST) -> {
                        mode = 3
                        key = line.removePrefix(MARK_POST).trim()
                        body.setLength(0)
                    }
                    line.startsWith(MARK_MEDIA) -> {
                        val rel = line.removePrefix(MARK_MEDIA).trim()
                        val parts = rel.split('/')
                        if (parts.size >= 3 && parts[0] == TrashKind.MEDIA.dir) {
                            val batch = parts[1]
                            mediaCount[batch] = (mediaCount[batch] ?: 0) + 1
                        }
                    }
                    line.trim() == MARK_END -> {
                        when (mode) {
                            2 -> titles[key] = body.toString().trim()
                            3 -> postHeads[key] = body.toString()
                        }
                        mode = 0
                    }
                    mode != 0 -> body.append(line).append('\n')
                }
            }

            val posts = ArrayList<TrashPost>()
            for ((rel, head) in postHeads) {
                val parts = rel.removePrefix("./").split('/')
                if (parts.size < 2) continue
                val batch = parts[0]
                val relPath = parts.drop(1).joinToString("/")
                val cleanRel = rel.removePrefix("./")
                val title = titleOf(head, relPath.substringAfterLast('/').removeSuffix(".md"))
                posts.add(
                    TrashPost(
                        remotePath = "$trash/${TrashKind.POSTS.dir}/$cleanRel",
                        relPath = relPath,
                        batch = batch,
                        title = title,
                        attachmentCount = mediaCount[batch] ?: 0,
                        size = files.firstOrNull { it.rel.endsWith(rel.removePrefix("./")) }?.size ?: 0L,
                        time = formatBatchTime(batch)
                    )
                )
            }
            return ScanResult(files, posts, titles)
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 批次名里的时间戳 → 可读日期 */
    private fun formatBatchTime(batch: String): String {
        val stamp = batch.substringBefore('-')
        if (stamp.length < 15) return ""
        return try {
            val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            val date = format.parse(stamp.substring(0, 15)) ?: return ""
            val out = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            out.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            out.format(date)
        } catch (_: Exception) {
            ""
        }
    }

    private fun batchMediaFiles(session: Session, root: String, batch: String): List<Pair<String, String>> {
        val dir = "$root/$TRASH_DIR/${TrashKind.MEDIA.dir}/$batch"
        val script = "cd '" + SshPublisher.escape(dir) + "' 2>/dev/null || exit 0; " +
            "find . -type f ! -name '$SOURCE' | LC_ALL=C sort | while IFS= read -r f; do printf '%s\\n' \"\${f#./}\"; done"
        val (_, out) = SshPublisher.exec(session, script)
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && isSafeRel(it) }
            .map { "$dir/$it" to "$root/$it" }
            .toList()
    }

    /** 正文里出现的站内附件地址（先剥域名，再按附件库前缀全文捞） */
    private fun extractRefs(text: String, prefix: String): Set<String> {
        if (prefix.isEmpty()) return emptySet()
        val flattened = text.replace(ABSOLUTE_HOST, "")
        val regex = Regex(Regex.escape(prefix) + "/[^\\s()\\[\\]<>\"'`,;]+")
        val out = HashSet<String>()
        for (match in regex.findAll(flattened)) {
            val url = normalizeUrl(match.value)
            if (url.startsWith("$prefix/")) out.add(url)
        }
        return out
    }

    private fun urlToRel(config: Config, url: String): String? {
        val prefix = config.mediaUrlRoot()
        val mediaRoot = config.mediaRoot.trim().trim('/')
        if (mediaRoot.isEmpty()) return null
        if (!url.startsWith("$prefix/")) return null
        return mediaRoot + "/" + url.removePrefix("$prefix/")
    }

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

    private fun titleOf(raw: String, fallback: String): String {
        val head = raw.substringBefore("\n---\n", raw.take(1500))
        for (line in head.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key != "title") continue
            val value = line.substring(idx + 1).trim()
                .removeSurrounding("\"").removeSurrounding("'")
            if (value.isNotEmpty()) return value
        }
        return fallback
    }

    private fun tail(text: String, max: Int = 1200): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "（无输出）"
        return if (trimmed.length <= max) trimmed else "…" + trimmed.takeLast(max)
    }
}
