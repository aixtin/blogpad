package io.github.aixtin.blogpad

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 关于页：作者、仓库入口与版本检查。
 *
 * 版本比较只认 GitHub Releases 的 tag；检查失败就明说失败，不假装「已是最新」。
 */
class AboutActivity : Activity() {

    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnDownload: Button

    /** 手动检查发现的新版本，下载按钮跟着它走 */
    private var newVersion: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<TextView>(R.id.tvVersion).text =
            "v${UpdateChecker.currentVersion(this)}"
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowBlog).setOnClickListener { openUrl(UpdateChecker.BLOG_URL) }
        findViewById<View>(R.id.rowRepo).setOnClickListener { openUrl(UpdateChecker.REPO_URL) }
        findViewById<View>(R.id.rowIssues).setOnClickListener { openUrl(UpdateChecker.ISSUES_URL) }

        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnDownload = findViewById(R.id.btnDownload)

        btnCheckUpdate.setOnClickListener { checkUpdate() }
        btnDownload.setOnClickListener {
            val version = newVersion ?: return@setOnClickListener
            openUrl(UpdateChecker.downloadUrl(version))
        }
    }

    private fun checkUpdate() {
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "正在检查…"
        setStatus("正在读取 GitHub Releases…", R.color.text_secondary)

        UpdateChecker.check { result ->
            btnCheckUpdate.isEnabled = true
            btnCheckUpdate.text = "检查更新"
            val current = UpdateChecker.currentVersion(this)
            when (result) {
                is UpdateChecker.Result.Found -> {
                    if (UpdateChecker.isNewer(result.version, current)) {
                        newVersion = result.version
                        setStatus("发现新版本 ${result.version}，当前 v$current", R.color.brand)
                        btnDownload.text = "下载 ${result.version}"
                        btnDownload.visibility = View.VISIBLE
                    } else {
                        newVersion = null
                        setStatus("已是最新版本（v$current）", R.color.success)
                        btnDownload.visibility = View.GONE
                    }
                }
                UpdateChecker.Result.NoRelease -> {
                    newVersion = null
                    setStatus("仓库还没有发布版本", R.color.text_secondary)
                    btnDownload.visibility = View.GONE
                }
                UpdateChecker.Result.Error -> {
                    newVersion = null
                    setStatus("检查失败，确认网络后重试", R.color.danger)
                    btnDownload.visibility = View.GONE
                }
            }
        }
    }

    private fun setStatus(text: String, colorRes: Int) {
        tvUpdateStatus.text = text
        tvUpdateStatus.setTextColor(getColor(colorRes))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
