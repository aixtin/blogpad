package io.github.aixtin.blogpad

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 发布失败时抛出，message 直接展示给用户 */
class PublishException(message: String) : Exception(message)

data class PublishResult(
    val remotePath: String,
    val url: String,
    val buildOutput: String
)

data class Article(
    val title: String,
    val slug: String,
    val categories: String,
    val tags: String,
    val body: String,
    val draft: Boolean
)

object SshPublisher {

    private const val CONNECT_TIMEOUT_MS = 20000
    private const val EXEC_TIMEOUT_MS = 300000

    /**
     * 仅做连通性测试。
     * 返回 ok=true 时 hint 为 SSH 欢迎信息摘要；
     * 首次连接会返回 fingerprint，需用户确认后写入配置再连。
     */
    fun test(config: Config): Triple<Boolean, String, String?> {
        var session: Session? = null
        val repo = FingerprintRepository(config)
        return try {
            session = openSession(config, repo)
            val (code, out) = exec(session, "echo BLOGPAD_OK; uname -sr")
            if (code != 0) {
                Triple(false, "命令执行失败（exit=$code）: ${out.trim()}", null)
            } else {
                val head = out.lines().firstOrNull { it.isNotBlank() } ?: ""
                val sys = out.lines().lastOrNull { it.isNotBlank() } ?: ""
                Triple(true, "连接成功  $sys", null)
            }
        } catch (e: Exception) {
            val fp = repo.pendingFingerprint()
            if (fp != null) {
                Triple(false, "首次连接这台主机，请核对指纹后信任", fp)
            } else {
                Triple(false, describe(e), null)
            }
        } finally {
            try { session?.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * 发布：上传 md -> 执行构建命令 -> 返回链接。
     * 构建失败会回滚：若远端原本没有该文件则删除，原本有则写回旧内容。
     */
    fun publish(config: Config, article: Article): PublishResult {
        val slug = resolveSlug(article.slug)
        val siteRoot = config.siteRoot.trimEnd('/')
        val postDir = config.postDir.trim('/').ifEmpty { "content/post" }
        val remoteDir = "$siteRoot/$postDir"
        val remotePath = "$remoteDir/$slug.md"
        val markdown = renderMarkdown(article.copy(slug = slug))

        var session: Session? = null
        val repo = FingerprintRepository(config)
        try {
            session = openSession(config, repo)
        } catch (e: Exception) {
            val fp = repo.pendingFingerprint()
            throw PublishException(
                if (fp != null) "这台主机还没有被信任（指纹 $fp），请先到设置里点一次“测试连接”"
                else describe(e)
            )
        }

        var sftp: ChannelSftp? = null
        var previousContent: ByteArray? = null
        try {
            val (dirCode, dirOut) = exec(session, "test -d '${escape(remoteDir)}' && echo YES || echo NO")
            if (!dirOut.contains("YES")) {
                throw PublishException("远端目录不存在：$remoteDir")
            }

            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)

            previousContent = try {
                val input = sftp.get(remotePath)
                input.readBytes().also { input.close() }
            } catch (_: Exception) {
                null
            }

            sftp.put(ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8)), remotePath)

            val command = "cd '${escape(siteRoot)}' && ${config.buildCmd}"
            val (code, output) = exec(session, command)
            if (code != 0) {
                rollback(sftp, remotePath, previousContent)
                throw PublishException("构建失败，已回滚文章文件。\n\n${tail(output)}")
            }

            return PublishResult(remotePath, buildUrl(config, slug, article.draft), tail(output))
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            try {
                sftp?.let { rollback(it, remotePath, previousContent) }
            } catch (_: Exception) { }
            throw PublishException(describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun rollback(sftp: ChannelSftp, remotePath: String, previous: ByteArray?) {
        if (previous == null) {
            try { sftp.rm(remotePath) } catch (_: Exception) { }
        } else {
            try { sftp.put(ByteArrayInputStream(previous), remotePath) } catch (_: Exception) { }
        }
    }

    // ---------- 附件上传 ----------

    /** 一个已上传附件：远端绝对路径、写进 Markdown 的站内路径、文件名 */
    data class MediaRef(val remotePath: String, val urlPath: String, val fileName: String)

    /**
     * 建目录。JSch 的 put 不会自动建父目录，父目录缺失会直接失败，所以上传前必须先来这一趟。
     * mkdir -p 幂等：目录已存在不算错，不会重复创建，也不会动已有内容。
     */
    internal fun ensureDir(session: Session, remoteDir: String) {
        val (code, out) = exec(session, "mkdir -p '${escape(remoteDir)}' && echo BLOGPAD_MKDIR_OK")
        if (code != 0 || !out.contains("BLOGPAD_MKDIR_OK")) {
            throw PublishException("无法创建远端目录：$remoteDir\n\n${tail(out)}")
        }
    }

    /**
     * 上传附件：先确保目录存在，再按 <slug>-<时间戳>.<ext> 落盘，返回可直接写进 Markdown 的站内路径。
     * 只写文件、不触发构建——附件先落地，正文后发布，顺序反了正文里会挂 404。
     */
    fun uploadMedia(
        config: Config,
        kind: MediaKind,
        data: ByteArray,
        displayName: String?,
        slugHint: String
    ): MediaRef {
        val siteRoot = config.siteRoot.trimEnd('/')
        if (siteRoot.isEmpty()) throw PublishException("请先在设置里填写站点根目录")
        config.mediaReady()?.let { throw PublishException(it) }

        val slug = resolveSlug(slugHint)
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        val ext = safeExtension(displayName, kind)
        val fileName = "$slug-$stamp.$ext"

        val remoteDir = "$siteRoot/${config.mediaDir(kind)}"
        val remotePath = "$remoteDir/$fileName"
        val urlPath = "${config.mediaUrlDir(kind)}/$fileName"

        val session = open(config)
        var sftp: ChannelSftp? = null
        try {
            ensureDir(session, remoteDir)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CONNECT_TIMEOUT_MS)
            sftp.put(ByteArrayInputStream(data), remotePath)
            return MediaRef(remotePath, urlPath, fileName)
        } catch (e: PublishException) {
            throw e
        } catch (e: Exception) {
            throw PublishException(describe(e))
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) { }
            try { session.disconnect() } catch (_: Exception) { }
        }
    }

    /** 只认字母数字扩展名，最长 6 位；拿不到就按类型给个保守默认 */
    private fun safeExtension(displayName: String?, kind: MediaKind): String {
        val fromName = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.filter { it.isLetterOrDigit() }
            ?.take(6)
            .orEmpty()
        if (fromName.isNotEmpty() && fromName.length <= 6) return fromName
        return when (kind) {
            MediaKind.IMAGE -> "jpg"
            MediaKind.VIDEO -> "mp4"
            MediaKind.AUDIO -> "mp3"
            MediaKind.COVER -> "jpg"
        }
    }


    /** 建立连接；失败时把原因包成 PublishException 供 UI 直接展示 */
    internal fun open(config: Config): Session {
        val repo = FingerprintRepository(config)
        return try {
            openSession(config, repo)
        } catch (e: Exception) {
            val fp = repo.pendingFingerprint()
            throw PublishException(
                if (fp != null) "这台主机还没有被信任（指纹 \$fp），请先到设置里点一次“测试连接”"
                else describe(e)
            )
        }
    }

    private fun openSession(config: Config, repo: FingerprintRepository): Session {
        val jsch = JSch()
        jsch.hostKeyRepository = repo
        if (config.authType == "key" && config.privateKey.isNotBlank()) {
            val key = normalizeKey(config.privateKey)
            jsch.addIdentity(
                "blogpad",
                key.toByteArray(Charsets.UTF_8),
                null,
                config.passphrase.ifBlank { null }?.toByteArray(Charsets.UTF_8)
            )
        }
        val session = jsch.getSession(config.user, config.host, config.port)
        if (config.authType == "password") {
            session.setPassword(config.password)
        }
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (config.authType == "password") "password,keyboard-interactive" else "publickey")
        session.timeout = CONNECT_TIMEOUT_MS
        session.setServerAliveInterval(15000)
        session.setServerAliveCountMax(2)
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }

