package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 首页：远端文章列表。
 *
 * 在线时列表是 content/post 的投影，每次进入/刷新都重拉；拉不到时退到最近一次
 * 成功的本地快照并在状态栏标明「离线缓存」，离线状态下不放开删除与状态切换——
 * 写操作必须以远端为准，缓存不能替远端做决定。
 */
class PostListActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = PostAdapter()

    /** 全量数据，搜索只过滤展示层，不动这份 */
    private var allPosts: List<PostMeta> = emptyList()

    private var loading = false

    /** >0 表示当前展示的是本地快照，值为快照时间 */
    private var offlineStamp = 0L
    private var lastError: String = ""

    /** 读取失败且无缓存可退：空列表要说明是“读不到”，不是“没有文章” */
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_list)

        listView = findViewById(R.id.listPosts)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.postAt(position)?.let { openEditor(it.path) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            adapter.postAt(position)?.let { showActions(it) }
            true
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        findViewById<TextView>(R.id.btnNew).setOnClickListener { openEditor(null) }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { load() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    private fun openEditor(path: String?) {
        val intent = Intent(this, MainActivity::class.java)
        if (path != null) intent.putExtra(MainActivity.EXTRA_PATH, path)
        startActivity(intent)
    }

    // ---------- 拉取 ----------

    private fun load() {
        if (loading) return
        val config = ConfigStore.load(this)
        val missing = config.connectionReady() ?: config.siteReady()
        if (missing != null) {
            allPosts = emptyList()
            offlineStamp = 0L
            lastError = ""
            applyFilter()
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "还没有配置服务器\n先到「设置」里填写连接和站点信息"
            showStatus(missing, true)
            return
        }

        loading = true
        loadFailed = false
        showStatus("正在读取文章列表…", false)
        worker.execute {
            try {
                val posts = PostRepository.listPosts(config)
                handler.post { onLoaded(posts) }
            } catch (e: Exception) {
                handler.post { onLoadFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun onLoaded(posts: List<PostMeta>) {
        loading = false
        loadFailed = false
        offlineStamp = 0L
        lastError = ""
        allPosts = posts
        // 列表是远端唯一真相的投影，缓存只是它的副本；顺手把词表也刷新
        PostCacheStore.save(this, posts)
        TaxonomyStore.save(this, PostRepository.buildTaxonomy(posts))
        applyFilter()
    }

    private fun onLoadFailed(error: String) {
        loading = false
        lastError = error
        val cached = PostCacheStore.load(this)
        if (cached != null) {
            offlineStamp = cached.savedAt
            allPosts = cached.posts
            applyFilter()
        } else {
            offlineStamp = 0L
            loadFailed = true
            allPosts = emptyList()
            applyFilter()
            showStatus(error, true)
        }
    }

    // ---------- 搜索过滤 ----------

    private fun applyFilter() {
        val query = etSearch.text.toString().trim()
        val filtered = if (query.isEmpty()) allPosts else allPosts.filter { matches(it, query) }
        adapter.setItems(filtered)
        updateEmpty(query)
        updateStatus()
    }

    private fun matches(post: PostMeta, query: String): Boolean {
        val needle = query.lowercase()
        if (post.title.lowercase().contains(needle)) return true
        if (post.slug.lowercase().contains(needle)) return true
        if (post.tags.any { it.lowercase().contains(needle) }) return true
        return post.categories.any { it.lowercase().contains(needle) }
    }

    private fun updateEmpty(query: String) {
        if (adapter.count > 0) {
            tvEmpty.visibility = View.GONE
            return
        }
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = when {
            loadFailed -> "读取失败\n检查网络与服务器配置后点「刷新」"
            allPosts.isNotEmpty() && query.isNotEmpty() -> "没有匹配「$query」的文章"
            allPosts.isNotEmpty() -> "没有匹配的文章"
            offlineStamp > 0L -> "本地缓存里也没有文章\n联网后点「刷新」重新读取"
            else -> "这个目录下还没有文章\n点右上角「新建」写第一篇"
        }
    }

    private fun updateStatus() {
        if (offlineStamp > 0L) {
            val reason = lastError.replace('\n', ' ').take(40)
            val suffix = if (reason.isEmpty()) "" else "｜$reason"
            showStatus("离线缓存 · ${PostCacheStore.stamp(offlineStamp)} 同步$suffix", true)
            return
        }
        if (allPosts.isEmpty()) {
            showStatus("0 篇", false)
            return
        }
        val shown = adapter.count
        showStatus(
            if (shown == allPosts.size) "共 ${allPosts.size} 篇"
            else "匹配 $shown / ${allPosts.size} 篇",
            false
        )
    }

    private fun showStatus(message: String, isError: Boolean) {
        tvStatus.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (message.isBlank()) return
        tvStatus.setTextColor(Color.parseColor(if (isError) "#D93025" else "#6B7280"))
        tvStatus.text = message
    }

    // ---------- 长按：改状态 / 删除 ----------

    private fun showActions(post: PostMeta) {
        val toggleLabel = if (post.draft) "转为已发布" else "转为草稿"
        AlertDialog.Builder(this)
            .setTitle(post.title)
            .setItems(arrayOf(toggleLabel, "删除文章")) { _, which ->
                when (which) {
                    0 -> toggleDraft(post)
                    1 -> confirmDelete(post)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleDraft(post: PostMeta) {
        if (offlineStamp > 0L) {
            toast("当前离线，联网后再改状态")
            return
        }
        val target = !post.draft
        mutate(if (target) "正在发布…" else "正在转为草稿…") { config ->
            val changed = PostRepository.setDraft(config, post.path, target)
            when {
                !changed -> "《${post.title}》已经是${if (target) "已发布" else "草稿"}状态"
                target -> "已发布《${post.title}》"
                else -> "《${post.title}》已转为草稿"
            }
        }
    }

    private fun confirmDelete(post: PostMeta) {
        if (offlineStamp > 0L) {
            toast("当前离线，联网后再删除")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除《${post.title}》？")
            .setMessage("会删掉服务器上的 ${post.slug}.md 并重建站点。构建失败会自动恢复原文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                mutate("正在删除…") { config ->
                    PostRepository.delete(config, post.path)
                    "已删除《${post.title}》"
                }
            }
            .show()
    }

    /** 所有写操作的统一通道：失败要讲清楚原因，结束一律重新拉列表，避免界面和远端对不上 */
    private fun mutate(label: String, block: (Config) -> String) {
        if (loading) {
            toast("上一个操作还没结束，请稍候")
            return
        }
        val config = ConfigStore.load(this)
        val missing = config.connectionReady() ?: config.siteReady()
        if (missing != null) {
            toast(missing)
            return
        }

        loading = true
        showStatus(label, false)
        worker.execute {
            try {
                val done = block(config)
                handler.post {
                    loading = false
                    toast(done)
                    load()
                }
            } catch (e: Exception) {
                handler.post {
                    loading = false
                    AlertDialog.Builder(this)
                        .setTitle("操作失败")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("知道了", null)
                        .show()
                    load()
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 列表项：标题 / 日期 · slug / 草稿 · 标签 */
    private class PostAdapter : BaseAdapter() {

        private val items = ArrayList<PostMeta>()

        fun setItems(list: List<PostMeta>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun postAt(index: Int): PostMeta? = items.getOrNull(index)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any? = items.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(parent!!.context)
                    .inflate(R.layout.item_post, parent, false)
            val post = items[position]

            view.findViewById<TextView>(R.id.tvItemTitle).text = post.title

            val meta = StringBuilder()
            if (post.date.isNotEmpty()) meta.append(post.date).append("  ·  ")
            meta.append(post.slug)
            view.findViewById<TextView>(R.id.tvItemMeta).text = meta

            val foot = view.findViewById<TextView>(R.id.tvItemTags)
            val parts = ArrayList<String>()
            if (post.draft) parts.add("草稿")
            parts.addAll(post.tags.filter { !(post.draft && it == "草稿") })
            if (parts.isEmpty()) {
                foot.visibility = View.GONE
            } else {
                foot.visibility = View.VISIBLE
                foot.text = parts.joinToString("  ·  ")
                foot.setTextColor(Color.parseColor(if (post.draft) "#D93025" else "#2F6FED"))
            }
            return view
        }
    }
}
