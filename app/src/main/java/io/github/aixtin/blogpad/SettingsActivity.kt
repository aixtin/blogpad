package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var rbKey: TextView
    private lateinit var rbPassword: TextView
    private lateinit var groupKey: LinearLayout
    private lateinit var groupPassword: LinearLayout
    private lateinit var etPrivateKey: EditText
    private lateinit var etPassphrase: EditText
    private lateinit var etPassword: EditText
    private lateinit var etSiteRoot: EditText
    private lateinit var etPostDir: EditText
    private lateinit var etBuildCmd: EditText
    private lateinit var etSiteUrl: EditText
    private lateinit var etLinkTemplate: EditText
    private lateinit var etMediaRoot: EditText
    private lateinit var cbMediaGrouping: CheckBox
    private lateinit var etMediaSubdirs: EditText
    private lateinit var btnTest: Button
    private lateinit var tvTestStatus: TextView

    private var config = Config()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQ_PICK_KEY = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        rbKey = findViewById(R.id.rbKey)
        rbPassword = findViewById(R.id.rbPassword)
        groupKey = findViewById(R.id.groupKey)
        groupPassword = findViewById(R.id.groupPassword)
        etPrivateKey = findViewById(R.id.etPrivateKey)
        etPassphrase = findViewById(R.id.etPassphrase)
        etPassword = findViewById(R.id.etPassword)
        etSiteRoot = findViewById(R.id.etSiteRoot)
        etPostDir = findViewById(R.id.etPostDir)
        etBuildCmd = findViewById(R.id.etBuildCmd)
        etSiteUrl = findViewById(R.id.etSiteUrl)
        etLinkTemplate = findViewById(R.id.etLinkTemplate)
        etMediaRoot = findViewById(R.id.etMediaRoot)
        cbMediaGrouping = findViewById(R.id.cbMediaGrouping)
        etMediaSubdirs = findViewById(R.id.etMediaSubdirs)
        btnTest = findViewById(R.id.btnTest)
        tvTestStatus = findViewById(R.id.tvTestStatus)

        config = ConfigStore.load(this)
        bindConfig(config)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { saveAndFinish() }
        findViewById<Button>(R.id.btnImportKey).setOnClickListener { pickKeyFile() }
        findViewById<Button>(R.id.btnClearKey).setOnClickListener { etPrivateKey.setText("") }
        btnTest.setOnClickListener { runTest() }

        rbKey.setOnClickListener { selectAuth(true) }
        rbPassword.setOnClickListener { selectAuth(false) }
        applyAuthMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun bindConfig(c: Config) {
        etHost.setText(c.host)
        etPort.setText(c.port.toString())
        etUser.setText(c.user)
        if (c.authType == "password") selectAuth(false) else selectAuth(true)
        etPrivateKey.setText(c.privateKey)
        etPassphrase.setText(c.passphrase)
        etPassword.setText(c.password)
        etSiteRoot.setText(c.siteRoot)
        etPostDir.setText(c.postDir)
        etBuildCmd.setText(c.buildCmd)
        etSiteUrl.setText(c.siteUrl)
        etLinkTemplate.setText(c.linkTemplate)
        etMediaRoot.setText(c.mediaRoot)
        cbMediaGrouping.isChecked = c.mediaGrouping
        etMediaSubdirs.setText(c.mediaSubdirs)
        syncMediaInputs()
        cbMediaGrouping.setOnCheckedChangeListener { _, _ -> syncMediaInputs() }
    }

    /** 关掉分类后子目录名无意义，直接置灰，免得用户以为填了不生效 */
    private fun syncMediaInputs() {
        val on = cbMediaGrouping.isChecked
        etMediaSubdirs.isEnabled = on
        etMediaSubdirs.alpha = if (on) 1f else 0.4f
    }

    private fun collectConfig(): Config {
        val port = etPort.text.toString().trim().toIntOrNull() ?: 22
        return Config(
            host = etHost.text.toString().trim(),
            port = port,
            user = etUser.text.toString().trim(),
            authType = if (rbPassword.isSelected) "password" else "key",
            privateKey = etPrivateKey.text.toString().trim(),
            passphrase = etPassphrase.text.toString(),
            password = etPassword.text.toString(),
            siteRoot = etSiteRoot.text.toString().trim(),
            postDir = etPostDir.text.toString().trim().ifEmpty { "content/post" },
            buildCmd = etBuildCmd.text.toString().trim().ifEmpty { "hugo --gc --minify" },
            siteUrl = etSiteUrl.text.toString().trim(),
            linkTemplate = etLinkTemplate.text.toString().trim(),
            hostFingerprint = config.hostFingerprint,
            mediaRoot = etMediaRoot.text.toString().trim().ifEmpty { Config.DEFAULT_MEDIA_ROOT },
            mediaGrouping = cbMediaGrouping.isChecked,
            mediaSubdirs = etMediaSubdirs.text.toString().trim()
                .ifEmpty { Config.DEFAULT_MEDIA_SUBDIRS }
        )
    }

    private fun saveAndFinish() {
        val next = collectConfig()
        next.connectionReady()?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            return
        }
        next.siteReady()?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            return
        }
        next.mediaReady()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            etMediaRoot.requestFocus()
            return
        }
        // 连接目标变更后旧指纹失效
        if (next.host != config.host || next.port != config.port) {
            next.hostFingerprint = ""
        }
        ConfigStore.save(this, next)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun selectAuth(useKey: Boolean) {
        rbKey.isSelected = useKey
        rbPassword.isSelected = !useKey
        applyAuthMode()
    }

    private fun applyAuthMode() {
        val useKey = rbKey.isSelected
        groupKey.visibility = if (useKey) View.VISIBLE else View.GONE
        groupPassword.visibility = if (useKey) View.GONE else View.VISIBLE
    }

    private fun pickKeyFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_KEY)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_KEY && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }.orEmpty()
                if (text.contains("PRIVATE KEY")) {
                    etPrivateKey.setText(text)
                    Toast.makeText(this, "私钥已导入", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "这个文件不是 OpenSSH 私钥", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "读取失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runTest() {
        val target = collectConfig()
        target.connectionReady()?.let {
            tvTestStatus.setTextColor(getColor(R.color.danger))
            tvTestStatus.text = it
            return
        }
        btnTest.isEnabled = false
        btnTest.text = "正在连接…"
        tvTestStatus.setTextColor(getColor(R.color.text_secondary))
        tvTestStatus.text = "正在连接…"

        worker.execute {
            val result = SshPublisher.test(target)
            mainHandler.post {
                btnTest.isEnabled = true
                btnTest.text = "测试连接"
                val (ok, message, fingerprint) = result
                if (fingerprint != null) {
                    askTrust(target, fingerprint)
                } else if (ok) {
                    tvTestStatus.setTextColor(getColor(R.color.success))
                    tvTestStatus.text = message
                } else {
                    tvTestStatus.setTextColor(getColor(R.color.danger))
                    tvTestStatus.text = message
                }
            }
        }
    }

    private fun askTrust(target: Config, fingerprint: String) {
        tvTestStatus.setTextColor(getColor(R.color.text_secondary))
        tvTestStatus.text = "等待确认主机指纹"

        val content = layoutInflater.inflate(R.layout.dialog_confirm_host, null)
        content.findViewById<TextView>(R.id.tvHost).text =
            "${target.user}@${target.host}:${target.port}"
        content.findViewById<TextView>(R.id.tvFingerprint).text = fingerprint

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .create()

        content.findViewById<TextView>(R.id.btnTrust).setOnClickListener {
            dialog.dismiss()
            val updated = collectConfig()
            updated.hostFingerprint = fingerprint
            config = updated
            ConfigStore.save(this, updated)
            Toast.makeText(this, "已信任，正在重新连接…", Toast.LENGTH_SHORT).show()
            runTest()
        }
        content.findViewById<TextView>(R.id.btnCancelTrust).setOnClickListener {
            dialog.dismiss()
            tvTestStatus.setTextColor(getColor(R.color.danger))
            tvTestStatus.text = "已取消，未信任该主机"
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_dialog)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
