package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
    }

    private lateinit var etTitle: EditText
    private lateinit var etSlug: EditText
    private lateinit var etCategories: EditText
    private lateinit var etTags: EditText
    private lateinit var etBody: EditText
    private lateinit var cbDraft: CheckBox
    private lateinit var btnPublish: Button
    private lateinit var tvStatus: TextView

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var remotePath: String? = null
    private var loadedDate: String? = null
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

        findViewById<TextView>(R.id.tvHeader).text = if (editing) "编辑文章" else "新建文章"
        findViewById<TextView>(R.id.btnBack).visibility = if (editing) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btnSettings).visibility = if (editing) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.btnBack).setOnClickListener { confirmExit() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnPublish.setOnClickListener { doSave() }

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
        remotePath = source.meta.path
        loaded = true
        setEditable(true)
        setBusy(false, null)
        snapshot = signature()
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

    // ---------- UI 状态 ----------

    private fun setBusy(busy: Boolean, status: String?) {
        btnPublish.isEnabled = !busy
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
    }

    private fun showMessage(message: String, isError: Boolean) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(Color.parseColor(if (isError) "#D93025" else "#1E7B34"))
        tvStatus.text = message
    }

    private fun signature(): String = listOf(
        etTitle.text.toString(),
        etSlug.text.toString(),
        etCategories.text.toString(),
        etTags.text.toString(),
        cbDraft.isChecked.toString(),
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
            .apply()
    }

    private fun restoreCache() {
        val prefs = getSharedPreferences("blogpad_cache", MODE_PRIVATE)
        etTitle.setText(prefs.getString("title", ""))
        etSlug.setText(prefs.getString("slug", ""))
        etCategories.setText(prefs.getString("categories", ""))
        etTags.setText(prefs.getString("tags", ""))
        etBody.setText(prefs.getString("body", ""))
    }
}
