package io.github.aixtin.blogpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 从远端文章聚合出来的分类/标签词表，只用来给编辑页出 chip 提示 */
data class Taxonomy(
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val updatedAt: Long = 0L
) {
    val isEmpty: Boolean get() = categories.isEmpty() && tags.isEmpty()
}

/**
 * 词表本地缓存：列表页拉到文章后写入，编辑页读出来渲染 chip。
 * 不参与保存逻辑——写进 front matter 的永远是输入框里的文本，词表只是快捷输入。
 * 用的是和草稿缓存同一套 SharedPreferences 思路，明文 JSON，没有敏感信息。
 */
object TaxonomyStore {

    private const val PREFS = "blogpad_taxonomy"
    private const val KEY_PAYLOAD = "payload"

    fun load(context: Context): Taxonomy {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null)
            ?: return Taxonomy()
        return try {
            val obj = JSONObject(raw)
            Taxonomy(
                categories = readArray(obj.optJSONArray("categories")),
                tags = readArray(obj.optJSONArray("tags")),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        } catch (_: Exception) {
            Taxonomy()
        }
    }

    fun save(context: Context, taxonomy: Taxonomy) {
        val obj = JSONObject()
        obj.put("categories", JSONArray(taxonomy.categories))
        obj.put("tags", JSONArray(taxonomy.tags))
        obj.put("updatedAt", System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAYLOAD, obj.toString())
            .apply()
    }

    /** 保存成功后就地并入这次用到的值，不必等下一次列表刷新 */
    fun merge(context: Context, categories: List<String>, tags: List<String>): Taxonomy {
        val current = load(context)
        val next = Taxonomy(
            categories = merged(current.categories, categories),
            tags = merged(current.tags, tags),
            updatedAt = System.currentTimeMillis()
        )
        save(context, next)
        return next
    }

    private fun merged(base: List<String>, extra: List<String>): List<String> {
        val out = ArrayList(base)
        extra.forEach { if (!out.contains(it)) out.add(it) }
        return out
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
