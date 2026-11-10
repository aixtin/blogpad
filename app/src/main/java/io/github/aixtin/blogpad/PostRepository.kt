package io.github.aixtin.blogpad

import com.jcraft.jsch.ChannelSftp
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 列表用的一篇文章摘要，字段全部来自远端 md 的 front matter */
data class PostMeta(
    val path: String,
    val slug: String,
    val title: String,
    val date: String,
    val draft: Boolean,
    val tags: List<String>,
    val categories: List<String> = emptyList()
)

/** 编辑缓冲区 */
data class PostDraft(
    val title: String,
    val slug: String,
    val categories: String,
    val tags: String,
    val body: String,
    val draft: Boolean,
    val date: String? = null,
    val extraFrontMatter: List<String> = emptyList()
)

/** 读回来的一篇文章：元信息 + 可编辑缓冲区 + 原始日期（保存时沿用） */
data class PostSource(
    val meta: PostMeta,
    val draft: PostDraft,
    val dateIso: String?
)

data class SaveResult(
    val remotePath: String,
    val url: String,
    val renamedFrom: String?,
    val buildOutput: String
)

/**
 * 文章仓库：列表、读取、保存全部直接作用于远端 content/post。
 * 不落任何本地数据库——列表就是远端目录的投影。
 */
object PostRepository {

    private const val HEAD_BYTES = 1600
    private const val MARK_FILE = "@@@FILE "
    private const val MARK_END = "@@@END"

    /** 被 App 管理的 front matter 键，其余键原样保留 */
    private val MANAGED = setOf("title", "slug", "date", "draft", "categories", "tags")

    fun postDir(config: Config): String {
        val postDir = config.postDir.trim('/').ifEmpty { "content/post" }
        return "${config.siteRoot.trimEnd('/')}/$postDir"
    }

    /** 一次 SSH 往返取回所有文章的 front matter 头部 */
    fun listPosts(config: Config): List<PostMeta> {
        val dir = postDir(config)
        val session = SshPublisher.open(config)
        try {
            val script = "cd '" + SshPublisher.escape(dir) + "' && " +
                "find . -type f -name '*.md' | LC_ALL=C sort | while IFS= read -r f; do " +
                "printf '@@@FILE %s\\n' \"\$f\"; head -c $HEAD_BYTES \"\$f\"; printf '\\n@@@END\\n'; done"
            val (code, out) = SshPublisher.exec(session, script)
            if (code != 0 && out.contains("No such file")) {
                throw PublishException("文章目录不存在：$dir")
            }
            return parseListing(dir, out)
        } finally {
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 把列表里所有文章的 front matter 压成一张词表：出现次数降序，同频按名字排 */
    fun buildTaxonomy(posts: List<PostMeta>): Taxonomy {
        fun rank(values: List<List<String>>): List<String> {
            val counter = LinkedHashMap<String, Int>()
            values.forEach { list ->
                list.forEach { counter[it] = (counter[it] ?: 0) + 1 }
            }
            return counter.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { it.key.lowercase() }
                )
                .map { it.key }
        }
        return Taxonomy(
            categories = rank(posts.map { it.categories }),
            tags = rank(posts.map { it.tags }),
            updatedAt = System.currentTimeMillis()
        )
    }

    fun readPost(config: Config, path: String): PostSource {
        val session = SshPublisher.open(config)
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)
            val input = sftp.get(path)
            val raw = input.readBytes().toString(Charsets.UTF_8)
            input.close()
            return parseSource(path, raw)
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * 保存：写入 target -> 构建。失败回滚。
     * 原路径与目标路径不同（改 slug）时，删除旧文件后再构建一次，
     * 保证站点里不会留下两条 URL；任何一步失败都写回旧内容。
     */
    fun save(config: Config, draft: PostDraft, originalPath: String?): SaveResult {
        val siteRoot = config.siteRoot.trimEnd('/')
        val slug = SshPublisher.resolveSlug(draft.slug)
        val remoteDir = postDir(config)
        val target = "$remoteDir/$slug.md"
        val markdown = render(draft.copy(slug = slug), draft.date)
        val renamed = originalPath != null && originalPath != target

        val session = SshPublisher.open(config)
        var sftp: ChannelSftp? = null
        var prevTarget: ByteArray? = null
        var prevOriginal: ByteArray? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(20000)

            prevTarget = readOrNull(sftp, target)
            if (renamed) prevOriginal = readOrNull(sftp, originalPath!!)

            sftp.put(ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8)), target)
            if (renamed && prevOriginal != null) {
                try { sftp.rm(originalPath!!) } catch (_: Exception) { }
            }

