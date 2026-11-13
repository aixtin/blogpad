package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

    private var categoryPool: List<String> = emptyList()
    private var tagPool: List<String> = emptyList()
    private val selectedCategories = ArrayList<String>()
    private val selectedTags = ArrayList<String>()

    private var slugExpanded = false

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_publish_meta)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        editing = intent.getBooleanExtra(EXTRA_EDITING, false)
        defaultDraft = intent.getBooleanExtra(EXTRA_DRAFT, false)
        coverRemote = intent.getStringExtra(EXTRA_COVER).orEmpty()
        coverInboxId = intent.getStringExtra(EXTRA_COVER_INBOX)?.ifBlank { null }

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
        ivCover = findViewById(R.id.ivCover)
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
        renderChipRow(layoutCategoryChips, categoryPool, selectedCategories, multiSelect = false) {
            renderChips()
        }
        renderChipRow(layoutTagChips, tagPool, selectedTags, multiSelect = true) {
            renderChips()
        }
    }

    private fun renderChipRow(
        container: LinearLayout,
        pool: List<String>,
        selected: MutableList<String>,
        multiSelect: Boolean,
        onChanged: () -> Unit
    ) {
        val values = ArrayList(pool)
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
        container.addView(makeChip(if (selected.isEmpty() && values.isEmpty()) "新建" else "＋ 新建", false) {
            val poolFrozen = ArrayList(values)
            promptNew(if (multiSelect) "新建标签" else "新建分类", poolFrozen) { picked ->
                if (!pool.contains(picked)) {
                    if (multiSelect) tagPool = tagPool + picked else categoryPool = categoryPool + picked
                }
                if (!selected.contains(picked)) {
                    if (!multiSelect) selected.clear()
                    selected.add(picked)
                }
                onChanged()
            }
        })
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

    private fun pickCover() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_PICK_COVER)
        } catch (_: Exception) {
            showStatus("没有可用的文件选择器")
        }
    }

    @Deprecated("纯 Activity 没有 registerForActivityResult，这里继续用旧回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_COVER) return
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        showStatus("正在暂存封面…")
        worker.execute {
            try {
                val item = AttachmentInbox.add(this, uri, MediaKind.COVER)
                // 换封面时把上一张还没上传的副本丢掉，避免暂存区里堆一串废图
                coverInboxId?.let { old -> AttachmentInbox.remove(this, listOf(old)) }
                mainHandler.post {
                    coverInboxId = item.id
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

    private fun updateCover() {
        val local = AttachmentInbox.find(this, coverInboxId)
        when {
            local != null -> {
                tvCoverState.text = "${local.displayName} · 发布时上传"
                ivCover.setImageBitmap(decodeThumb(local.file(this)))
                btnClearCover.visibility = View.VISIBLE
            }
            coverRemote.isNotBlank() -> {
                tvCoverState.text = coverRemote.substringAfterLast('/') + " · 已上传"
                ivCover.setImageDrawable(null)
                btnClearCover.visibility = View.VISIBLE
            }
            else -> {
                tvCoverState.text = "未设置（文章可以没有封面）"
                ivCover.setImageDrawable(null)
                btnClearCover.visibility = View.GONE
            }
        }
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
        setResult(RESULT_OK, data)
        finish()
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
