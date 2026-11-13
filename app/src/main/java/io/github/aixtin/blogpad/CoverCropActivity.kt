package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 封面裁剪页：进页面先按预设比例摆好框，剩下的事全交给用户。
 *
 * 不做双指缩放，也不记忆上次拖到哪——封面每次都是新图，上一张的构图没有参考价值。
 * 页面只有一件事：让用户决定这块图裁在哪。
 */
class CoverCropActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"

        /**
         * 裁完的字节有几百 KB，塞 Intent 容易撞上 Binder 上限，
         * 所以走进程内传递，父页面取一次就清空。
         */
        private var pendingBytes: ByteArray? = null
        private var pendingName: String = ""

        fun takeResult(): Pair<ByteArray, String>? {
            val bytes = pendingBytes ?: return null
            val name = pendingName
            pendingBytes = null
            pendingName = ""
            return bytes to name
        }
    }

    private lateinit var cropView: CoverCropView
    private lateinit var tvRatio: TextView
    private lateinit var btnLock: TextView

    private var presetW = 16f
    private var presetH = 9f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_URI)?.takeIf { it.isNotBlank() } ?: intent.dataString
        val uri = raw?.let { Uri.parse(it) }
        if (uri == null) {
            finish()
            return
        }

        CoverRatioStore.parse(CoverRatioStore.load(this))?.let {
            presetW = it.first
            presetH = it.second
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101114"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(2))
        }
        tvRatio = actionText("比例 " + ratioLabel(presetW, presetH)).apply {
            setOnClickListener { editPreset() }
        }
        val btnReset = actionText("重置").apply {
            setOnClickListener { cropView.resetFrame() }
        }
        top.addView(tvRatio)
        top.addView(btnReset)

        cropView = CoverCropView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPreset(presetW, presetH)
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(12))
        }
        btnLock = actionText("等比例").apply {
            setOnClickListener {
                cropView.locked = !cropView.locked
                syncLockLabel()
            }
        }
        val btnCancel = actionText("取消").apply {
            setOnClickListener { finish() }
        }
        val btnDone = actionText("完成").apply {
            setTextColor(Color.parseColor("#8AB4FF"))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { finishWithCrop() }
        }
        bottom.addView(btnLock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(btnCancel)
        bottom.addView(btnDone)

        root.addView(top)
        root.addView(cropView)
        root.addView(bottom)
        setContentView(root)

        syncLockLabel()
        loadBitmap(uri)
    }

    private fun loadBitmap(uri: Uri) {
        Thread {
            val bmp = CoverCrop.decode(this, uri)
            runOnUiThread {
                if (bmp == null) {
                    // 用 RESULT_FIRST_USER 区分于用户主动取消：上层收到它会退回直接暂存原图
                    setResult(RESULT_FIRST_USER)
                    finish()
                } else {
                    cropView.setBitmap(bmp)
                }
            }
        }.start()
    }

    private fun finishWithCrop() {
        val bmp = cropView.bitmapOrNull() ?: return
        val rect = cropView.frameInBitmap() ?: return
        Thread {
            val bytes = CoverCrop.compress(bmp, rect)
            val name = if (bytes != null) CoverCrop.name(rect.width(), rect.height()) else ""
            runOnUiThread {
                if (bytes == null) {
                    Toast.makeText(this, "这张图处理失败，换一张试试", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                pendingBytes = bytes
                pendingName = name
                setResult(RESULT_OK)
                finish()
            }
        }.start()
    }

    private fun syncLockLabel() {
        btnLock.text = if (cropView.locked) "等比例" else "自由"
        btnLock.setTextColor(
            if (cropView.locked) Color.parseColor("#C9CDD4") else Color.parseColor("#8AB4FF")
        )
    }

    private fun editPreset() {
        val input = EditText(this).apply {
            setText(ratioLabel(presetW, presetH))
            setSingleLine()
            setSelection(text.length)
            hint = "比如 16:9、2:1、1.78"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("封面比例")
            .setMessage("按你站点主题的封面比例填。只决定框的初始形状，拖角之后就按你拖的走。填错或不填，按 16:9。")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> applyPreset(input.text.toString()) }
            .show()
    }

    private fun applyPreset(text: String) {
        val parsed = CoverRatioStore.parse(text)
        if (parsed == null) {
            presetW = 16f
            presetH = 9f
            Toast.makeText(this, "没认出这个比例，仍按 16:9", Toast.LENGTH_SHORT).show()
        } else {
            presetW = parsed.first
            presetH = parsed.second
        }
        CoverRatioStore.save(this, presetW, presetH)
        tvRatio.text = "比例 " + ratioLabel(presetW, presetH)
        cropView.locked = true
        cropView.setPreset(presetW, presetH)
        syncLockLabel()
    }

    // ---------- 小工具 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun actionText(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#E6E8EB"))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1FFFFFFF"))
            cornerRadius = dp(10).toFloat()
        }
        isClickable = true
    }
}
