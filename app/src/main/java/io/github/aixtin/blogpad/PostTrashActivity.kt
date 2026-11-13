package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
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
 * 文章回收站。
 *
 * 只看 posts 那一份，附件回收站在附件管家里，两边互不可见。
 * 删除文章时 md 和只被它引用的图已经一起搬进来，所以还原是整批一起回去：
 * 正文里的图片是根相对路径，单独把 md 搬回去只会得到一堆破图。
 *
 * 点一下勾选、长按看单篇操作，跟附件管家一个手感。
 */
class PostTrashActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnRefresh: TextView
    private lateinit var btnPurgeAll: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnDanger: Button

    private val adapter = PostTrashAdapter()
    private val selected = LinkedHashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private var posts: List<TrashPost> = emptyList()
    private var config = Config()
    private var busy = false

    private val mainHandler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            if (isFinishing || isDestroyed) return
            super.dispatchMessage(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_trash)

        listView = findViewById(R.id.listPostTrash)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progress)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnPurgeAll = findViewById(R.id.btnPurgeAll)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnDanger = findViewById(R.id.btnDanger)

        config = ConfigStore.load(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            posts.getOrNull(position)?.let { showActions(it) }
            true
        }
        btnRefresh.setOnClickListener { if (!busy) load() }
        btnPurgeAll.setOnClickListener { confirmPurgeAll() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnPrimary.setOnClickListener { onRestore() }
        btnDanger.setOnClickListener { onPurge() }

        val blocker = config.connectionReady() ?: config.siteReady()
        if (blocker != null) {
            AlertDialog.Builder(this)
                .setTitle("还进不了回收站")
                .setMessage(blocker)
                .setNegativeButton("返回") { _, _ -> finish() }
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                }
                .show()
            busy = true
            applyBottomState()
            return
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!busy && posts.isNotEmpty()) load()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ---------- 选中 ----------

    private fun toggle(position: Int) {
        if (busy) return
        val post = posts.getOrNull(position) ?: return
        if (!selected.add(post.remotePath)) selected.remove(post.remotePath)
        adapter.notifyDataSetChanged()
        applyBottomState()
    }

    private fun toggleSelectAll() {
        if (busy || posts.isEmpty()) return
        if (selected.size == posts.size) {
            selected.clear()
        } else {
            selected.clear()
            posts.forEach { selected.add(it.remotePath) }
        }
        adapter.notifyDataSetChanged()
        applyBottomState()
    }

    private fun selectedPosts(): List<TrashPost> =
        posts.filter { selected.contains(it.remotePath) }

    private fun applyBottomState() {
        val has = selected.isNotEmpty()
        val enabled = !busy && has
        btnPrimary.isEnabled = enabled
        btnPrimary.alpha = if (enabled) 1f else 0.4f
        btnDanger.isEnabled = enabled
        btnDanger.alpha = if (enabled) 1f else 0.4f
        btnSelectAll.isEnabled = !busy && posts.isNotEmpty()
        btnSelectAll.alpha = if (btnSelectAll.isEnabled) 1f else 0.4f
        btnSelectAll.text = if (posts.isNotEmpty() && selected.size == posts.size) "取消全选" else "全选"
        btnPurgeAll.isEnabled = !busy && posts.isNotEmpty()
        btnPurgeAll.alpha = if (btnPurgeAll.isEnabled) 1f else 0.4f
        btnPrimary.text = if (has) "还原 (${selected.size})" else "还原"
        btnDanger.text = if (has) "彻底删除 (${selected.size})" else "彻底删除"
    }

    // ---------- 读取 ----------

    private fun load() {
        setBusy(true, "正在读取回收站…")
        val snapshot = ConfigStore.load(this)
        config = snapshot
        worker.execute {
            try {
                val list = TrashStore.listPostTrash(snapshot)
                mainHandler.post {
                    posts = list
                    selected.retainAll(list.map { it.remotePath }.toSet())
                    setBusy(false, null)
                    adapter.notifyDataSetChanged()
                    applyEmptyState()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    posts = emptyList()
                    selected.clear()
                    adapter.notifyDataSetChanged()
                    applyEmptyState()
                    setBusy(false, null)
                    tvEmpty.text = "读取失败：${e.message ?: e.javaClass.simpleName}"
                    tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun applyEmptyState() {
        if (posts.isEmpty()) {
            tvEmpty.text = "回收站是空的。\n\n删除文章时会先搬到这里，线上当场 404，随时可以搬回去。"
            tvEmpty.visibility = View.VISIBLE
            tvSummary.text = "没有可还原的文章"
        } else {
            tvEmpty.visibility = View.GONE
            val files = posts.sumOf { it.attachmentCount }
            tvSummary.text = "共 ${posts.size} 篇 · 带着 $files 个附件"
        }
    }

    // ---------- 单篇（长按） ----------

    private fun showActions(post: TrashPost) {
        if (busy) return
        val extra = if (post.attachmentCount > 0) "，连 ${post.attachmentCount} 个附件一起" else ""
        AlertDialog.Builder(this)
            .setTitle(post.title.ifEmpty { post.relPath.substringAfterLast('/') })
            .setItems(
                arrayOf(
                    "还原$extra（不构建）",
                    "还原${extra}并构建",
                    "彻底删除$extra"
                )
            ) { _, which ->
                when (which) {
                    0 -> runRestore(listOf(post), build = false)
                    1 -> runRestore(listOf(post), build = true)
                    2 -> confirmPurge(listOf(post))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 批量 ----------

    private fun onRestore() {
        val targets = selectedPosts()
        if (targets.isEmpty() || busy) return
        val extra = targets.sumOf { it.attachmentCount }
        val detail = if (extra > 0) "连 ${extra} 个附件一起" else ""
        AlertDialog.Builder(this)
            .setTitle("还原 ${targets.size} 篇")
            .setMessage("把选中的文章${detail}搬回原来的位置。\n\n「还原并构建」直接重新上线；「仅还原」先退回草稿，由你自己决定改还是发。")
            .setNegativeButton("取消", null)
            .setNeutralButton("仅还原") { _, _ -> runRestore(targets, build = false) }
            .setPositiveButton("还原并构建") { _, _ -> runRestore(targets, build = true) }
            .show()
    }

    private fun runRestore(targets: List<TrashPost>, build: Boolean) {
        if (targets.isEmpty()) return
        setBusy(true, if (build) "正在搬回并构建…" else "正在搬回…")
        val snapshot = ConfigStore.load(this)
        worker.execute {
            try {
                val result = TrashStore.restorePosts(snapshot, targets, build)
                mainHandler.post {
                    setBusy(false, null)
                    if (result.conflicts.isNotEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("原位置已经有同名文件")
                            .setMessage(
                                "下面这些位置被占了，一个都没还原，回收站里的东西原样留着：\n\n" +
                                    result.conflicts.joinToString("\n")
                            )
                            .setPositiveButton("知道了", null)
                            .show()
                        return@post
                    }
                    val tail = if (build) "，已重新构建" else "，已退回草稿：线上仍是 404"
                    val count = targets.size
                    toast("已还原 $count 篇$tail")
                    selected.clear()
                    load()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false, null)
                    AlertDialog.Builder(this)
                        .setTitle("还原失败")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun onPurge() {
        val targets = selectedPosts()
        if (targets.isEmpty() || busy) return
        confirmPurge(targets)
    }

    private fun confirmPurge(targets: List<TrashPost>) {
        val names = targets.take(6).joinToString("\n") {
            "· " + it.relPath.substringAfterLast('/')
        }
        val more = if (targets.size > 6) "\n… 还有 ${targets.size - 6} 篇" else ""
        val extra = targets.sumOf { it.attachmentCount }
        val withMedia = if (extra > 0) "连同 $extra 个附件一起清掉。" else ""
        AlertDialog.Builder(this)
            .setTitle("彻底删除 ${targets.size} 篇？")
            .setMessage("$names$more\n\n${withMedia}这一步不进回收站，删掉就找不回来了。")
            .setNegativeButton("取消", null)
            .setPositiveButton("彻底删除") { _, _ -> runPurge(targets) }
            .show()
    }

    private fun runPurge(targets: List<TrashPost>) {
        setBusy(true, "正在彻底删除…")
        val snapshot = ConfigStore.load(this)
        worker.execute {
            try {
                val removed = TrashStore.purgePosts(snapshot, targets)
                mainHandler.post {
                    setBusy(false, null)
                    toast(if (removed == targets.size) "已彻底删除 $removed 篇" else "删了 $removed / ${targets.size} 篇，其余可能已经不在了")
                    selected.clear()
                    load()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false, null)
                    toast("操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun confirmPurgeAll() {
        if (busy || posts.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空文章回收站？")
            .setMessage("${posts.size} 篇文章连同它们的附件会被永久删除，找不回来。附件库里还有别的文件不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> runPurgeAll() }
            .show()
    }

    private fun runPurgeAll() {
        setBusy(true, "正在清空…")
        val snapshot = ConfigStore.load(this)
        worker.execute {
            try {
                val ok = TrashStore.purgeAll(snapshot, TrashKind.POSTS)
                mainHandler.post {
                    setBusy(false, null)
                    toast(if (ok) "文章回收站已清空" else "清空失败")
                    selected.clear()
                    load()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    setBusy(false, null)
                    toast("操作失败：${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------- 状态 ----------

    private fun setBusy(value: Boolean, label: String?) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        btnRefresh.isEnabled = !value
        btnRefresh.alpha = if (value) 0.4f else 1f
        if (label != null) tvSummary.text = label
        applyBottomState()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------- 列表 ----------

    private inner class PostTrashAdapter : BaseAdapter() {

        override fun getCount(): Int = posts.size

        override fun getItem(position: Int): Any? = posts.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_trash_post, parent, false)
            val check = view.findViewById<CheckBox>(R.id.cbSelect)
            val title = view.findViewById<TextView>(R.id.tvTitle)
            val meta = view.findViewById<TextView>(R.id.tvMeta)
            val post = posts.getOrNull(position) ?: return view

            title.text = post.title.ifEmpty { post.relPath.substringAfterLast('/') }
            val parts = StringBuilder()
            if (post.time.isNotEmpty()) parts.append(post.time) else parts.append(post.batch)
            parts.append(" · ").append(MediaLibrary.formatSize(post.size))
            if (post.attachmentCount > 0) {
                parts.append(" · ").append(post.attachmentCount).append(" 个附件")
            } else {
                parts.append(" · 无附件")
            }
            parts.append("\n").append(post.relPath)
            meta.text = parts.toString()
            check.isChecked = selected.contains(post.remotePath)
            return view
        }
    }
}
