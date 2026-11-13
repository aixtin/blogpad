package io.github.aixtin.blogpad

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 一份完整配置：SSH 连接 + Hugo 站点信息。
 * 所有字段均为可改，不预置任何特定服务器，便于开源后他人复用。
 */
data class Config(
    var host: String = "",
    var port: Int = 22,
    var user: String = "",
    var authType: String = "key",
    var privateKey: String = "",
    var passphrase: String = "",
    var password: String = "",
    var siteRoot: String = "",
    var postDir: String = "content/post",
    var buildCmd: String = "hugo --gc --minify",
    var siteUrl: String = "",
    var linkTemplate: String = "",
    var hostFingerprint: String = "",
    /** 附件库根目录，相对站点根，必须落在 static 下 */
    var mediaRoot: String = DEFAULT_MEDIA_ROOT,
    /** 是否按类型分子目录；关掉则所有附件平铺在 mediaRoot */
    var mediaGrouping: Boolean = true,
    /** 四个分类子目录名，逗号分隔：图片、视频、音频、封面 */
    var mediaSubdirs: String = DEFAULT_MEDIA_SUBDIRS
) {

    /** 分类子目录名，索引对应 MediaKind.index；缺项回落到默认名 */
    fun mediaSubdir(kind: MediaKind): String {
        val names = mediaSubdirs.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return names.getOrNull(kind.index) ?: kind.fallbackName
    }

    /** 附件在站点内的物理目录（相对 siteRoot） */
    fun mediaDir(kind: MediaKind): String {
        val root = mediaRoot.trim().trim('/').ifEmpty { DEFAULT_MEDIA_ROOT }
        return if (mediaGrouping) "$root/${mediaSubdir(kind)}" else root
    }

    /** 附件库的站内 URL 根（根相对），由 mediaRoot 推导：static/upload -> /upload */
    fun mediaUrlRoot(): String {
        val physical = mediaRoot.trim().trim('/').ifEmpty { DEFAULT_MEDIA_ROOT }
        val rel = if (physical == "static") "" else physical.removePrefix("static").trim('/')
        return if (rel.isEmpty()) "" else "/$rel"
    }

    /** 该附件的站内 URL 目录（根相对），由物理目录推导：static/upload/images -> /upload/images */
    fun mediaUrlDir(kind: MediaKind): String {
        val physical = mediaDir(kind)
        val rel = if (physical == "static") "" else physical.removePrefix("static").trim('/')
        return if (rel.isEmpty()) "" else "/$rel"
    }

    /** 附件库配置校验；返回 null 表示可用 */
    fun mediaReady(): String? {
        val raw = mediaRoot.trim()
        if (raw.isEmpty()) return "请填写附件库目录（默认 static/upload）"
        if (raw.startsWith("/")) return "附件库目录要写相对站点根的路径，不能以 / 开头"
        if (raw.split('/').any { it == ".." }) return "附件库目录不能包含 ..，不允许跳出站点根目录"
        if (raw == "assets" || raw.startsWith("assets/")) {
            return "assets/ 不是公开路径，放进去的附件会被构建丢弃（Markdown 引用 404）。请改用 static/ 下的目录"
        }
        if (raw != "static" && !raw.startsWith("static/")) {
            return "附件库必须位于 static/ 下，否则 Hugo 不会复制到站点根"
        }
        if (mediaGrouping) {
            val names = mediaSubdirs.split(',', '，', ';', '；').map { it.trim() }
            if (names.size < MediaKind.values().size || names.any { it.isEmpty() }) {
                return "分类子目录要填满 4 个：图片、视频、音频、封面"
            }
            if (names.any { it.contains('/') || it == ".." }) {
                return "分类子目录只能写单层目录名，不能带 / 或 .."
            }
        }
        return null
    }

    fun connectionReady(): String? = when {
        host.isBlank() -> "请先在设置里填写主机地址"
        user.isBlank() -> "请先在设置里填写用户名"
        authType == "key" && privateKey.isBlank() -> "请先导入 SSH 私钥"
        authType == "password" && password.isBlank() -> "请先填写登录密码"
        else -> null
    }

    fun siteReady(): String? = when {
        siteRoot.isBlank() -> "请先在设置里填写站点根目录"
        postDir.isBlank() -> "请先在设置里填写文章目录"
        buildCmd.isBlank() -> "请先在设置里填写构建命令"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("host", host); put("port", port); put("user", user)
        put("authType", authType); put("privateKey", privateKey)
        put("passphrase", passphrase); put("password", password)
        put("siteRoot", siteRoot); put("postDir", postDir)
        put("buildCmd", buildCmd); put("siteUrl", siteUrl)
        put("linkTemplate", linkTemplate); put("hostFingerprint", hostFingerprint)
        put("mediaRoot", mediaRoot); put("mediaGrouping", mediaGrouping)
        put("mediaSubdirs", mediaSubdirs)
    }

    companion object {
        const val DEFAULT_MEDIA_ROOT = "static/upload"
        const val DEFAULT_MEDIA_SUBDIRS = "images,videos,audio,covers"

        fun fromJson(o: JSONObject): Config = Config(
            host = o.optString("host", ""),
            port = o.optInt("port", 22),
            user = o.optString("user", ""),
            authType = o.optString("authType", "key"),
            privateKey = o.optString("privateKey", ""),
            passphrase = o.optString("passphrase", ""),
            password = o.optString("password", ""),
            siteRoot = o.optString("siteRoot", ""),
            postDir = o.optString("postDir", "content/post"),
            buildCmd = o.optString("buildCmd", "hugo --gc --minify"),
            siteUrl = o.optString("siteUrl", ""),
            linkTemplate = o.optString("linkTemplate", ""),
            hostFingerprint = o.optString("hostFingerprint", ""),
            mediaRoot = o.optString("mediaRoot", DEFAULT_MEDIA_ROOT),
            mediaGrouping = o.optBoolean("mediaGrouping", true),
            mediaSubdirs = o.optString("mediaSubdirs", DEFAULT_MEDIA_SUBDIRS)
        )
    }
}

