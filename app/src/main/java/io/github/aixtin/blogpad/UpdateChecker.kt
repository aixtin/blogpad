package io.github.aixtin.blogpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 版本检查：只看 GitHub Releases 的 latest 跳转（/releases/latest → /releases/tag/vX.Y.Z），
 * 不走 API，避免匿名接口限流。
 *
 * 自动检查每天最多一次，发现新版本由首页弹一条几秒后自动消失的提示；
 * 关于页的手动检查不节流，结果直接写在页面上。
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/aixtin/blogpad"
    const val ISSUES_URL = "$REPO_URL/issues"
    const val BLOG_URL = "https://atin.asia"
    private const val LATEST_URL = "$REPO_URL/releases/latest"

    private const val PREFS = "app_prefs"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_PROMPTED = "update_prompted_version"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

    sealed class Result {
        /** 仓库有发布：version 形如 v1.6.0 */
        data class Found(val version: String) : Result()
        object NoRelease : Result()
        object Error : Result()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull().orEmpty()

    /** 手动检查：每次都真的联网，回调在主线程 */
    fun check(callback: (Result) -> Unit) {
        executor.execute {
            val result = fetchLatest()
            main.post { callback(result) }
        }
    }

    /**
     * 启动时自动检查：24 小时内只查一次，同一个版本只提示一次。
     * 失败静默，不打扰用户。
     */
    fun autoCheck(ctx: Context, onNewVersion: (String) -> Unit) {
        val app = ctx.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < ONE_DAY_MS) return

        executor.execute {
            val result = fetchLatest()
            main.post {
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                if (result !is Result.Found) return@post
                val current = currentVersion(app)
                if (!isNewer(result.version, current)) return@post
                if (result.version == prefs.getString(KEY_PROMPTED, null)) return@post
                prefs.edit().putString(KEY_PROMPTED, result.version).apply()
                onNewVersion(result.version)
            }
        }
    }

    /** 版本号比较：latest 比 current 新返回 true，支持 v1.2 / 1.2.0 等写法 */
    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    /** 下载地址按仓库约定：blogpad-vX.Y.Z.apk */
    fun downloadUrl(version: String): String =
        "$REPO_URL/releases/download/$version/blogpad-$version.apk"

    private fun fetchLatest(): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/html")
            when (conn.responseCode) {
                404 -> Result.NoRelease
                in 300..399 -> {
                    val location = conn.getHeaderField("Location").orEmpty()
                    val tag = location.substringAfterLast("/releases/tag/", "")
                    // 没有发布版本时 GitHub 会跳到 /releases，此时取不到 tag
                    if (tag.isBlank()) {
                        Result.NoRelease
                    } else {
                        Result.Found(URLDecoder.decode(tag, "UTF-8"))
                    }
                }
                else -> Result.Error
            }
        } catch (e: Exception) {
            Result.Error
        } finally {
            conn?.disconnect()
        }
    }

    private fun compare(a: String, b: String): Int {
        val pa = a.replace(Regex("[^0-9.]"), "").split(".").filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
        val pb = b.replace(Regex("[^0-9.]"), "").split(".").filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}
