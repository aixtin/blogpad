package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 编辑页。两种模式：
 *  - 新建：不传 EXTRA_PATH，正文为空，本地草稿缓存生效；
 *  - 编辑：传 EXTRA_PATH，先读远端 md 回填，保存时覆盖原文件（slug 变了就远端改名），
 *    front matter 里不归 App 管的键原样保留。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "remote_path"
        private const val REQ_PICK_MEDIA = 1001
        /** 单个附件上限。手机内存里要先整个读出来再上传，超过这个量级容易 OOM */
        private const val MAX_ATTACHMENT_BYTES = 64 * 1024 * 1024
    }

    private lateinit var etTitle: EditText
    private lateinit var etSlug: EditText
    private lateinit var etCategories: EditText
    private lateinit var etTags: EditText
    private lateinit var etBody: EditText
    private lateinit var cbDraft: CheckBox
    private lateinit var btnPublish: Button
    private lateinit var tvStatus: TextView

    private lateinit var rowCategoryChips: View
    private lateinit var rowTagChips: View
    private lateinit var layoutCategoryChips: LinearLayout
    private lateinit var layoutTagChips: LinearLayout

    private lateinit var tvMetaSummary: TextView
    private lateinit var metaContainer: View
    private var metaExpanded = false

    private lateinit var btnSettings: View
    private lateinit var btnToggle: TextView
    private lateinit var btnBrowser: View
    private lateinit var bottomBar: View
    private lateinit var scrollView: ScrollView
    private lateinit var editPane: View
    private lateinit var previewPane: LinearLayout

    /** 结构预览开关；切换时保留编辑区滚动位置与光标，预览区也各自记住位置 */
    private var previewing = false
    private var editScrollY = 0
    private var previewScrollY = 0
    private var bodySelection = 0

    /** 词表来自上次列表刷新，chip 只是快捷输入，最终写盘仍看输入框 */
    private var taxonomy: Taxonomy = Taxonomy()
    private val chipRenderers = ArrayList<() -> Unit>()

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var remotePath: String? = null
    private var loadedDate: String? = null
    /** front matter 的 image 字段，空串表示这篇不设封面 */
    private var coverImage: String = ""
    /** 文件选择器只会回一个结果，用它在返回时还原用户点的是哪一类 */
    private var pendingKind: MediaKind? = null
    private lateinit var btnAttach: View
    private lateinit var tvCover: TextView
    private var extraFrontMatter: List<String> = emptyList()
    private var loaded = false
    private var snapshot: String = ""

    private val editing: Boolean get() = remotePath != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        remotePath = intent.getStringExtra(EXTRA_PATH)

        etTitle = findViewById(R.id.etTitle)
        etSlug = findViewById(R.id.etSlug)
        etCategories = findViewById(R.id.etCategories)
        etTags = findViewById(R.id.etTags)
        etBody = findViewById(R.id.etBody)
        cbDraft = findViewById(R.id.cbDraft)
        btnPublish = findViewById(R.id.btnPublish)
        tvStatus = findViewById(R.id.tvStatus)

        rowCategoryChips = findViewById(R.id.rowCategoryChips)
        rowTagChips = findViewById(R.id.rowTagChips)
        layoutCategoryChips = findViewById(R.id.layoutCategoryChips)
        layoutTagChips = findViewById(R.id.layoutTagChips)
        tvMetaSummary = findViewById(R.id.tvMetaSummary)
        metaContainer = findViewById(R.id.metaContainer)
        btnSettings = findViewById(R.id.btnSettings)
        btnToggle = findViewById(R.id.btnToggle)
        btnBrowser = findViewById(R.id.btnBrowser)
        bottomBar = findViewById(R.id.bottomBar)
        scrollView = findViewById(R.id.scrollView)
        editPane = findViewById(R.id.editPane)
        previewPane = findViewById(R.id.previewPane)
        btnAttach = findViewById(R.id.btnAttach)
        tvCover = findViewById(R.id.tvCover)
        taxonomy = TaxonomyStore.load(this)
        chipRenderers.add(
            bindChipInput(
                input = etCategories,
                chips = layoutCategoryChips,
                row = rowCategoryChips,
                multiSelect = false,
                suggest = { taxonomy.categories }
            )
        )
        chipRenderers.add(
            bindChipInput(
                input = etTags,
                chips = layoutTagChips,
                row = rowTagChips,
                multiSelect = true,
                suggest = { taxonomy.tags }
            )
        )

        findViewById<TextView>(R.id.tvHeader).text = if (editing) "编辑文章" else "新建文章"
        findViewById<View>(R.id.btnBack).visibility = View.VISIBLE
        btnSettings.visibility = if (editing) View.GONE else View.VISIBLE

        findViewById<View>(R.id.btnBack).setOnClickListener { confirmExit() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnToggle.setOnClickListener { toggleMode() }
        btnBrowser.setOnClickListener { openInBrowser() }
        btnPublish.setOnClickListener { doSave() }
        btnAttach.setOnClickListener { chooseAttachment() }
        tvCover.setOnClickListener { onCoverClicked() }

        tvMetaSummary.setOnClickListener { toggleMeta() }
        val metaWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!metaExpanded) updateMetaSummary()
            }
        }
        etSlug.addTextChangedListener(metaWatcher)
        etCategories.addTextChangedListener(metaWatcher)
        etTags.addTextChangedListener(metaWatcher)
        updateMetaSummary()
        updateCoverLabel()

        btnPublish.text = if (editing) "保存" else "发布"

        if (editing) {
            loadRemote()
        } else {
            loaded = true
            restoreCache()
            snapshot = signature()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!editing) cacheDraft()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    override fun onBackPressed() {
        confirmExit()
    }

    private fun confirmExit() {
        if (loaded && signature() != snapshot) {
            AlertDialog.Builder(this)
                .setTitle("放弃修改？")
                .setMessage(if (editing) "这篇改动还没有保存，返回后将丢失。" else "正文还没有发布，返回后草稿会留在这里，下次进来可以继续。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("返回") { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
    }

    // ---------- 属性折叠 ----------

    private fun toggleMeta() {
        metaExpanded = !metaExpanded
        metaContainer.visibility = if (metaExpanded) View.VISIBLE else View.GONE
        updateMetaSummary()
    }

    /** 收起时用当前值做摘要，避免为了看一眼 slug 展开整块表单 */
    private fun updateMetaSummary() {
        tvMetaSummary.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (metaExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
            0
        )
        if (metaExpanded) {
            tvMetaSummary.text = "收起属性"
            return
        }
        val slug = etSlug.text.toString().trim().ifEmpty { "slug 自动生成" }
        val categories = splitValues(etCategories.text.toString()).joinToString("/").ifEmpty { "未分类" }
        val tags = splitValues(etTags.text.toString()).joinToString("、").ifEmpty { "无标签" }
        tvMetaSummary.text = "$slug  ·  $categories  ·  $tags"
    }

    // ---------- 编辑模式：读取远端 ----------

    private fun loadRemote() {
        val path = remotePath ?: return
        val config = ConfigStore.load(this)
        val missing = config.connectionReady() ?: config.siteReady()
        if (missing != null) {
            showMessage(missing, true)
            return
        }

        setEditable(false)
        setBusy(true, "正在读取远端文章…")
        worker.execute {
            try {
                val source = PostRepository.readPost(config, path)
                mainHandler.post { fill(source) }
            } catch (e: Exception) {
                mainHandler.post { onLoadFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun fill(source: PostSource) {
        etTitle.setText(source.draft.title)
        etSlug.setText(source.draft.slug)
        etCategories.setText(source.draft.categories)
        etTags.setText(source.draft.tags)
        etBody.setText(source.draft.body)
        cbDraft.isChecked = source.draft.draft
        loadedDate = source.dateIso
        extraFrontMatter = source.draft.extraFrontMatter
        coverImage = source.draft.coverImage
        updateCoverLabel()
        remotePath = source.meta.path
        loaded = true
        setEditable(true)
        setBusy(false, null)
        snapshot = signature()
        updateMetaSummary()
        if (!source.draft.draft) enterPreview()
    }

    private fun onLoadFailed(error: String) {
        loaded = false
        setEditable(false)
        setBusy(false, null)
        showMessage("读取失败：$error", true)
        AlertDialog.Builder(this)
            .setTitle("读取失败")
            .setMessage(error)
            .setNegativeButton("返回") { _, _ -> finish() }
            .setPositiveButton("重试") { _, _ -> loadRemote() }
            .show()
    }

    // ---------- 附件 ----------

    /**
     * 附件一律「选完就上传」：不做本地暂存队列。
     * 手机相册里的原图动辄几 MB，暂存到发布时再传会让「保存」变成长时间黑盒，
     * 而且在身边没网时用户会以为文章本身有问题。当场传完、当场把引用插进正文，
     * 失败就明确报失败，正文一个字都不改。
     */
    private fun chooseAttachment() {
        if (!loaded) {
            showMessage("内容还没读取完成，请稍候", true)
            return
        }
        val labels = arrayOf("图片", "视频", "音频", "设为封面")
        AlertDialog.Builder(this)
            .setTitle("插入附件")
            .setItems(labels) { _, index ->
                val kind = when (index) {
                    0 -> MediaKind.IMAGE
                    1 -> MediaKind.VIDEO
                    2 -> MediaKind.AUDIO
                    else -> MediaKind.COVER
                }
                pickMedia(kind)
            }
            .show()
    }

    private fun pickMedia(kind: MediaKind) {
        val config = ConfigStore.load(this)
        config.connectionReady()?.let {
            showMessage(it, true)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        config.siteReady()?.let {
            showMessage(it, true)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        config.mediaReady()?.let {
            showMessage(it, true)
            AlertDialog.Builder(this)
                .setTitle("附件库还没配好")
                .setMessage(it)
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .show()
            return
        }

        pendingKind = kind
        val mime = when (kind) {
            MediaKind.IMAGE, MediaKind.COVER -> "image/*"
            MediaKind.VIDEO -> "video/*"
            MediaKind.AUDIO -> "audio/*"
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_PICK_MEDIA)
        } catch (e: Exception) {
            pendingKind = null
            showMessage("没有可用的文件选择器", true)
        }
    }

    @Deprecated("纯 Activity 没有 registerForActivityResult，这里继续用旧回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_MEDIA) return
        val kind = pendingKind
        pendingKind = null
        if (resultCode != RESULT_OK || kind == null) return
        val uri = data?.data ?: return
        uploadAttachment(uri, kind)
    }

    private fun uploadAttachment(uri: Uri, kind: MediaKind) {
        val config = ConfigStore.load(this)
        val slug = SshPublisher.resolveSlug(
            etSlug.text.toString().ifBlank { etTitle.text.toString() }
        )
        setBusy(true, "正在上传${kind.label}…")
        worker.execute {
            try {
                val (bytes, name) = readAttachment(uri)
                val ref = SshPublisher.uploadMedia(config, kind, bytes, name, slug)
                mainHandler.post { onAttachmentUploaded(kind, ref) }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false, null)
                    showMessage("上传失败：${e.message ?: e.javaClass.simpleName}", true)
                }
            }
        }
    }

    /** 连同文件名一起读出来：扩展名要跟着走，否则远端会落一堆无后缀文件 */
    private fun readAttachment(uri: Uri): Pair<ByteArray, String> {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("读不到这个文件，可能是权限失效，重选一次试试")
        if (bytes.isEmpty()) throw IllegalStateException("这个文件是空的")
        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            throw IllegalStateException("文件超过 64MB，建议先压缩再传")
        }
        return bytes to displayName(uri)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Exception) {
            // 查不到名字不影响上传，扩展名会回落到按类型给的默认值
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun onAttachmentUploaded(kind: MediaKind, ref: SshPublisher.MediaRef) {
        setBusy(false, null)
        if (kind == MediaKind.COVER) {
            coverImage = ref.urlPath
            updateCoverLabel()
            showMessage("封面已上传：${ref.urlPath}", false)
            return
        }
        if (previewing) leavePreview()
        val snippet = when (kind) {
            MediaKind.VIDEO -> "<video controls src=\"${ref.urlPath}\" style=\"max-width:100%\"></video>"
            MediaKind.AUDIO -> "<audio controls src=\"${ref.urlPath}\" style=\"width:100%\"></audio>"
            else -> "![](${ref.urlPath})"
        }
        insertAtCursor(snippet)
        showMessage("已上传 ${ref.fileName}，引用已插入光标处", false)
        if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) showHtmlHintOnce()
    }

    /**
     * 视频/音频走 HTML 标签，而 Hugo 默认丢弃正文原始 HTML（goldmark unsafe=false），
     * 构建后只会剩一条 raw HTML omitted 注释。这是站点侧配置问题，App 改不了，
     * 所以只在第一次插入时讲清楚，之后不再打扰。
     */
    private fun showHtmlHintOnce() {
        val prefs = getSharedPreferences("blogpad_flags", MODE_PRIVATE)
        if (prefs.getBoolean("html_hint_shown", false)) return
        prefs.edit().putBoolean("html_hint_shown", true).apply()
        AlertDialog.Builder(this)
            .setTitle("视频/音频需要站点配合")
            .setMessage(
                "插入的是 HTML 标签。Hugo 默认不允许正文写原始 HTML，构建时会把整段丢掉。\n\n" +
                    "在站点配置里加上这三行即可：\n\n" +
                    "[markup.goldmark.renderer]\n    unsafe = true\n\n" +
                    "图片和封面用的是标准 Markdown，不需要这项。"
            )
            .setNegativeButton("知道了", null)
            .setPositiveButton("复制配置") { _, _ -> copyText("[markup.goldmark.renderer]\n    unsafe = true") }
            .show()
    }

    private fun copyText(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("blogpad", text))
        showMessage("配置片段已复制", false)
    }

    /** 块级插入：前后各留一个空行，避免图片/HTML 被并进上一段或被当成代码块 */
    private fun insertAtCursor(snippet: String) {
        val editable = etBody.text
        val start = etBody.selectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
        val end = etBody.selectionEnd.coerceAtLeast(0).coerceAtMost(editable.length)
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val before = editable.substring(0, lo)
        val after = editable.substring(hi)
        val lead = when {
            before.isEmpty() -> ""
            before.endsWith("\n\n") -> ""
            before.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val trail = when {
            after.isEmpty() -> "\n"
            after.startsWith("\n\n") -> ""
            after.startsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val block = lead + snippet + trail
        editable.replace(lo, hi, block)
        etBody.setSelection((lo + block.length).coerceAtMost(editable.length))
    }

    private fun onCoverClicked() {
        if (coverImage.isBlank()) {
            pickMedia(MediaKind.COVER)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("封面")
            .setMessage(coverImage)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除") { _, _ ->
                coverImage = ""
                updateCoverLabel()
                showMessage("已清除封面，保存后 front matter 不再有 image", false)
            }
            .setPositiveButton("重新选择") { _, _ -> pickMedia(MediaKind.COVER) }
            .show()
    }

    private fun updateCoverLabel() {
        tvCover.text = if (coverImage.isBlank()) {
            "封面：未设置"
        } else {
            "封面：${coverImage.substringAfterLast('/')}"
        }
    }

    // ---------- 保存 ----------

    private fun doSave() {
        if (!loaded) {
            showMessage("内容还没读取完成，请稍候", true)
            return
        }

        val config = ConfigStore.load(this)
        config.connectionReady()?.let {
            showMessage(it, true)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        config.siteReady()?.let {
            showMessage(it, true)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            showMessage("标题不能为空", true)
            etTitle.requestFocus()
            return
        }
        val body = etBody.text.toString()
        if (body.isBlank()) {
            showMessage("正文不能为空", true)
            etBody.requestFocus()
            return
        }

        val draft = PostDraft(
            title = title,
            slug = SshPublisher.resolveSlug(etSlug.text.toString()),
            categories = etCategories.text.toString(),
            tags = etTags.text.toString(),
            body = body,
            draft = cbDraft.isChecked,
            date = if (editing) loadedDate else null,
            coverImage = coverImage,
            extraFrontMatter = if (editing) extraFrontMatter else emptyList()
        )
        val original = remotePath

        setBusy(true, if (editing) "正在保存…" else "正在发布…")
        worker.execute {
            try {
                val result = PostRepository.save(config, draft, original)
                mainHandler.post { onSaved(draft, result) }
            } catch (e: Exception) {
                mainHandler.post { onSaveFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun onSaved(draft: PostDraft, result: SaveResult) {
        setBusy(false, null)
        remotePath = result.remotePath
        loadedDate = draft.date
        loaded = true
        if (!editing) cacheDraft()
        snapshot = signature()

        val message = StringBuilder()
        message.append("已写入：").append(result.remotePath)
        result.renamedFrom?.let { message.append("\n\n原路径已清理：").append(it) }
        if (draft.draft) {
            message.append("\n\n草稿已存，正式发布前把「存为草稿」取消勾选再保存一次。")
        } else if (result.url.isNotEmpty()) {
            message.append("\n\n").append(result.url)
        }
        if (result.buildOutput.isNotEmpty()) {
            message.append("\n\n构建输出：\n").append(result.buildOutput.takeLast(600))
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (draft.draft) "已存为草稿" else if (editing) "已保存" else "发布成功")
            .setMessage(message.toString())
            .setPositiveButton("好") { _, _ -> finish() }
        if (!draft.draft && result.url.isNotEmpty()) {
            builder.setNeutralButton("复制链接") { _, _ -> copyLink(result.url) }
        }
        builder.show()
    }

    private fun onSaveFailed(error: String) {
        setBusy(false, null)
        showMessage("保存失败", true)
        AlertDialog.Builder(this)
            .setTitle("保存失败")
            .setMessage(error)
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("文章链接", url))
        showMessage("链接已复制", false)
    }

    // ---------- 结构预览 / 浏览器兜底 ----------

    private fun toggleMode() {
        if (!loaded) {
            showMessage("内容还没读取完成，请稍候", true)
            return
        }
        if (previewing) leavePreview() else enterPreview()
    }

    /** 预览渲染的是当前缓冲区，不是远端文件；进来前记下编辑区的滚动位置与光标。 */
    private fun enterPreview() {
        bodySelection = etBody.selectionStart.coerceAtLeast(0)
        editScrollY = scrollView.scrollY
        MarkdownPreview.render(
            previewPane,
            etBody.text.toString(),
            resources.displayMetrics.density
        ) { url -> openExternal(url) }

        if (coverImage.isNotBlank()) {
            val head = TextView(this)
            head.text = "封面 · $coverImage"
            head.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            head.setTextColor(0xFF9AA0A8.toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(6)
            previewPane.addView(head, 0, lp)
        }

        previewing = true
        editPane.visibility = View.GONE
        previewPane.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
        btnSettings.visibility = View.GONE
        btnToggle.text = "编辑"
        btnBrowser.visibility = if (currentUrl().isNotEmpty()) View.VISIBLE else View.GONE
        tvStatus.visibility = View.GONE
        scrollView.scrollTo(0, previewScrollY)
    }

    private fun leavePreview() {
        previewScrollY = scrollView.scrollY
        previewing = false
        previewPane.visibility = View.GONE
        editPane.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        if (!editing) btnSettings.visibility = View.VISIBLE
        btnToggle.text = "预览"
        btnBrowser.visibility = View.GONE
        scrollView.scrollTo(0, editScrollY)
        etBody.setSelection(bodySelection.coerceIn(0, etBody.text.length))
    }

    /** 线上地址：草稿没有线上页面，未配站点地址时也没有。 */
    private fun currentUrl(): String {
        if (cbDraft.isChecked) return ""
        val slug = if (etSlug.text.toString().isBlank()) {
            remotePath?.substringAfterLast('/')?.removeSuffix(".md") ?: ""
        } else {
            SshPublisher.resolveSlug(etSlug.text.toString())
        }
        if (slug.isEmpty()) return ""
        return SshPublisher.buildUrl(ConfigStore.load(this), slug, false)
    }

    private fun openInBrowser() {
        val url = currentUrl()
        if (url.isEmpty()) {
            showMessage("这篇还没有线上地址（草稿或未配置站点地址）", true)
            return
        }
        openExternal(url)
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            showMessage("没有可用的浏览器：${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    // ---------- UI 状态 ----------

    private fun setBusy(busy: Boolean, status: String?) {
        btnPublish.isEnabled = !busy
        // 上传/保存期间锁住附件入口，避免同一个文件被点两次传成两份
        btnAttach.isEnabled = !busy
        tvCover.isEnabled = !busy
        btnPublish.text = when {
            busy -> if (editing) "保存中…" else "发布中…"
            else -> if (editing) "保存" else "发布"
        }
        if (busy) {
            if (status != null) showMessage(status, false)
        } else {
            if (status == null) tvStatus.visibility = View.GONE else showMessage(status, false)
        }
    }

    private fun setEditable(enabled: Boolean) {
        etTitle.isEnabled = enabled
        etSlug.isEnabled = enabled
        etCategories.isEnabled = enabled
        etTags.isEnabled = enabled
        etBody.isEnabled = enabled
        cbDraft.isEnabled = enabled
        btnPublish.isEnabled = enabled
        btnAttach.isEnabled = enabled
        tvCover.isEnabled = enabled
    }

    private fun showMessage(message: String, isError: Boolean) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(getColor(if (isError) R.color.danger else R.color.success))
        tvStatus.text = message
    }

    // ---------- 分类 / 标签 chip ----------

    /**
     * 输入框与 chip 双向同步：
     *  - 输入框里逗号分隔的值决定谁高亮，手输的新值会临时补一个 chip 出来；
     *  - 分类单选（再点一次取消），标签多选，写完一律回填输入框。
     * 保存逻辑完全不感知 chip，词表只是快捷输入。
     */
    private fun bindChipInput(
        input: EditText,
        chips: LinearLayout,
        row: View,
        multiSelect: Boolean,
        suggest: () -> List<String>
    ): () -> Unit {
        var lastValues: List<String>? = null
        var lastSelected: List<String>? = null

        val render: () -> Unit = {
            val selected = splitValues(input.text.toString())
            val values = ArrayList(suggest())
            selected.forEach { if (!values.contains(it)) values.add(it) }
            if (values != lastValues || selected != lastSelected) {
                lastValues = values
                lastSelected = selected
                chips.removeAllViews()
                values.forEach { value ->
                    chips.addView(makeChip(value, selected.contains(value)) {
                        if (input.isEnabled) {
                            val next = if (multiSelect) {
                                if (selected.contains(value)) selected - value else selected + value
                            } else {
                                if (selected.size == 1 && selected[0] == value) {
                                    emptyList()
                                } else {
                                    listOf(value)
                                }
                            }
                            input.setText(next.joinToString(", "))
                            input.setSelection(input.text.length)
                        }
                    })
                }
                row.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render()
        })
        render()
        return render
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

    /** chip 值只按逗号/分号切，不切空格——分类名本身可能带空格 */
    private fun splitValues(text: String): List<String> =
        text.split(',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun signature(): String = listOf(
        etTitle.text.toString(),
        etSlug.text.toString(),
        etCategories.text.toString(),
        etTags.text.toString(),
        cbDraft.isChecked.toString(),
        coverImage,
        etBody.text.toString()
    ).joinToString("\u0000")

    // ---------- 新建模式的本地草稿缓存 ----------

    private fun cacheDraft() {
        getSharedPreferences("blogpad_cache", MODE_PRIVATE).edit()
            .putString("title", etTitle.text.toString())
            .putString("slug", etSlug.text.toString())
            .putString("categories", etCategories.text.toString())
            .putString("tags", etTags.text.toString())
            .putString("body", etBody.text.toString())
            .putString("cover", coverImage)
            .apply()
    }

    private fun restoreCache() {
        val prefs = getSharedPreferences("blogpad_cache", MODE_PRIVATE)
        etTitle.setText(prefs.getString("title", ""))
        etSlug.setText(prefs.getString("slug", ""))
        etCategories.setText(prefs.getString("categories", ""))
        etTags.setText(prefs.getString("tags", ""))
        etBody.setText(prefs.getString("body", ""))
        coverImage = prefs.getString("cover", "").orEmpty()
        updateCoverLabel()
    }
}