/** 附件类型：决定落到哪个子目录、插入什么 Markdown 语法 */
enum class MediaKind(val index: Int, val fallbackName: String, val label: String) {
    IMAGE(0, "images", "图片"),
    VIDEO(1, "videos", "视频"),
    AUDIO(2, "audio", "音频"),
    COVER(3, "covers", "封面");

    companion object {
        fun of(mime: String?): MediaKind = when {
            mime == null -> IMAGE
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("audio/") -> AUDIO
            else -> IMAGE
        }
    }
}

/**
 * 配置持久化：JSON 文本经 Android Keystore 的 AES/GCM 加密后落 SharedPreferences，
 * 私钥、口令、密码不会以明文留在磁盘上。
 */
object ConfigStore {
    private const val PREFS = "blogpad_prefs"
    private const val STORE_KEY = "config_json"
    private const val KEY_ALIAS = "blogpad_master_key"
    private const val GCM_TAG_BITS = 128

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(STORE_KEY, null) ?: return Config()
        return try {
            Config.fromJson(JSONObject(decrypt(raw)))
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(context: Context, config: Config) {
        val cipherText = encrypt(config.toJson().toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(STORE_KEY, cipherText).apply()
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteArray(4 + iv.size + body.size)
        packed[0] = (iv.size ushr 24).toByte()
        packed[1] = (iv.size ushr 16).toByte()
        packed[2] = (iv.size ushr 8).toByte()
        packed[3] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 4, iv.size)
        System.arraycopy(body, 0, packed, 4 + iv.size, body.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
        val iv = data.copyOfRange(4, 4 + ivLength)
        val body = data.copyOfRange(4 + ivLength, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}
