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

class MainActivity : Activity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTitle = findViewById(R.id.etTitle)
        etSlug = findViewById(R.id.etSlug)
        etCategories = findViewById(R.id.etCategories)
        etTags = findViewById(R.id.etTags)
        etBody = findViewById(R.id.etBody)
        cbDraft = findViewById(R.id.cbDraft)
        btnPublish = findViewById(R.id.btnPublish)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnPublish.setOnClickListener { doPublish() }

        restoreCache()
    }

    override fun onPause() {
        super.onPause()
        cacheDraft()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun doPublish() {
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

        val article = Article(
            title = title,
            slug = SshPublisher.resolveSlug(etSlug.text.toString()),
            categories = etCategories.text.toString(),
            tags = etTags.text.toString(),
            body = body,
            draft = cbDraft.isChecked
        )

        setBusy(true, "正在发布…")
        worker.execute {
            try {
                val result = SshPublisher.publish(config, article)
                mainHandler.post { onPublishSuccess(article, result) }
            } catch (e: Exception) {
                mainHandler.post { onPublishFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun onPublishSuccess(article: Article, result: PublishResult) {
        setBusy(false, null)
        cacheDraft()
        val message = StringBuilder()
        message.append("已上传：").append(result.remotePath)
        if (article.draft) {
            message.append("\n\n草稿已生成，正式发布前请把 draft 改为 false。")
        } else if (result.url.isNotEmpty()) {
            message.append("\n\n").append(result.url)
        }
        if (result.buildOutput.isNotEmpty()) {
            message.append("\n\n构建输出：\n").append(result.buildOutput.takeLast(600))
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (article.draft) "已存为草稿" else "发布成功")
            .setMessage(message.toString())
            .setPositiveButton("好", null)
        if (!article.draft && result.url.isNotEmpty()) {
            builder.setNeutralButton("复制链接") { _, _ -> copyLink(result.url) }
        }
        builder.show()
    }

    private fun onPublishFailed(error: String) {
        setBusy(false, null)
        showMessage("发布失败", true)
        AlertDialog.Builder(this)
            .setTitle("发布失败")
            .setMessage(error)
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("文章链接", url))
        showMessage("链接已复制", false)
    }

    private fun setBusy(busy: Boolean, status: String?) {
        btnPublish.isEnabled = !busy
        btnPublish.text = if (busy) "发布中…" else "发布"
        if (status == null) {
            if (busy) return
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.visibility = View.VISIBLE
            tvStatus.setTextColor(Color.parseColor("#2F6FED"))
            tvStatus.text = status
        }
    }

    private fun showMessage(message: String, isError: Boolean) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(Color.parseColor(if (isError) "#D93025" else "#1E7B34"))
        tvStatus.text = message
    }

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
