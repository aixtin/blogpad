package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 附件管家。
 *
 * 只做三件事：看清附件库里有什么、看清哪些没人引用、把不要的搬走。
 * 不做重命名和移动 —— 静态站里文件路径就是文章的引用凭证，改名等于要求全局改写正文，
 * 那不是写作台该干的活。
 *
 * 删除一律先进回收站（站点根下的 .blogpad-trash），确认无用后再彻底清掉。
 */
class MediaManagerActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnMode: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnPrimary: TextView
    private lateinit var btnDanger: TextView
    private lateinit var btnPurgeAll: TextView

    private val adapter = MediaAdapter()
    private val selected = LinkedHashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var config = Config()
    private var mode = MODE_FILES
    private var scan: MediaScan? = null
    private var files: List<MediaFile> = emptyList()
    private var trashFiles: List<TrashFile> = emptyList()
    private var busy = false

    companion object {
        private const val MODE_FILES = 0
        private const val MODE_TRASH = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_manager)

        listView = findViewById(R.id.listMedia)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progress)
        btnMode = findViewById(R.id.btnMode)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnDanger = findViewById(R.id.btnDanger)
        btnPurgeAll = findViewById(R.id.btnPurgeAll)

        config = ConfigStore.load(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        btnMode.setOnClickListener { switchMode() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnPrimary.setOnClickListener { onPrimary() }
        btnDanger.setOnClickListener { onDanger() }
        btnPurgeAll.setOnClickListener { onPurgeAll() }

        val blocker = config.connectionReady() ?: config.siteReady() ?: config.mediaReady()
        if (blocker != null) {
            AlertDialog.Builder(this)
                .setTitle("还不能管理附件")
                .setMessage(blocker)
                .setNegativeButton("返回") { _, _ -> finish() }
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                .show()
            setBusy(true)
            return
        }
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ---------- 模式 ----------

    private fun switchMode() {
        if (busy) return
        mode = if (mode == MODE_FILES) MODE_TRASH else MODE_FILES
        selected.clear()
        applyModeUi()
        reload()
    }

    private fun applyModeUi() {
        val isTrash = mode == MODE_TRASH
        btnMode.text = if (isTrash) "看附件库" else "看回收站"
        btnPrimary.text = if (isTrash) "还原" else "移入回收站"
        btnDanger.visibility = if (isTrash) View.VISIBLE else View.GONE
        btnPurgeAll.visibility = if (isTrash) View.VISIBLE else View.GONE
        tvEmpty.text = if (isTrash) "回收站是空的" else "附件库里还没有文件"
        adapter.notifyDataSetChanged()
    }

    // ---------- 数据 ----------

    private fun reload() {
        setBusy(true)
        val snapshot = config
        val currentMode = mode
        worker.execute {
            try {
                if (currentMode == MODE_FILES) {
                    val result = MediaLibrary.scan(snapshot)
                    mainHandler.post {
                        scan = result
                        files = result.files
                        trashFiles = emptyList()
                        selected.clear()
                        setBusy(false)
                        renderFiles()
                    }
                } else {
                    val list = MediaLibrary.listTrash(snapshot)
                    mainHandler.post {
                        trashFiles = list
                        files = emptyList()
                        selected.clear()
                        setBusy(false)
                        renderTrash()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    showMessage("读取失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderFiles() {
        val result = scan
        if (result == null) {
            tvSummary.text = ""
            return
        }
        val orphanCount = result.orphans.size
        val text = StringBuilder()
        text.append("共 ").append(result.files.size).append(" 个文件 · ")
            .append(MediaLibrary.formatSize(result.totalBytes))
        text.append("\n").append(result.postCount).append(" 篇文章中，")
        if (orphanCount == 0) {
            text.append("没有发现未被引用的文件")
        } else {
            text.append(orphanCount).append(" 个文件没有任何引用 · ")
                .append(MediaLibrary.formatSize(result.orphanBytes))
        }
        tvSummary.text = text.toString()
        tvEmpty.visibility = if (result.files.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        updateBottomState()
    }

    private fun renderTrash() {
        if (trashFiles.isEmpty()) {
            tvSummary.text = "回收站里没有文件"
        } else {
            val bytes = trashFiles.fold(0L) { sum, f -> sum + f.size }
            val batches = trashFiles.map { it.batch }.distinct().size
            tvSummary.text = "回收站里 " + trashFiles.size + " 个文件 · " +
                MediaLibrary.formatSize(bytes) + " · " + batches + " 个批次"
        }
        tvEmpty.visibility = if (trashFiles.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        updateBottomState()
    }

    // ---------- 交互 ----------

    private fun rowCount(): Int = if (mode == MODE_FILES) files.size else trashFiles.size

    private fun rowKey(position: Int): String =
        if (mode == MODE_FILES) files[position].remotePath else trashFiles[position].remotePath

    private fun toggle(position: Int) {
        if (position < 0 || position >= rowCount()) return
        val key = rowKey(position)
        if (!selected.remove(key)) selected.add(key)
        adapter.notifyDataSetChanged()
        updateBottomState()
    }

    private fun toggleSelectAll() {
        if (selected.size == rowCount() && rowCount() > 0) {
            selected.clear()
        } else {
            selected.clear()
            for (i in 0 until rowCount()) selected.add(rowKey(i))
        }
        adapter.notifyDataSetChanged()
        updateBottomState()
    }

    private fun updateBottomState() {
        val count = rowCount()
        btnSelectAll.text = if (count > 0 && selected.size == count) "取消全选" else "全选"
        val picked = selected.size
        btnPrimary.isEnabled = picked > 0 && !busy
        btnPrimary.alpha = if (btnPrimary.isEnabled) 1f else 0.4f
        btnDanger.isEnabled = picked > 0 && !busy
        btnDanger.alpha = if (btnDanger.isEnabled) 1f else 0.4f
        btnPurgeAll.isEnabled = !busy && (mode == MODE_TRASH && trashFiles.isNotEmpty())
        btnPurgeAll.alpha = if (btnPurgeAll.isEnabled) 1f else 0.4f
        if (mode == MODE_FILES) {
            btnPrimary.text = if (picked > 0) "移入回收站 ($picked)" else "移入回收站"
        } else {
            btnPrimary.text = if (picked > 0) "还原 ($picked)" else "还原"
            btnDanger.text = if (picked > 0) "彻底删除 ($picked)" else "彻底删除"
        }
    }

    private fun onPrimary() {
        if (mode == MODE_FILES) trashSelected() else restoreSelected()
    }

    private fun onDanger() {
        if (mode != MODE_TRASH || selected.isEmpty()) return
        val targets = trashFiles.filter { selected.contains(it.remotePath) }
        if (targets.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("彻底删除")
            .setMessage(
                "将从服务器永久删除 ${targets.size} 个文件，不可恢复。\n\n" +
                    "如果你还需要它们，请先还原到站点里。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("永久删除") { _, _ -> runPurge(targets) }
            .show()
    }

    private fun onPurgeAll() {
        if (mode != MODE_TRASH || trashFiles.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空回收站")
            .setMessage(
                "将删除回收站里的全部 ${trashFiles.size} 个文件（含历史批次），不可恢复。\n\n" +
                    "附件库里的文件不受影响。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> runPurgeAll() }
            .show()
    }

    private fun trashSelected() {
        val targets = files.filter { selected.contains(it.remotePath) }
        if (targets.isEmpty()) return
        val inUse = targets.count { (scan?.refCount(it) ?: 0) > 0 }
        val message = StringBuilder()
        message.append("把 ${targets.size} 个文件移动到站点根目录下的 .blogpad-trash，")
            .append("站点里会立即访问不到它们。\n\n")
            .append("这不是永久删除，之后可以在回收站里还原。\n")
        if (inUse > 0) {
            message.append("\n其中 ").append(inUse)
                .append(" 个文件仍被文章引用，移走之后那些文章会出现 404。")
        }
        AlertDialog.Builder(this)
            .setTitle("移入回收站")
            .setMessage(message.toString())
            .setNegativeButton("取消", null)
            .setPositiveButton(if (inUse > 0) "仍然移入" else "移入") { _, _ -> runTrash(targets) }
            .show()
    }

    private fun restoreSelected() {
        val targets = trashFiles.filter { selected.contains(it.remotePath) }
        if (targets.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("还原")
            .setMessage("把这 ${targets.size} 个文件搬回原来的位置。同名文件会被覆盖。")
            .setNegativeButton("取消", null)
            .setPositiveButton("还原") { _, _ -> runRestore(targets) }
            .show()
    }

    private fun runTrash(targets: List<MediaFile>) {
        setBusy(true)
        val snapshot = config
        worker.execute {
            try {
                val moved = MediaLibrary.moveToTrash(snapshot, targets)
                mainHandler.post {
                    showMessage("已移入回收站：$moved 个")
                    selected.clear()
                    reload()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    showMessage("操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun runRestore(targets: List<TrashFile>) {
        setBusy(true)
        val snapshot = config
        worker.execute {
            try {
                val restored = MediaLibrary.restore(snapshot, targets)
                mainHandler.post {
                    showMessage("已还原：$restored 个")
                    selected.clear()
                    reload()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    showMessage("操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun runPurge(targets: List<TrashFile>) {
        setBusy(true)
        val snapshot = config
        worker.execute {
            try {
                val removed = MediaLibrary.purge(snapshot, targets)
                mainHandler.post {
                    showMessage("已永久删除：$removed 个")
                    selected.clear()
                    reload()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    showMessage("操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun runPurgeAll() {
        setBusy(true)
        val snapshot = config
        worker.execute {
            try {
                val ok = MediaLibrary.purgeAll(snapshot)
                mainHandler.post {
                    showMessage(if (ok) "回收站已清空" else "清空失败，回收站可能已经空了")
                    selected.clear()
                    reload()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false)
                    showMessage("操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------- 状态 ----------

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnMode.isEnabled = !value
        btnMode.alpha = if (value) 0.4f else 1f
        if (value) {
            tvSummary.text = "正在读取远端附件库…"
        }
        updateBottomState()
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------- 列表 ----------

    private inner class MediaAdapter : BaseAdapter() {

        override fun getCount(): Int = rowCount()

        override fun getItem(position: Int): Any? =
            if (mode == MODE_FILES) files.getOrNull(position) else trashFiles.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_media, parent, false)
            val check = view.findViewById<CheckBox>(R.id.cbSelect)
            val name = view.findViewById<TextView>(R.id.tvName)
            val meta = view.findViewById<TextView>(R.id.tvMeta)

            if (mode == MODE_FILES) {
                val file = files.getOrNull(position) ?: return view
                name.text = file.urlPath.substringAfterLast('/')
                val refs = scan?.refCount(file) ?: 0
                val dir = file.urlPath.substringBeforeLast('/').trimStart('/')
                val metaText = StringBuilder()
                metaText.append(dir).append(" · ").append(MediaLibrary.formatSize(file.size))
                if (file.modified > 0) {
                    metaText.append(" · ").append(MediaLibrary.formatTime(file.modified))
                }
                if (refs == 0) {
                    metaText.append("\n未被任何文章引用")
                    meta.setTextColor(getColor(R.color.danger))
                } else {
                    metaText.append("\n被 ").append(refs).append(" 篇文章引用")
                    meta.setTextColor(getColor(R.color.text_secondary))
                }
                meta.text = metaText.toString()
                check.isChecked = selected.contains(file.remotePath)
            } else {
                val file = trashFiles.getOrNull(position) ?: return view
                name.text = file.relPath.substringAfterLast('/')
                meta.text = file.relPath.substringBeforeLast('/', "") + "\n批次 " +
                    file.batch + " · " + MediaLibrary.formatSize(file.size)
                meta.setTextColor(getColor(R.color.text_secondary))
                check.isChecked = selected.contains(file.remotePath)
            }
            return view
        }
    }
}