            val command = "cd '${SshPublisher.escape(siteRoot)}' && ${config.buildCmd}"
            val (code, output) = SshPublisher.exec(session, command)
            if (code != 0) {
                rollback(sftp, target, prevTarget)
                if (renamed) rollback(sftp, originalPath!!, prevOriginal)
                try { SshPublisher.exec(session, command) } catch (_: Exception) { }
                throw PublishException("构建失败，已回滚文章文件。\n\n${tail(output)}")
            }

            return SaveResult(
                remotePath = target,
                url = SshPublisher.buildUrl(config, slug, draft.draft),
                renamedFrom = if (renamed) originalPath else null,
                buildOutput = tail(output)
            )
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            try {
                sftp?.let {
                    rollback(it, target, prevTarget)
                    if (renamed) rollback(it, originalPath!!, prevOriginal)
                }
            } catch (_: Exception) { }
            throw PublishException(SshPublisher.describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    // ---------- 渲染 ----------

    fun render(draft: PostDraft, isoDate: String?): String {
        val text = StringBuilder()
        text.append("---\n")
        text.append("title: ").append(JSONObject.quote(draft.title)).append('\n')
        text.append("date: ").append(isoDate?.ifBlank { null } ?: nowIso()).append('\n')
        text.append("draft: ").append(draft.draft).append('\n')
        text.append("slug: ").append(JSONObject.quote(draft.slug)).append('\n')
        val categories = splitList(draft.categories)
        if (categories.isNotEmpty()) {
            text.append("categories: [")
            text.append(categories.joinToString(", ") { JSONObject.quote(it) })
            text.append("]\n")
        }
        val tags = splitList(draft.tags)
        if (tags.isNotEmpty()) {
            text.append("tags: [")
            text.append(tags.joinToString(", ") { JSONObject.quote(it) })
            text.append("]\n")
        }
        draft.extraFrontMatter.forEach { line ->
            if (line.isNotBlank()) text.append(line).append('\n')
        }
        text.append("---\n\n")
        text.append(draft.body.trimEnd('\n'))
        text.append('\n')
        return text.toString()
    }

    private fun nowIso(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        format.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return format.format(Date())
    }

    // ---------- 解析 ----------

    private fun parseListing(dir: String, output: String): List<PostMeta> {
        val posts = ArrayList<PostMeta>()
        var rel: String? = null
        val head = StringBuilder()
        for (line in output.lineSequence()) {
            when {
                line.startsWith(MARK_FILE) -> {
                    rel = line.removePrefix(MARK_FILE).trim()
                    head.setLength(0)
                }
                line.trim() == MARK_END -> {
                    val path = rel
                    if (path != null) posts.add(buildMeta(dir, path, head.toString()))
                    rel = null
                    head.setLength(0)
                }
                else -> if (rel != null) head.append(line).append('\n')
            }
        }
        return posts.sortedWith(
            compareByDescending<PostMeta> { it.date }.thenByDescending { it.path }
        )
    }

    private fun buildMeta(dir: String, rel: String, head: String): PostMeta {
        val slug = rel.substringAfterLast('/').removeSuffix(".md")
        val fields = frontFields(head)
        val title = unquote(fields["title"].orEmpty()).ifBlank { slug }
        return PostMeta(
            path = "$dir/${rel.removePrefix("./")}",
            slug = slug,
            title = title,
            date = displayDate(fields["date"]),
            draft = fields["draft"]?.trim()?.equals("true", ignoreCase = true) == true,
            tags = parseList(fields["tags"]),
            categories = parseList(fields["categories"])
        )
    }

    private fun parseSource(path: String, raw: String): PostSource {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val slug = path.substringAfterLast('/').removeSuffix(".md")
        val lines = normalized.split('\n').toMutableList()
        var body = normalized
        var end = -1
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") { end = i; break }
            }
        }

