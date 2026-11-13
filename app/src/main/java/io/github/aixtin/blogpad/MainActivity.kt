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
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 编辑页。只管三件事：标题、正文、附件。
 *
 * slug、分类、标签、封面全部挪到点「完成」之后的发布页——它们大多数时候是空的，
 * 或者沿用全站已有的值，长期占着编辑页只会把正文挤小。
 *
 * 附件不再「选完就传」：选完只复制进私有目录，正文里留一个占位，
 * 点发布时一次性上传、把占位换成站内路径。理由是边选边传会在服务器上留下
 * 一串「用户换过但没再用」的孤儿文件，而发布才是真正决定要哪些附件的时刻。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "remote_path"
        private const val REQ_PICK_MEDIA = 1001
        private const val REQ_PUBLISH = 1002
    }

    private lateinit var etTitle: EditText
    private lateinit var etBody: EditText
    private lateinit var btnDone: TextView
    private lateinit var btnAttach: TextView
    private lateinit var tvAttachHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: TextView
    private lateinit var btnBrowser: View
    private lateinit var bottomBar: View
    private lateinit var scrollView: ScrollView
    private lateinit var editPane: View
    private lateinit var previewPane: LinearLayout

    private var previewing = false
    private var editScrollY = 0
    private var previewScrollY = 0
    private var bodySelection = 0

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var remotePath: String? = null
    private var loadedDate: String? = null
    private var extraFrontMatter: List<String> = emptyList()

    // 发布页填的元数据。这里只是暂存，不参与正文编辑
    private var slugValue = ""
    private var categoriesValue = ""
    private var tagsValue = ""
    private var draftFlag = false

    /** front matter 里已有的封面（站内路径），换封面只在发布页发生 */
    private var coverImage = ""
    /** 已选但还没上传的封面，指向暂存区 */
    private var coverInboxId: String? = null

    private var pendingKind: MediaKind? = null
    private var loaded = false
    private var snapshot = ""
    /** 暂存区快照，避免每敲一个字都去读一次磁盘 */
    private var inboxCache: List<InboxItem> = emptyList()
    /** 发布成功后就不要再把内容写回本地草稿缓存 */
    private var published = false

    private val hintTick = Runnable { updateAttachHint() }

    private val editing: Boolean get() = remotePath != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        remotePath = intent.getStringExtra(EXTRA_PATH)

        etTitle = findViewById(R.id.etTitle)
        etBody = findViewById(R.id.etBody)
        btnDone = findViewById(R.id.btnDone)
        btnAttach = findViewById(R.id.btnAttach)
        tvAttachHint = findViewById(R.id.tvAttachHint)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnBrowser = findViewById(R.id.btnBrowser)
        bottomBar = findViewById(R.id.bottomBar)
        scrollView = findViewById(R.id.scrollView)
        editPane = findViewById(R.id.editPane)
        previewPane = findViewById(R.id.previewPane)

        findViewById<TextView>(R.id.tvHeader).text =
            if (editing) "编辑文章" else "新建文章"

        findViewById<View>(R.id.btnBack).setOnClickListener { confirmExit() }
        btnToggle.setOnClickListener { toggleMode() }
        btnBrowser.setOnClickListener { openInBrowser() }
        btnDone.setOnClickListener { doDone() }
        btnAttach.setOnClickListener { chooseAttachment() }

        // 正文每敲一个字都去扫一遍占位符对长文来说太贵，防抖一下，只关心停下来之后的状态
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                mainHandler.removeCallbacks(hintTick)
                mainHandler.postDelayed(hintTick, 400L)
            }
        }
        etBody.addTextChangedListener(watcher)

        updateAttachHint()
        refreshInbox()

        if (editing) {
            loadRemote()
        } else {
            loaded = true
            restoreCache()
            updateAttachHint()
            snapshot = signature()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!editing && !published) cacheDraft()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hintTick)
        worker.shutdownNow()
    }

    override fun onBackPressed() {
        confirmExit()
    }

    private fun confirmExit() {
        if (loaded && signature() != snapshot) {
            AlertDialog.Builder(this)
                .setTitle("放弃修改？")
                .setMessage(
                    if (editing) "这篇改动还没有保存，返回后将丢失。"
                    else "正文还没有发布，返回后草稿会留在这里，下次进来可以继续。\n\n已暂存的附件不会被删，下次还能用。"
                )
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("返回") { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
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
        etBody.setText(source.draft.body)
        slugValue = source.draft.slug
        categoriesValue = source.draft.categories
        tagsValue = source.draft.tags
        draftFlag = source.draft.draft
        loadedDate = source.dateIso
        extraFrontMatter = source.draft.extraFrontMatter
        coverImage = source.draft.coverImage
        coverInboxId = null
        remotePath = source.meta.path
        loaded = true
        setEditable(true)
        setBusy(false, null)
        snapshot = signature()
        refreshInbox()
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

    // ---------- 附件：只暂存，不上传 ----------

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
            showMessage("没有可用的文件选择器：${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    @Deprecated("纯 Activity 没有 registerForActivityResult，这里继续用旧回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK_MEDIA -> {
                val kind = pendingKind
                pendingKind = null
                if (resultCode != RESULT_OK || kind == null) return
                val uri = data?.data ?: return
                stageAttachment(uri, kind)
            }
            REQ_PUBLISH -> {
                if (resultCode != RESULT_OK || data == null) return
                slugValue = data.getStringExtra(PublishMetaActivity.RESULT_SLUG).orEmpty()
                categoriesValue = data.getStringExtra(PublishMetaActivity.RESULT_CATEGORIES).orEmpty()
                tagsValue = data.getStringExtra(PublishMetaActivity.RESULT_TAGS).orEmpty()
                draftFlag = data.getBooleanExtra(PublishMetaActivity.RESULT_DRAFT, false)
                coverImage = data.getStringExtra(PublishMetaActivity.RESULT_COVER).orEmpty()
                coverInboxId = data.getStringExtra(PublishMetaActivity.RESULT_COVER_INBOX)
                    ?.ifBlank { null }
                doPublish()
            }
        }
    }

    /**
     * 复制进私有目录。content:// 的读权限只在这次回来的瞬间有效，
     * 不复制的话，进程重启后正文里那条引用就永远指向一个读不到的文件了。
     */
    private fun stageAttachment(uri: Uri, kind: MediaKind) {
        setBusy(true, "正在暂存${kind.label}…")
        worker.execute {
            try {
                val item = AttachmentInbox.add(this, uri, kind)
                mainHandler.post { onStaged(kind, item) }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false, null)
                    showMessage("暂存失败：${e.message ?: e.javaClass.simpleName}", true)
                }
            }
        }
    }

    private fun onStaged(kind: MediaKind, item: InboxItem) {
        setBusy(false, null)
        if (kind == MediaKind.COVER) {
            coverInboxId?.let { old -> AttachmentInbox.remove(this, listOf(old)) }
            coverInboxId = item.id
            coverImage = ""
            refreshInbox()
            showMessage("封面已选：${item.displayName}，发布时上传", false)
            return
        }
        if (previewing) leavePreview()
        val placeholder = AttachmentInbox.SCHEME + item.id
        val snippet = when (kind) {
            MediaKind.VIDEO -> "<video controls src=\"$placeholder\" style=\"max-width:100%\"></video>"
            MediaKind.AUDIO -> "<audio controls src=\"$placeholder\" style=\"width:100%\"></audio>"
            else -> "![]($placeholder)"
        }
        insertAtCursor(snippet)
        inboxCache = inboxCache + item
        updateAttachHint()
        showMessage("已暂存 ${item.displayName}，发布时统一上传", false)
        if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) showHtmlHintOnce()
    }

    /** 暂存区状态直接摆在附件按钮右边：用户能看见「有几个还没传」，不用自己去想 */
    private fun renderAttachHint(items: List<InboxItem>) {
        if (items.isEmpty()) {
            tvAttachHint.text = ""
            return
        }
        val used = AttachmentInbox.referencedIds(etBody.text.toString()).toSet()
        val pending = items.count { used.contains(it.id) }
        val size = AttachmentInbox.formatSize(items.sumOf { it.size })
        tvAttachHint.text = if (pending < items.size) {
            "待传 $pending/${items.size} · $size"
        } else {
            "待传 $pending 个 · $size"
        }
    }

    private fun updateAttachHint() = renderAttachHint(inboxCache)

    /** 只在进入页面、暂存完、发布完这几个时点重新扫一遍暂存区 */
    private fun refreshInbox() {
        worker.execute {
            val items = AttachmentInbox.list(this)
            mainHandler.post {
                inboxCache = items
                renderAttachHint(items)
            }
        }
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
            .setPositiveButton("复制配置") { _, _ ->
                copyText("[markup.goldmark.renderer]\n    unsafe = true")
            }
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

    // ---------- 完成 -> 发布页 -> 统一上传 ----------

    private fun doDone() {
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
        val unresolved = AttachmentInbox.referencedIds(body)
            .filter { id -> inboxCache.none { it.id == id } }
        if (unresolved.isNotEmpty()) {
            showMessage("有 ${unresolved.size} 处附件引用已失效，请删掉那几行再继续", true)
            return
        }

        val intent = Intent(this, PublishMetaActivity::class.java)
            .putExtra(PublishMetaActivity.EXTRA_TITLE, title)
            .putExtra(PublishMetaActivity.EXTRA_BODY, body)
            .putExtra(PublishMetaActivity.EXTRA_SLUG, slugValue)
            .putExtra(PublishMetaActivity.EXTRA_CATEGORIES, categoriesValue)
            .putExtra(PublishMetaActivity.EXTRA_TAGS, tagsValue)
            .putExtra(PublishMetaActivity.EXTRA_DRAFT, draftFlag)
            .putExtra(PublishMetaActivity.EXTRA_COVER, coverImage)
            .putExtra(PublishMetaActivity.EXTRA_COVER_INBOX, coverInboxId.orEmpty())
            .putExtra(PublishMetaActivity.EXTRA_EDITING, editing)
        startActivityForResult(intent, REQ_PUBLISH)
    }

    private fun doPublish() {
        val config = ConfigStore.load(this)
        val title = etTitle.text.toString().trim()
        val body = etBody.text.toString()

        val draft = PostDraft(
            title = title,
            slug = SshPublisher.resolveSlug(slugValue.ifBlank { title }),
            categories = categoriesValue,
            tags = tagsValue,
            body = body,
            draft = draftFlag,
            date = if (editing) loadedDate else null,
            coverImage = coverImage,
            extraFrontMatter = if (editing) extraFrontMatter else emptyList()
        )
        val original = remotePath
        val inbox = inboxCache.associateBy { it.id }
        val stagedCover = coverInboxId

        setBusy(true, if (inbox.isEmpty()) "正在写入站点…" else "正在上传附件…")
        worker.execute {
            try {
                val outcome = PublishFlow.run(
                    context = applicationContext,
                    config = config,
                    draft = draft,
                    originalPath = original,
                    coverInboxId = stagedCover,
                    inbox = inbox
                ) { done, total ->
                    mainHandler.post {
                        showMessage("正在上传附件 $done/$total…", false)
                    }
                }
                mainHandler.post { onSaved(draft, outcome) }
            } catch (e: Exception) {
                mainHandler.post { onSaveFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun onSaved(draft: PostDraft, outcome: PublishFlow.Outcome) {
        val result = outcome.save
        setBusy(false, null)
        remotePath = result.remotePath
        loadedDate = draft.date
        loaded = true
        slugValue = draft.slug
        coverInboxId = null
        extraFrontMatter = draft.extraFrontMatter
        published = true
        if (!editing) clearDraftCache()
        snapshot = signature()
        refreshInbox()

        val message = StringBuilder()
        message.append("已写入：").append(result.remotePath)
        if (outcome.uploaded > 0) {
            message.append("\n\n附件：").append(outcome.uploaded).append(" 个已上传，本地副本已清理")
        }
        result.renamedFrom?.let { message.append("\n\n原路径已清理：").append(it) }
        if (draft.draft) {
            message.append("\n\n这是草稿，站点构建后不会出现这篇文章。")
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
            .setMessage("$error\n\n正文还在编辑器里，附件也留在本地暂存区，可以直接重试。")
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
            previewBody(),
            resources.displayMetrics.density
        ) { url -> openExternal(url) }

        val coverName = inboxCache.firstOrNull { it.id == coverInboxId }?.displayName
            ?: coverImage.ifBlank { "" }
        if (coverName.isNotBlank()) {
            val head = TextView(this)
            head.text = "封面 · $coverName"
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
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
        btnToggle.text = "预览"
        btnBrowser.visibility = View.GONE
        scrollView.scrollTo(0, editScrollY)
        etBody.setSelection(bodySelection.coerceIn(0, etBody.text.length))
    }

    /** 预览不认识暂存区占位，换成一条以 / 开头的假路径，至少能显示「发布后可见」 */
    private fun previewBody(): String {
        var text = etBody.text.toString()
        AttachmentInbox.referencedIds(text).forEach { id ->
            val item = inboxCache.firstOrNull { it.id == id }
            text = text.replace(AttachmentInbox.SCHEME + id, "/" + (item?.fileName ?: "附件"))
        }
        return text
    }

    /** 线上地址：草稿没有线上页面，未配站点地址时也没有。 */
    private fun currentUrl(): String {
        if (draftFlag) return ""
        val slug = if (slugValue.isBlank()) {
            remotePath?.substringAfterLast('/')?.removeSuffix(".md") ?: ""
        } else {
            SshPublisher.resolveSlug(slugValue)
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
        btnDone.isEnabled = !busy
        // 上传/保存期间锁住附件入口，避免同一个文件被点两次
        btnAttach.isEnabled = !busy
        btnDone.text = when {
            busy -> if (editing) "保存中…" else "发布中…"
            else -> getString(R.string.btn_done)
        }
        if (busy) {
            if (status != null) showMessage(status, false)
        } else {
            if (status == null) tvStatus.visibility = View.GONE else showMessage(status, false)
        }
    }

    private fun setEditable(enabled: Boolean) {
        etTitle.isEnabled = enabled
        etBody.isEnabled = enabled
        btnDone.isEnabled = enabled
        btnAttach.isEnabled = enabled
    }

    private fun showMessage(message: String, isError: Boolean) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(getColor(if (isError) R.color.danger else R.color.success))
        tvStatus.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun signature(): String = listOf(
        etTitle.text.toString(),
        etBody.text.toString(),
        coverInboxId.orEmpty(),
        AttachmentInbox.referencedIds(etBody.text.toString()).size.toString()
    ).joinToString("\u0000")

    // ---------- 新建模式的本地草稿缓存 ----------

    private fun cacheDraft() {
        getSharedPreferences("blogpad_cache", MODE_PRIVATE).edit()
            .putString("title", etTitle.text.toString())
            .putString("body", etBody.text.toString())
            .putString("slug", slugValue)
            .putString("categories", categoriesValue)
            .putString("tags", tagsValue)
            .putBoolean("draft", draftFlag)
            .putString("cover", coverImage)
            .putString("cover_inbox", coverInboxId.orEmpty())
            .apply()
    }

    /** 发布成功后清掉本地草稿，否则下次新建会把上一篇已发布的内容带出来 */
    private fun clearDraftCache() {
        getSharedPreferences("blogpad_cache", MODE_PRIVATE).edit().clear().apply()
    }

    private fun restoreCache() {
        val prefs = getSharedPreferences("blogpad_cache", MODE_PRIVATE)
        etTitle.setText(prefs.getString("title", ""))
        etBody.setText(prefs.getString("body", ""))
        slugValue = prefs.getString("slug", "").orEmpty()
        categoriesValue = prefs.getString("categories", "").orEmpty()
        tagsValue = prefs.getString("tags", "").orEmpty()
        draftFlag = prefs.getBoolean("draft", false)
        coverImage = prefs.getString("cover", "").orEmpty()
        coverInboxId = prefs.getString("cover_inbox", "")?.ifBlank { null }
        updateAttachHint()
    }
}
