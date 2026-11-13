package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 发布页：编辑页点「完成」后到这里补齐要进 front matter 的东西。
 *
 * 编辑页只负责「写」，slug/分类/标签/封面都是发布才需要考虑的事，
 * 混在一个页面里会把正文挤得很小，而这些东西大多数时候是空的或者沿用全站已有的。
 *
 * 分类和标签的候选只来自服务器上现有的文章（列表页已经统计过，缓存在内存），
 * 不记「上次选了什么」——文章各不相同，继承上次的选择只会制造错标签。
 */
class PublishMetaActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_CATEGORIES = "categories"
        const val EXTRA_TAGS = "tags"
        const val EXTRA_DRAFT = "draft"
        const val EXTRA_COVER = "cover"
        const val EXTRA_COVER_INBOX = "cover_inbox"
        const val EXTRA_EDITING = "editing"

        const val RESULT_SLUG = "out_slug"
        const val RESULT_CATEGORIES = "out_categories"
        const val RESULT_TAGS = "out_tags"
        const val RESULT_DRAFT = "out_draft"
        const val RESULT_COVER = "out_cover"
        const val RESULT_COVER_INBOX = "out_cover_inbox"

        private const val REQ_PICK_COVER = 2001
        private const val REQ_CROP_COVER = 2002

        /**
         * 封面预览图很小（长边 360px），按 URL 缓存在进程里。
         * 手机首次连站点头字节经常要好几秒，缓存之后反复进出发布页就不用再等第二次。
         */
        private val coverCache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvWordCount: TextView
    private lateinit var tvSlug: TextView
    private lateinit var etSlug: EditText
    private lateinit var layoutCategoryChips: LinearLayout
    private lateinit var layoutTagChips: LinearLayout
    private lateinit var ivCover: ImageView
    private lateinit var tvCoverState: TextView
    private lateinit var btnClearCover: TextView
    private lateinit var btnPublish: Button
    private lateinit var tvStatus: TextView

    private var title: String = ""
    private var body: String = ""
    private var slugValue: String = ""
    private var editing = false
    private var defaultDraft = false

    private var coverRemote: String = ""
    private var coverInboxId: String? = null

    /** 是否已经通过 setResult 交代过封面；用来避免 finish() 覆盖掉「完成」的返回值 */
    private var coverResultSent = false

    private var categoryPool: List<String> = emptyList()
    private var tagPool: List<String> = emptyList()

    /** 本次发布页里刚新建的词，只排在渲染最前面，不写回服务器词池，下次进来自动恢复默认顺序 */
    private val recentCategories = ArrayList<String>()
    private val recentTags = ArrayList<String>()
    private val selectedCategories = ArrayList<String>()
    private val selectedTags = ArrayList<String>()

    private var slugExpanded = false

    /** 远端缩略图是异步拉的，每换一次封面 token 加一，晚到的旧结果直接丢掉 */
    private var coverToken = 0

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    /**
     * 后台线程的结果统一 post 回来。页面已经关了（例如用户在「正在读取远端文章」时按返回）
     * 就直接丢掉，否则回调里弹的 AlertDialog 会撞上 BadTokenException 把 App 带走。
     */
    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            if (isFinishing || isDestroyed) return
            super.dispatchMessage(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_publish_meta)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        editing = intent.getBooleanExtra(EXTRA_EDITING, false)
        defaultDraft = intent.getBooleanExtra(EXTRA_DRAFT, false)
        coverRemote = normalizeCoverPath(intent.getStringExtra(EXTRA_COVER).orEmpty())
        val intentCoverId = intent.getStringExtra(EXTRA_COVER_INBOX)?.ifBlank { null }
        coverInboxId = intentCoverId ?: restorePendingCover()
        if (intentCoverId != null) CoverPendingStore.save(this, intentCoverId)

        slugValue = intent.getStringExtra(EXTRA_SLUG).orEmpty()
            .ifBlank { SshPublisher.resolveSlug(title) }
        splitValues(intent.getStringExtra(EXTRA_CATEGORIES).orEmpty()).forEach {
            selectedCategories.add(it)
        }
        splitValues(intent.getStringExtra(EXTRA_TAGS).orEmpty()).forEach { selectedTags.add(it) }

        tvTitle = findViewById(R.id.tvTitle)
        tvWordCount = findViewById(R.id.tvWordCount)
        tvSlug = findViewById(R.id.tvSlug)
        etSlug = findViewById(R.id.etSlug)
        layoutCategoryChips = findViewById(R.id.layoutCategoryChips)
        layoutTagChips = findViewById(R.id.layoutTagChips)
        findViewById<View>(R.id.btnNewCategory).setOnClickListener { addCategory() }
        findViewById<View>(R.id.btnNewTag).setOnClickListener { addTag() }
        ivCover = findViewById(R.id.ivCover)
        ivCover.setOnClickListener { showCoverPreview() }
        tvCoverState = findViewById(R.id.tvCoverState)
        btnClearCover = findViewById(R.id.btnClearCover)
        btnPublish = findViewById(R.id.btnPublish)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<TextView>(R.id.tvHeader).text =
            getString(if (editing) R.string.publish_header_edit else R.string.publish_title)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        tvTitle.text = title.ifBlank { "（无标题）" }
        tvWordCount.text = "正文 ${body.length} 字 · 标题 ${title.length} 字"

        etSlug.setText(slugValue)
        tvSlug.setOnClickListener { toggleSlug() }
        updateSlugLabel()

        findViewById<View>(R.id.btnPickCover).setOnClickListener { pickCover() }
        btnClearCover.setOnClickListener {
            // 本地暂存的那份也要删掉，否则它会一直躺在暂存区里占着空间
            coverInboxId?.let { old -> AttachmentInbox.remove(this, listOf(old)) }
            CoverPendingStore.clear(this)
            coverRemote = ""
            coverInboxId = null
            updateCover()
        }

        // 主按钮一律「发布」，左边那个才是「存为草稿」。
        // 编辑一篇草稿时两个入口正好对应「转正」和「继续留着」，不需要第三种含义。
        btnPublish.setText(
            if (editing && !defaultDraft) R.string.btn_publish_and_save
            else R.string.btn_publish
        )
        btnPublish.setOnClickListener { finishWith(draft = false) }
        findViewById<View>(R.id.btnSaveDraft).setOnClickListener { finishWith(draft = true) }

        renderChips()
        updateCover()
        loadTaxonomy()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ---------- 词表 ----------

    /**
     * 候选词只来自服务器上已有的文章。
     * 列表页刚统计过，直接读缓存；没有缓存（比如冷启动直接进了编辑页）才现场拉一次。
     */
    private fun loadTaxonomy() {
        val cached = PostCacheStore.load(this)
        if (cached != null) {
            applyTaxonomy(PostRepository.buildTaxonomy(cached.posts))
            return
        }
        val config = ConfigStore.load(this)
        if (config.connectionReady() != null) return
        if (config.siteReady() != null) return
        showStatus("正在读取站点已有的分类和标签…")
        worker.execute {
            val taxonomy = try {
                PostRepository.buildTaxonomy(PostRepository.listPosts(config))
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                showStatus("")
                if (taxonomy != null) applyTaxonomy(taxonomy)
            }
        }
    }

    private fun applyTaxonomy(taxonomy: Taxonomy) {
        categoryPool = taxonomy.categories
        tagPool = taxonomy.tags
        renderChips()
    }

    private fun renderChips() {
        renderChipRow(
            layoutCategoryChips, categoryPool, recentCategories, selectedCategories,
            multiSelect = false
        ) { renderChips() }
        renderChipRow(
            layoutTagChips, tagPool, recentTags, selectedTags,
            multiSelect = true
        ) { renderChips() }
    }

    private fun renderChipRow(
        container: LinearLayout,
        pool: List<String>,
        recent: List<String>,
        selected: MutableList<String>,
        multiSelect: Boolean,
        onChanged: () -> Unit
    ) {
        // 刚新建的排最前（用户新建完就想立刻看到自己选中了），其余保持服务器给的默认顺序
        val values = ArrayList<String>()
        recent.forEach { if (!values.contains(it)) values.add(it) }
        pool.forEach { if (!values.contains(it)) values.add(it) }
        selected.forEach { if (!values.contains(it)) values.add(it) }

        container.removeAllViews()
        values.forEach { value ->
            val isOn = selected.contains(value)
            container.addView(makeChip(value, isOn) {
                if (isOn) {
                    selected.remove(value)
                } else {
                    if (!multiSelect) selected.clear()
                    selected.add(value)
                }
                onChanged()
            })
        }
    }

    /** 「＋ 新建」固定在每行最左边，不参与横向滚动，滚到哪都在 */
    private fun addCategory() {
        val values = knownValues(categoryPool, recentCategories, selectedCategories)
        promptNew("新建分类", values) { picked ->
            promoteRecent(recentCategories, picked)
            selectedCategories.clear()
            selectedCategories.add(picked)
            renderChips()
            scrollToStart(layoutCategoryChips)
        }
    }

    private fun addTag() {
        val values = knownValues(tagPool, recentTags, selectedTags)
        promptNew("新建标签", values) { picked ->
            promoteRecent(recentTags, picked)
            if (!selectedTags.contains(picked)) selectedTags.add(picked)
            renderChips()
            scrollToStart(layoutTagChips)
        }
    }

    private fun knownValues(
        pool: List<String>,
        recent: List<String>,
        selected: List<String>
    ): List<String> {
        val values = ArrayList<String>()
        recent.forEach { if (!values.contains(it)) values.add(it) }
        pool.forEach { if (!values.contains(it)) values.add(it) }
        selected.forEach { if (!values.contains(it)) values.add(it) }
        return values
    }

    /** 置顶而非追加：重复点同一个词也只是把它挪到开头 */
    private fun promoteRecent(recent: MutableList<String>, value: String) {
        recent.removeAll { it.equals(value, ignoreCase = true) }
        recent.add(0, value)
    }

    /** chip 行外层的滚动容器，新词插到最前时回滚到行首，保证用户一眼看到刚建的那个 */
    private fun scrollToStart(chips: View) {
        val scroller = chips.parent as? HorizontalScrollView ?: return
        scroller.post { scroller.fullScroll(View.FOCUS_LEFT) }
    }

    private fun promptNew(heading: String, existing: List<String>, onPick: (String) -> Unit) {
        val input = EditText(this)
        input.hint = "名称"
        input.setSingleLine(true)
        val container = LinearLayout(this)
        container.setPadding(dp(20), dp(8), dp(20), 0)
        container.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(heading)
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                input.error = "名称不能为空"
                return@setOnClickListener
            }
            // 同名（不区分大小写）已经存在就直接选中，不重复建一个只差大小写的词
            val hit = existing.firstOrNull { it.equals(value, ignoreCase = true) } ?: value
            dialog.dismiss()
            onPick(hit)
        }
        input.requestFocus()
    }

    private fun makeChip(value: String, selected: Boolean, onClick: () -> Unit): TextView {
        val chip = TextView(this)
        chip.text = value
        chip.textSize = 13f
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(12), dp(6), dp(12), dp(6))
        chip.setTextColor(getColorStateList(R.color.chip_text))
        chip.setBackgroundResource(R.drawable.bg_chip)
        chip.isSelected = selected
        chip.isClickable = true
        chip.isFocusable = true
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(8)
        chip.layoutParams = lp
        chip.setOnClickListener { onClick() }
        return chip
    }

    // ---------- slug ----------

    private fun toggleSlug() {
        slugExpanded = !slugExpanded
        etSlug.visibility = if (slugExpanded) View.VISIBLE else View.GONE
        if (slugExpanded) {
            etSlug.setText(slugValue)
            etSlug.requestFocus()
        } else {
            slugValue = etSlug.text.toString()
        }
        updateSlugLabel()
    }

    private fun updateSlugLabel() {
        val preview = SshPublisher.resolveSlug(slugValue.ifBlank { title })
        tvSlug.text = "/archives/$preview/"
        tvSlug.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (slugExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
            0
        )
    }

    // ---------- 封面 ----------

    /** 选完图到裁剪页之间先记住原图，裁剪页解不开时用它兜底 */
    private var pendingCoverUri: Uri? = null

    /** 封面与编辑页「插入附件 → 相册」共用同一个相册选择器，只额外过滤掉视频 */
    private fun pickCover() {
        val picker = MediaPick.photoPicker(imagesOnly = true, max = 1)
        if (picker != null) {
            try {
                startActivityForResult(picker, REQ_PICK_COVER)
                return
            } catch (_: Exception) {
                // 机型缺这个组件，落到下面的文档选择器
            }
        }
        try {
            startActivityForResult(
                MediaPick.documentPicker(MediaPick.imageMimes, multiple = false),
                REQ_PICK_COVER
            )
        } catch (_: Exception) {
            showStatus("没有可用的文件选择器")
        }
    }

    @Deprecated("纯 Activity 没有 registerForActivityResult，这里继续用旧回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_COVER) {
            if (resultCode != RESULT_OK) return
            val uri: Uri = data?.data ?: return
            pendingCoverUri = uri
            // 选完图先进裁剪页定构图；裁剪页起不来就直接暂存原图，别让封面设置失败
            val cropIntent = Intent(this, CoverCropActivity::class.java)
                .putExtra(CoverCropActivity.EXTRA_URI, uri.toString())
            try {
                startActivityForResult(cropIntent, REQ_CROP_COVER)
            } catch (e: Exception) {
                stashCover(uri = uri, bytes = null, name = "")
            }
            return
        }
        if (requestCode != REQ_CROP_COVER) return
        when {
            resultCode == RESULT_OK -> {
                val result = CoverCropActivity.takeResult() ?: return
                stashCover(uri = null, bytes = result.first, name = result.second)
            }
            // 裁剪页解不出这张图（不是图片、格式太怪）：退回直接暂存原图，
            // 用户主动点取消走的是 RESULT_CANCELED，那种情况什么都不做
            resultCode == RESULT_FIRST_USER -> {
                val fallback = pendingCoverUri
                if (fallback != null) stashCover(uri = fallback, bytes = null, name = "")
            }
        }
    }

    /** 把裁剪结果（或兜底的原图）塞进暂存区，并顶掉上一张还没上传的封面 */
    private fun stashCover(uri: Uri?, bytes: ByteArray?, name: String) {
        showStatus("正在暂存封面…")
        worker.execute {
            try {
                val item = if (bytes != null) {
                    AttachmentInbox.addBytes(this, bytes, name, MediaKind.COVER)
                } else {
                    AttachmentInbox.add(this, requireNotNull(uri), MediaKind.COVER)
                }
                // 换封面时把上一张还没上传的副本丢掉，避免暂存区里堆一串废图
                coverInboxId?.let { old -> AttachmentInbox.remove(this, listOf(old)) }
                mainHandler.post {
                    coverInboxId = item.id
                    // 记到盘上：在发布页裁完封面直接杀后台，再进来还能接着用
                    CoverPendingStore.save(this, item.id)
                    coverRemote = ""
                    showStatus("")
                    updateCover()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    showStatus("")
                    showError("封面暂存失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * 杀后台回来捡封面。
     *
     * 只对「新建文章、还没上传过封面」生效：编辑老文章时封面是远端的，
     * 拿暂存区里的图糊上去会把上一篇的封面盖到这篇上，比丢了更糟。
     */
    private fun restorePendingCover(): String? {
        if (editing || coverRemote.isNotBlank()) {
            CoverPendingStore.clear(this)
            return null
        }
        return CoverPendingStore.loadAlive(this)
    }

    private fun updateCover() {
        val local = AttachmentInbox.find(this, coverInboxId)
        when {
            local != null -> {
                tvCoverState.text = "${local.displayName} · 发布时上传"
                coverToken++
                ivCover.setImageBitmap(decodeThumb(local.file(this)))
                btnClearCover.visibility = View.VISIBLE
            }
            coverRemote.isNotBlank() -> {
                tvCoverState.text = coverRemote.substringAfterLast('/') + " · 已上传"
                btnClearCover.visibility = View.VISIBLE
                loadRemoteThumb(coverRemote)
            }
            else -> {
                tvCoverState.text = "未设置（选图后可自己定构图）"
                coverToken++
                ivCover.setImageDrawable(null)
                btnClearCover.visibility = View.GONE
            }
        }
    }

    /**
     * 72dp 的格子只够看出「有没有图」，看不出裁成了什么样。点一下铺满屏幕看大图，点空白处关掉。
     * 本地那份是刚裁出来的成品，直接按屏幕宽度采样读；编辑老文章时本地已经没有文件，
     * 缩略图缓存是 360px 的，糊得没法判断，所以宁可多跑一次网络取大图。
     */
    private fun showCoverPreview() {
        val local = AttachmentInbox.find(this, coverInboxId)
        if (local != null) {
            decodeLarge(local.file(this))?.let {
                showCoverDialog(it)
                return
            }
        }
        val url = coverRemote.takeIf { it.isNotBlank() }?.let { remoteCoverUrl(it) }
        if (url == null) return
        coverCache.get(url)?.let {
            showCoverDialog(it)
            return
        }
        Toast.makeText(this, "正在取大图…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val bitmap = fetchRemoteCover(url, resources.displayMetrics.widthPixels)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (bitmap != null) showCoverDialog(bitmap)
                else Toast.makeText(this, "大图没取回来，检查一下网络", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCoverDialog(bitmap: android.graphics.Bitmap) {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val pad = (resources.displayMetrics.density * 20f).toInt()
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xF2000000.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 不接点击：让事件冒泡给外层，点图上也一样能关，不用去瞄空白处
            isClickable = false
        }
        root.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { setMargins(pad, pad * 2, pad, pad * 2) }
        )
        val hint = TextView(this).apply {
            text = "点击任意处关闭"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = pad }
        )
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /** 大图按屏幕宽度采样，一张 4MB 的原图没必要整张读进来 */
    private fun decodeLarge(file: java.io.File): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val target = resources.displayMetrics.widthPixels.coerceAtLeast(720)
        var sample = 1
        while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }

    /** 只为了 72dp 的预览格子，按目标尺寸采样解码，别把 8MB 原图整张读进内存 */
    private fun decodeThumb(file: java.io.File): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }

    /**
     * 已发布的文章 front matter 里只留站内路径（/upload/covers/x.png），本地早就没这文件了，
     * 所以预览只能回源站把图取回来。取不到就留空格子——封面本身是有效的，不该因为预览失败影响发布。
     */
    private fun loadRemoteThumb(path: String) {
        val token = ++coverToken
        val name = path.substringAfterLast('/')
        val url = remoteCoverUrl(path)
        if (url == null) {
            ivCover.setImageDrawable(null)
            return
        }
        val cached = coverCache.get(url)
        if (cached != null) {
            ivCover.setImageBitmap(cached)
            tvCoverState.text = "$name · 已上传"
            return
        }
        ivCover.setImageDrawable(null)
        tvCoverState.text = "$name · 加载预览…"
        worker.execute {
            // 首连慢，失败再给一次机会，别让用户靠「退出重进」来碰运气
            val bitmap = fetchRemoteCover(url) ?: run {
                Thread.sleep(600)
                fetchRemoteCover(url)
            }
            if (bitmap != null) coverCache.put(url, bitmap)
            mainHandler.post {
                if (token != coverToken) return@post
                if (bitmap != null) {
                    ivCover.setImageBitmap(bitmap)
                    tvCoverState.text = "$name · 已上传"
                } else {
                    ivCover.setImageDrawable(null)
                    tvCoverState.text = "$name · 预览没加载出来"
                }
            }
        }
    }

    /** 取一张封面小图；超时给得比正文页宽一些，返回 null 交给上层重试 */
    private fun fetchRemoteCover(url: String, target: Int = 360): android.graphics.Bitmap? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "BlogPad")
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { decodeSample(it.readBytes(), target) }
            } else null
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** 预览格子只有 72×48dp，长边采样到 360px 足够，也省内存 */
    private fun decodeSample(bytes: ByteArray, maxSize: Int): android.graphics.Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (_: Exception) {
        null
    }

    /** 站内相对路径拼成绝对地址；站点地址没填时用默认站点兜底 */
    private fun remoteCoverUrl(path: String): String? {
        val p = normalizeCoverPath(path)
        if (p.isEmpty()) return null
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        return ConfigStore.load(this).siteBase() + "/" + p.trimStart('/')
    }

    /** front matter 里可能是 "\/upload\/..." 这种转义写法，先还原成正常路径 */
    private fun normalizeCoverPath(raw: String): String = raw.trim().replace("\\/", "/")

    // ---------- 提交 ----------

    private fun finishWith(draft: Boolean) {
        if (slugExpanded) slugValue = etSlug.text.toString()
        val slug = SshPublisher.resolveSlug(slugValue.ifBlank { title })
        val data = Intent()
            .putExtra(RESULT_SLUG, slug)
            .putExtra(RESULT_CATEGORIES, selectedCategories.joinToString(", "))
            .putExtra(RESULT_TAGS, selectedTags.joinToString(", "))
            .putExtra(RESULT_DRAFT, draft)
            .putExtra(RESULT_COVER, coverRemote)
            .putExtra(RESULT_COVER_INBOX, coverInboxId.orEmpty())
        coverResultSent = true
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * 返回键、系统返回、点「返回」都是 finish()，这些入口同样要交代封面状态，
     * 否则用户选了封面却先退回去改正文，封面在上一页就"不见了"。
     * resultCode 用 RESULT_CANCELED，上一页据此知道这只是回来、不是要发布。
     */
    override fun finish() {
        if (!coverResultSent) {
            coverResultSent = true
            setResult(
                RESULT_CANCELED,
                Intent()
                    .putExtra(RESULT_COVER, coverRemote)
                    .putExtra(RESULT_COVER_INBOX, coverInboxId.orEmpty())
            )
        }
        super.finish()
    }

    private fun showStatus(message: String) {
        if (message.isBlank()) {
            tvStatus.visibility = View.GONE
            return
        }
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(getColor(R.color.text_secondary))
        tvStatus.text = message
    }

    private fun showError(message: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(getColor(R.color.danger))
        tvStatus.text = message
    }

    /** chip 值只按逗号/分号切，不切空格——分类名本身可能带空格 */
    private fun splitValues(text: String): List<String> =
        text.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
