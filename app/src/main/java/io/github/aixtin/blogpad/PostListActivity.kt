package io.github.aixtin.blogpad

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var searchInline: View
    private lateinit var tvTitle: View
    private lateinit var btnSearch: ImageButton
    private lateinit var btnClearSearch: View
    private var searchOpen = false

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    /** 页面已关闭时回调直接丢弃，避免后台结果回来再弹窗把 App 弄崩 */
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun dispatchMessage(msg: Message) {
            if (isFinishing || isDestroyed) return
            super.dispatchMessage(msg)
        }
    }
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
        swipeRefresh = findViewById(R.id.swipeRefresh)
        searchInline = findViewById(R.id.searchInline)
        tvTitle = findViewById(R.id.tvTitle)
        btnSearch = findViewById(R.id.btnSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)

        swipeRefresh.setColorSchemeColors(getColor(R.color.brand))
        swipeRefresh.setOnRefreshListener { load() }

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

        findViewById<View>(R.id.btnNew).setOnClickListener { openEditor(null) }
        btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        findViewById<View>(R.id.btnClearSearch).setOnClickListener { etSearch.setText("") }
        btnClearSearch.visibility = View.INVISIBLE
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 标题即入口：点「BlogPad」进关于页
        tvTitle.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                closeSearch()
                true
            } else {
                false
            }
        }

        // 每天最多查一次，有新版用 Toast 轻提示一句，几秒后自动消失
        UpdateChecker.autoCheck(this) { version ->
            Toast.makeText(
                this,
                "检测到新版本 $version，请到关于页更新",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------- 搜索：在顶栏原地向左展开 ----------

    private fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        btnSearch.setImageResource(R.drawable.ic_close)
        tvTitle.visibility = View.GONE

        searchInline.animate().cancel()
        searchInline.alpha = 0f
        searchInline.visibility = View.VISIBLE
        searchInline.post {
            searchInline.translationX = searchInline.width.toFloat()
            searchInline.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(180)
                .start()
            etSearch.requestFocus()
            etSearch.setSelection(etSearch.text.length)
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
        updateSearchTint()
    }

    private fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
        btnSearch.setImageResource(R.drawable.ic_search)

        searchInline.animate().cancel()
        searchInline.animate()
            .translationX(searchInline.width.toFloat())
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                searchInline.visibility = View.GONE
                searchInline.translationX = 0f
                searchInline.alpha = 1f
            }
            .start()

        tvTitle.animate().cancel()
        tvTitle.visibility = View.VISIBLE
        tvTitle.alpha = 0f
        tvTitle.animate().alpha(1f).setDuration(180).start()
        updateSearchTint()
    }

    /** 收起后如果还在筛选，放大镜保持高亮，避免用户忘记列表不是全量 */
    private fun updateSearchTint() {
        val active = searchOpen || etSearch.text.toString().isNotEmpty()
        btnSearch.setColorFilter(
            getColor(if (active) R.color.brand else R.color.text_secondary)
        )
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (searchOpen) closeSearch() else super.onBackPressed()
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
        if (loading) {
            swipeRefresh.isRefreshing = false
            return
        }
        val config = ConfigStore.load(this)
        val missing = config.connectionReady() ?: config.siteReady()
        if (missing != null) {
            swipeRefresh.isRefreshing = false
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
        swipeRefresh.isRefreshing = false
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
        swipeRefresh.isRefreshing = false
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
        // 空输入时清除按钮没有意义，和右侧的收起图标容易混淆，直接藏掉
        btnClearSearch.visibility = if (query.isEmpty()) View.INVISIBLE else View.VISIBLE
        val filtered = if (query.isEmpty()) allPosts else allPosts.filter { matches(it, query) }
        adapter.setItems(filtered)
        updateEmpty(query)
        updateStatus()
        updateSearchTint()
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
        tvStatus.text = message
        if (message.isBlank()) return
        tvStatus.setTextColor(getColor(if (isError) R.color.danger else R.color.text_secondary))
    }

    // ---------- 长按：改状态 / 删除 ----------

    /**
     * 长按只有一个正经用途：把草稿发出去，或者把文章下线。
     * 「转为草稿」不再提供——它只改一行 front matter，文件和线上产物都还在，
     * 撤不下来也不删东西，想临时下线直接用回收站，那条路才真的把文章搬走。
     */
    private fun showActions(post: PostMeta) {
        if (!post.draft) {
            confirmDelete(post)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(post.title)
            .setItems(arrayOf("转为已发布", "移入回收站")) { _, which ->
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
            .setTitle("把《${post.title}》移入回收站？")
            .setMessage(
                "文章会搬到站点的 .blogpad-trash 里，只被它引用的图片一起搬走，" +
                    "然后立即重建站点，线上这篇文章当场 404。\n\n" +
                    "随时可以去设置页的「文章」回收站把它搬回来。构建失败会自动整批退回，文章不受影响。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("移入回收站") { _, _ ->
                mutate("正在移入回收站…") { config ->
                    val result = PostRepository.delete(config, post.path)
                    val extra = if (result.attachments > 0) "，带走 ${result.attachments} 张独占图" else ""
                    "《${post.title}》已下线$extra"
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
            view.findViewById<TextView>(R.id.tvItemDate).text = post.date

            // 状态只看 front matter：草稿就是线上没有，已发布就是线上有
            val status = view.findViewById<TextView>(R.id.tvItemStatus)
            status.text = if (post.draft) "草稿" else "已发布"
            status.setTextColor(
                view.context.getColor(if (post.draft) R.color.warning else R.color.text_secondary)
            )

            val foot = view.findViewById<TextView>(R.id.tvItemTags)
            val tags = post.tags.filter { !(post.draft && it == "草稿") }
            if (tags.isEmpty()) {
                foot.visibility = View.GONE
            } else {
                foot.visibility = View.VISIBLE
                foot.text = tags.joinToString("  ·  ")
            }
            return view
        }
    }
}