        val fields = LinkedHashMap<String, String>()
        val extra = ArrayList<String>()
        if (end > 0) {
            val front = lines.subList(1, end)
            var i = 0
            while (i < front.size) {
                val line = front[i]
                val isTop = line.isNotEmpty() && !line[0].isWhitespace()
                val key = if (isTop) line.substringBefore(':', "").trim() else ""
                if (isTop && key.isNotEmpty() && MANAGED.contains(key)) {
                    val inline = line.substringAfter(':', "").trim()
                    var j = i + 1
                    while (j < front.size && front[j].trimStart().startsWith("- ")) j++
                    fields[key] = if (inline.isNotEmpty()) inline else {
                        front.subList(i + 1, j)
                            .joinToString(",") { unquote(it.trimStart().removePrefix("- ").trim()) }
                    }
                    i = j
                } else {
                    extra.add(line)
                    i++
                }
            }
            body = lines.drop(end + 1).joinToString("\n").trimStart('\n')
        }

        val meta = PostMeta(
            path = path,
            slug = slug,
            title = unquote(fields["title"].orEmpty()).ifBlank { slug },
            date = displayDate(fields["date"]),
            draft = fields["draft"]?.trim()?.equals("true", ignoreCase = true) == true,
            tags = parseList(fields["tags"]),
            categories = parseList(fields["categories"])
        )
        val draft = PostDraft(
            title = meta.title,
            slug = slug,
            categories = parseList(fields["categories"]).joinToString(", "),
            tags = meta.tags.joinToString(", "),
            body = body,
            draft = meta.draft,
            date = fields["date"]?.let { unquote(it) },
            extraFrontMatter = extra
        )
        return PostSource(meta = meta, draft = draft, dateIso = draft.date)
    }

    /** 只解析 front matter 内的顶层键；YAML 列表合并为逗号串 */
    private fun frontFields(head: String): Map<String, String> {
        val text = head.replace("\r\n", "\n")
        val lines = text.split('\n')
        if (lines.isEmpty() || lines[0].trim() != "---") return emptyMap()
        val map = LinkedHashMap<String, String>()
        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim() == "---") break
            val isTop = line.isNotEmpty() && !line[0].isWhitespace()
            val idx = line.indexOf(':')
            if (isTop && idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (value.isEmpty()) {
                    val items = ArrayList<String>()
                    var j = i + 1
                    while (j < lines.size && lines[j].trimStart().startsWith("- ")) {
                        items.add(unquote(lines[j].trimStart().removePrefix("- ").trim()))
                        j++
                    }
                    if (items.isNotEmpty()) {
                        map[key] = items.joinToString(",")
                        i = j
                        continue
                    }
                }
                map[key] = value
            }
            i++
        }
        return map
    }

    private fun parseList(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val body = raw.removePrefix("[").removeSuffix("]")
        return body.split(',', '，', ';', '；')
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2) {
            val first = trimmed[0]
            val last = trimmed[trimmed.length - 1]
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length - 1)
            }
        }
        return trimmed
    }

    /** 2026-11-09T01:23:45+08:00 / "2026-11-09 01:23" / 2026-11-09 都归一成可排序文本 */
    private fun displayDate(value: String?): String {
        val s = unquote(value.orEmpty())
        if (s.length >= 16 && s[4] == '-') return s.substring(0, 16).replace('T', ' ')
        if (s.length >= 10 && s[4] == '-') return s.substring(0, 10)
        return ""
    }

    private fun splitList(value: String): List<String> =
        value.split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun readOrNull(sftp: ChannelSftp, path: String): ByteArray? = try {
        val input = sftp.get(path)
        input.readBytes().also { input.close() }
    } catch (_: Exception) {
        null
    }

    private fun rollback(sftp: ChannelSftp, path: String, previous: ByteArray?) {
        if (previous == null) {
            try { sftp.rm(path) } catch (_: Exception) { }
        } else {
            try {
                sftp.put(ByteArrayInputStream(previous), path)
            } catch (_: Exception) { }
        }
    }

    private fun tail(text: String, max: Int = 1200): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length <= max) trimmed else "…" + trimmed.takeLast(max)
    }
}
