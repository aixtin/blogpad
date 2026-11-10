package io.github.aixtin.blogpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 列表的本地快照：网络不通时拿它顶上，同时也给编辑页的词表兜底 */
data class PostCache(
    val posts: List<PostMeta>,
    val savedAt: Long
)

/**
 * 文章列表的离线缓存。
 *
 * 只是远端目录的一次投影副本，唯一的用途是断网/连不上时还能看到上次的样子，
 * 所以任何写操作（保存、删除、改状态）都必须联网，缓存不参与也不抵消这些操作。
 * 明文 JSON 存 SharedPreferences，内容与远端 md 头部同源，没有凭据。
 */
object PostCacheStore {

    private const val PREFS = "blogpad_post_cache"
    private const val KEY_PAYLOAD = "payload"

    /** 上限只是防止异常情况下无限膨胀；正常博客远达不到这个量级 */
    private const val MAX_POSTS = 800

    fun load(context: Context): PostCache? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val array = obj.optJSONArray("posts") ?: return null
            val posts = ArrayList<PostMeta>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                posts.add(
                    PostMeta(
                        path = item.optString("path"),
                        slug = item.optString("slug"),
                        title = item.optString("title"),
                        date = item.optString("date"),
                        draft = item.optBoolean("draft", false),
                        tags = readArray(item.optJSONArray("tags")),
                        categories = readArray(item.optJSONArray("categories"))
                    )
                )
            }
            if (posts.isEmpty()) null else PostCache(posts, obj.optLong("savedAt", 0L))
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, posts: List<PostMeta>) {
        if (posts.isEmpty()) return
        val array = JSONArray()
        posts.take(MAX_POSTS).forEach { post ->
            val item = JSONObject()
            item.put("path", post.path)
            item.put("slug", post.slug)
            item.put("title", post.title)
            item.put("date", post.date)
            item.put("draft", post.draft)
            item.put("tags", JSONArray(post.tags))
            item.put("categories", JSONArray(post.categories))
            array.put(item)
        }
        val obj = JSONObject()
        obj.put("savedAt", System.currentTimeMillis())
        obj.put("posts", array)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAYLOAD, obj.toString())
            .apply()
    }

    /** 缓存时间显示成「11-10 14:32」，比时间戳好读，也不占宽度 */
    fun stamp(millis: Long): String {
        if (millis <= 0L) return "上次"
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return format.format(Date(millis))
    }

    private fun readArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty() && !out.contains(value)) out.add(value)
        }
        return out
    }
}