    /** 粘贴来的私钥常带多余换行或 CRLF，JSch 对格式敏感，这里统一成 LF + 末尾换行 */
    private fun normalizeKey(raw: String): String {
        val body = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        return if (body.endsWith("\n")) body else "$body\n"
    }

    internal fun exec(session: Session, command: String): Pair<Int, String> {
        var channel: ChannelExec? = null
        return try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stderr = ByteArrayOutputStream()
            channel.setErrStream(stderr)
            val input = channel.inputStream
            channel.connect(CONNECT_TIMEOUT_MS)
            val stdout = input.readBytes().toString(Charsets.UTF_8)
            val deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(80)
            }
            val code = if (channel.isClosed) channel.exitStatus else -1
            val combined = (stdout + stderr.toString("UTF-8")).trim()
            if (!channel.isClosed) {
                throw PublishException("命令超时：$command")
            }
            code to combined
        } finally {
            try { channel?.disconnect() } catch (_: Exception) { }
        }
    }

    fun resolveSlug(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (cleaned.isNotEmpty()) return cleaned
        val now = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date())
        return "post-$now"
    }

    fun renderMarkdown(article: Article): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val quote = { s: String -> JSONObject.quote(s) }
        val builder = StringBuilder()
        builder.append("---\n")
        builder.append("title: ").append(quote(article.title)).append('\n')
        builder.append("date: ").append(dateFormat.format(Date())).append('\n')
        builder.append("draft: ").append(article.draft).append('\n')
        builder.append("slug: ").append(quote(article.slug)).append('\n')
        val categories = splitList(article.categories)
        if (categories.isNotEmpty()) {
            builder.append("categories: [")
            builder.append(categories.joinToString(", ") { quote(it) })
            builder.append("]\n")
        }
        val tags = splitList(article.tags)
        if (tags.isNotEmpty()) {
            builder.append("tags: [")
            builder.append(tags.joinToString(", ") { quote(it) })
            builder.append("]\n")
        }
        builder.append("---\n\n")
        builder.append(article.body.trimEnd('\n'))
        builder.append('\n')
        return builder.toString()
    }

    private fun splitList(value: String): List<String> =
        value.split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    internal fun buildUrl(config: Config, slug: String, draft: Boolean): String {
        if (draft) return ""
        val template = config.linkTemplate.trim()
        if (template.isNotEmpty()) return template.replace("{slug}", slug)
        val base = config.siteUrl.trim().trimEnd('/')
        return if (base.isEmpty()) "" else "$base/archives/$slug/"
    }

    internal fun escape(arg: String): String = arg.replace("'", "'\\''")

    private fun tail(text: String, max: Int = 2000): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "（无输出）"
        return if (trimmed.length <= max) trimmed else "…" + trimmed.takeLast(max)
    }

    internal fun describe(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("Auth fail") || msg.contains("Auth cancel") ->
                "认证失败：私钥/密码不正确，或该 key 未被服务器 authorized_keys 接受"
            msg.contains("UnknownHostKey") ->
                "主机指纹未信任：请先点“测试连接”确认服务器指纹"
            msg.contains("Connection refused") ->
                "连接被拒绝：检查地址与 SSH 端口是否正确"
            msg.contains("timeout") || msg.contains("Timeout") ->
                "连接超时：检查服务器地址、端口与网络"
            msg.contains("invalid privatekey") || msg.contains("PEM") ->
                "私钥格式无法识别：需要 OpenSSH 私钥（ed25519/rsa），不支持 PuTTY 的 .ppk"
            msg.contains("Host is not allowed") || msg.contains("java.net.UnknownHost") ->
                "无法解析主机名：检查地址是否写错"
            else -> msg
        }
    }

    /** 主机指纹白名单：只接受配置里记录过的指纹；首次连接把指纹暂存供 UI 确认 */
    private class FingerprintRepository(private val config: Config) : HostKeyRepository {
        private val probe = JSch()
        private var pending: HostKey? = null

        fun pendingFingerprint(): String? = pending?.let { it.getFingerPrint(probe) }

        override fun getKnownHostsRepositoryID(): String = "blogpad"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

        override fun check(host: String?, key: ByteArray): Int {
            if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED
            val hostKey = try { HostKey(host, key) } catch (_: Exception) { return HostKeyRepository.NOT_INCLUDED }
            val fingerprint = try { hostKey.getFingerPrint(probe) } catch (_: Exception) { return HostKeyRepository.NOT_INCLUDED }
            val trusted = config.hostFingerprint.trim()
            return if (trusted.isNotEmpty() && trusted == fingerprint) {
                HostKeyRepository.OK
            } else {
                if (trusted.isEmpty()) pending = hostKey
                HostKeyRepository.NOT_INCLUDED
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {
            pending = hostkey
        }

        override fun remove(host: String, type: String) { }

        override fun remove(host: String, type: String, key: ByteArray?) { }
    }
}
