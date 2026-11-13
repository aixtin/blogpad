package io.github.aixtin.blogpad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 只读结构预览：把 Markdown 源码渲染成块级视图。
 *
 * 只回答「内容是否规范」——标题层级、列表、代码围栏、引用、链接、图片是否存在；
 * 不追主题样式，也不引第三方 Markdown 库（保持离线构建）。
 * 解析刻意保守：认不出的语法原样当段落显示，不吞内容。
 */
object MarkdownPreview {

    private const val C_TEXT = "#1A1A1E"
    private const val C_MUTED = "#9AA0A8"
    private const val C_ACCENT = "#2F6FED"
    private const val C_CODE_BG = "#F2F3F5"
    private const val C_LINE = "#E3E5E8"

    private val BULLET = Regex("^([-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val IMAGE_LINE = Regex("^!\\[([^\\]]*)\\]\\(([^)\\s]*)\\)$")

    fun render(
        container: LinearLayout,
        markdown: String,
        density: Float,
        onLink: (String) -> Unit
    ) {
        val view = View1(container, density, onLink)
        container.removeAllViews()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        if (markdown.isBlank()) {
            view.paragraph("正文为空，没有可预览的内容。", muted = true)
            return
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            when {
                t.isEmpty() -> i++

                t.startsWith("```") -> {
                    val lang = t.removePrefix("```").trim()
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i++
                    }
                    if (i < lines.size) i++
                    view.code(body.toString(), lang)
                }

                isDivider(t) -> {
                    view.divider()
                    i++
                }

                headingLevel(t) > 0 -> {
                    view.heading(headingLevel(t), t.substring(headingLevel(t) + 1).trim())
                    i++
                }

                t.startsWith(">") -> {
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        if (quote.isNotEmpty()) quote.append('\n')
                        quote.append(lines[i].trim().removePrefix(">").trim())
                        i++
                    }
                    view.quote(quote.toString())
                }

                BULLET.matches(t) -> {
                    val consumed = ArrayList<Pair<Int, String>>()
                    while (i < lines.size) {
                        val cur = lines[i]
                        val ct = cur.trim()
                        if (!BULLET.matches(ct)) break
                        val level = ((cur.length - cur.trimStart().length) / 2).coerceIn(0, 4)
                        consumed.add(level to ct)
                        i++
                    }
                    view.list(consumed)
                }

                IMAGE_LINE.matches(t) -> {
                    val m = IMAGE_LINE.find(t)!!
                    view.imageBlock(m.groupValues[1], m.groupValues[2])
                    i++
                }

                t.startsWith("|") -> {
                    val rows = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows.add(lines[i].trim())
                        i++
                    }
                    view.table(rows)
                }

                else -> {
                    val para = StringBuilder()
                    while (i < lines.size) {
                        val cur = lines[i]
                        val ct = cur.trim()
                        if (ct.isEmpty()) break
                        if (ct.startsWith("```") || ct.startsWith(">") || ct.startsWith("|")) break
                        if (headingLevel(ct) > 0 || isDivider(ct) || BULLET.matches(ct)) break
                        if (IMAGE_LINE.matches(ct) && para.isNotEmpty()) break
                        if (para.isNotEmpty()) para.append('\n')
                        para.append(ct)
                        i++
                    }
                    if (para.isEmpty()) i++ else view.paragraph(para.toString(), muted = false)
                }
            }
        }
    }

    private fun headingLevel(line: String): Int {
        var n = 0
        while (n < line.length && n < 6 && line[n] == '#') n++
        return if (n in 1..6 && line.length > n && line[n] == ' ') n else 0
    }

    private fun isDivider(line: String): Boolean {
        if (line.length < 3) return false
        val c = line[0]
        if (c != '-' && c != '*' && c != '_') return false
        return line.all { it == c }
    }

    // ---------- 行内解析 ----------

    private fun inline(text: String, onLink: (String) -> Unit): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("![", i)) {
                val close = text.indexOf("](", i + 2)
                if (close > 0) {
                    val end = text.indexOf(')', close + 2)
                    if (end > 0) {
                        val alt = text.substring(i + 2, close)
                        val url = text.substring(close + 2, end)
                        sb.append("[图片 ").append(if (alt.isBlank()) url else alt).append(']')
                        i = end + 1
                        continue
                    }
                }
            }
            if (text[i] == '[') {
                val close = text.indexOf("](", i + 1)
                if (close > 0) {
                    val end = text.indexOf(')', close + 2)
                    if (end > 0) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end).trim()
                        val start = sb.length
                        sb.append(label)
                        sb.setSpan(linkSpan(url, onLink), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                        continue
                    }
                }
            }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = sb.length
                    sb.append(text.substring(i + 1, end))
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(
                        BackgroundColorSpan(Color.parseColor("#EEF0F3")),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    i = end + 1
                    continue
                }
            }
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val mark = text.substring(i, i + 2)
                val end = text.indexOf(mark, i + 2)
                if (end > i + 2) {
                    val start = sb.length
                    sb.append(text.substring(i + 2, end))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            if (text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val start = sb.length
                    sb.append(text.substring(i + 2, end))
                    sb.setSpan(
                        android.text.style.StrikethroughSpan(),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    i = end + 2
                    continue
                }
            }
            val c = text[i]
            if (c == '*' || c == '_') {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    val start = sb.length
                    sb.append(text.substring(i + 1, end))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb
    }

    private fun linkSpan(url: String, onLink: (String) -> Unit): ClickableSpan =
        object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (url.isNotEmpty()) onLink(url)
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.color = Color.parseColor(C_ACCENT)
                ds.isUnderlineText = true
            }
        }

    // ---------- 图片加载 ----------

    /** 预览图缓存按字节计，12MB 上限足够滚动十几张封面 */
    private val imageCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 固定 3 线程：够滚动时预取，又不会因为长文里几十张图把设备拖死 */
    private val imagePool = Executors.newFixedThreadPool(3)

    /**
     * 取图。远端走 HTTP，本地走 file:// / content://。
     * 先读字节再按 inSampleSize 降采样解码——直接 decodeStream 大图会 OOM。
     */
    private fun fetchBitmap(context: android.content.Context, url: String, maxPx: Int): Bitmap? {
        imageCache.get(url)?.let { return it }
        val bytes = readBytes(context, url) ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > maxPx && sample < 16) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        imageCache.put(url, bitmap)
        return bitmap
    }

    private fun readBytes(context: android.content.Context, url: String): ByteArray? = try {
        when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "BlogPad/1.6 (Android)")
                }
                try {
                    if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }
            url.startsWith("content://") ->
                context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
            url.startsWith("file://") ->
                Uri.parse(url).path?.let { File(it).takeIf { f -> f.isFile }?.readBytes() }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** 站内路径只有发布后才存在，本地预览无法解码，走占位提示而不是当成加载失败 */
    private fun isSitePath(url: String): Boolean =
        url.startsWith("/") && !url.startsWith("//")

    // ---------- 块视图 ----------

    private class View1(
        private val box: LinearLayout,
        private val density: Float,
        private val onLink: (String) -> Unit
    ) {
        private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        private fun attach(view: View, top: Int) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(top)
            box.addView(view, lp)
        }

        private fun label(text: CharSequence, size: Float, color: String, bold: Boolean = false): TextView {
            val tv = TextView(box.context)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            tv.setTextColor(Color.parseColor(color))
            tv.setLineSpacing(dp(3).toFloat(), 1.35f)
            tv.text = text
            if (bold) tv.setTypeface(null, Typeface.BOLD)
            return tv
        }

        fun heading(level: Int, text: String) {
            val size = when (level) {
                1 -> 22f
                2 -> 19f
                3 -> 17f
                4 -> 15.5f
                else -> 15f
            }
            val tv = label(inline(text, onLink), size, C_TEXT, bold = true)
            tv.movementMethod = LinkMovementMethod.getInstance()
            attach(tv, if (level <= 2) 18 else 14)
        }

        fun paragraph(text: String, muted: Boolean) {
            val tv = label(inline(text, onLink), 15f, if (muted) C_MUTED else C_TEXT)
            tv.movementMethod = LinkMovementMethod.getInstance()
            attach(tv, 10)
        }

        fun list(items: List<Pair<Int, String>>) {
            val column = LinearLayout(box.context)
            column.orientation = LinearLayout.VERTICAL
            items.forEach { (level, line) ->
                val m = BULLET.find(line) ?: return@forEach
                val marker = m.groupValues[1]
                val content = m.groupValues[2]
                val row = LinearLayout(box.context)
                row.orientation = LinearLayout.HORIZONTAL
                val indent = dp(level * 16)
                row.setPadding(indent, dp(3), 0, dp(3))

                val markerView = label(marker, 14f, C_MUTED)
                val mlp = LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT)
                row.addView(markerView, mlp)

                val contentView = label(inline(content, onLink), 15f, C_TEXT)
                contentView.movementMethod = LinkMovementMethod.getInstance()
                val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(contentView, clp)

                column.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            attach(column, 8)
        }

        fun quote(text: String) {
            val holder = LinearLayout(box.context)
            holder.orientation = LinearLayout.HORIZONTAL
            val bar = View(box.context)
            bar.setBackgroundColor(Color.parseColor("#D5D8DE"))
            holder.addView(bar, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))

            val content = label(inline(text, onLink), 14.5f, "#5B6068")
            content.movementMethod = LinkMovementMethod.getInstance()
            content.setPadding(dp(12), dp(2), 0, dp(2))
            holder.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            attach(holder, 10)
        }

        fun code(text: String, lang: String) {
            if (lang.isNotEmpty()) {
                val tag = label("代码 · $lang", 11.5f, C_MUTED)
                attach(tag, 12)
            }
            val scroller = HorizontalScrollView(box.context)
            scroller.isFillViewport = true
            scroller.setBackgroundColor(Color.parseColor(C_CODE_BG))
            val body = TextView(box.context)
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            body.setTextColor(Color.parseColor("#24262B"))
            body.typeface = Typeface.MONOSPACE
            body.text = if (text.isEmpty()) "（空代码块）" else text
            body.setPadding(dp(12), dp(10), dp(12), dp(10))
            scroller.addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            attach(scroller, if (lang.isEmpty()) 12 else 4)
        }

        fun table(rows: List<String>) {
            val tag = label("表格 · ${rows.size} 行", 11.5f, C_MUTED)
            attach(tag, 12)
            val scroller = HorizontalScrollView(box.context)
            scroller.isFillViewport = true
            scroller.setBackgroundColor(Color.parseColor(C_CODE_BG))
            val body = TextView(box.context)
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            body.setTextColor(Color.parseColor("#24262B"))
            body.typeface = Typeface.MONOSPACE
            body.text = rows.joinToString("\n")
            body.setPadding(dp(12), dp(10), dp(12), dp(10))
            scroller.addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            attach(scroller, 4)
        }

        fun imageBlock(alt: String, url: String) {
            val caption = if (alt.isBlank()) "图片" else alt
            if (isSitePath(url) || url.isBlank()) {
                // 站内路径要等构建后才生效，这里只报路径对不对，不伪装成一张图
                val holder = LinearLayout(box.context)
                holder.orientation = LinearLayout.VERTICAL
                holder.setBackgroundColor(Color.parseColor("#EEF0F3"))
                holder.setPadding(dp(12), dp(10), dp(12), dp(10))
                holder.addView(label("站内图片 · $caption", 12.5f, "#3C4149"))
                holder.addView(label(url.ifBlank { "（缺少路径）"} + " · 发布后可见", 11.5f, C_MUTED))
                attach(holder, 10)
                return
            }

            val holder = LinearLayout(box.context)
            holder.orientation = LinearLayout.VERTICAL
            holder.addView(label(caption, 11.5f, C_MUTED))

            val image = ImageView(box.context)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.adjustViewBounds = true
            image.setBackgroundColor(Color.parseColor("#EEF0F3"))
            holder.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(120)
            ))
            attach(holder, 10)

            val target = (360 * density).toInt().coerceAtLeast(540)
            imagePool.execute {
                val bitmap = runCatching { fetchBitmap(box.context, url, target) }.getOrNull()
                image.post {
                    // 预览可能已经切走重建，view 脱树后不再回填，避免脏写
                    if (image.parent == null) return@post
                    if (bitmap != null) {
                        image.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        image.setImageBitmap(bitmap)
                        image.setBackgroundColor(Color.TRANSPARENT)
                    } else {
                        holder.addView(label("无法加载：$url", 11f, C_MUTED))
                    }
                }
            }
        }

        fun divider() {
            val line = View(box.context)
            line.setBackgroundColor(Color.parseColor(C_LINE))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            lp.topMargin = dp(14)
            lp.bottomMargin = dp(2)
            box.addView(line, lp)
        }
    }
}
