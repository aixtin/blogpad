package io.github.aixtin.blogpad

import android.content.Context

/**
 * 封面裁剪的预设比例，只存一条，全局共用。
 *
 * 为什么不放设置页：比例只有裁封面那一刻才有意义，摆到设置里等于要求用户先想起
 * "我主题的封面是几比几"，再去一个跟他此刻无关的页面填。放在裁剪页右上角，
 * 他看图的同时就能定，填一次之后一直用。
 */
object CoverRatioStore {

    private const val PREFS = "blogpad_cover_ratio"
    private const val KEY = "ratio"

    const val DEFAULT = "16:9"

    fun load(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = parse(raw) ?: return DEFAULT
        return ratioLabel(parsed.first, parsed.second)
    }

    fun save(context: Context, w: Float, h: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, ratioLabel(w, h)).apply()
    }

    /**
     * 解析用户填的比例。16:9 / 16/9 / 16：9 / 1.78 / 1.78:1 都认，认不出返回 null，
     * 由调用方回落成 16:9——填错不该让人卡在一个弹窗里出不来。
     */
    fun parse(text: String?): Pair<Float, Float>? {
        val raw = text?.trim()?.replace('：', ':')?.replace('／', '/')?.replace('x', ':')?.replace('X', ':')
        if (raw.isNullOrEmpty()) return null
        val cut = raw.split(':', '/').map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            cut.size >= 2 -> {
                val w = cut[0].toFloatOrNull()
                val h = cut[1].toFloatOrNull()
                if (w != null && h != null && w > 0f && h > 0f) w to h else null
            }
            cut.size == 1 -> {
                val v = cut[0].toFloatOrNull()
                if (v != null && v > 0f) v to 1f else null
            }
            else -> null
        }
    }
}

/** 比例文字：化简得开就写 16:9，化简不开（比如 1207:679）就写 1.78:1 */
fun ratioLabel(w: Float, h: Float): String {
    if (w <= 0f || h <= 0f) return "—"
    val iw = Math.round(w)
    val ih = Math.round(h)
    val g = gcd(iw, ih)
    val a = iw / g
    val b = ih / g
    return if (a in 1..60 && b in 1..60) "$a:$b"
    else String.format(java.util.Locale.US, "%.2f:1", w / h)
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) Math.max(1, Math.abs(a)) else gcd(b, a % b)
