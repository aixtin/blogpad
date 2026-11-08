package io.github.aixtin.blogpad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 首页：远端文章列表。列表内容就是 content/post 的投影，
 * 每次进入/返回都重新拉取，不做本地缓存（v1.4 再考虑离线）。
 */
class PostListActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = PostAdapter()

    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_list)

        listView = findViewById(R.id.listPosts)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvStatus)

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.postAt(position)?.let { openEditor(it.path) }
        }

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

    private fun load() {
        if (loading) return
        val config = ConfigStore.load(this)
        val missing = config.connectionReady() ?: config.siteReady()
        if (missing != null) {
            adapter.setItems(emptyList())
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "还没有配置服务器\n先到「设置」里填写连接和站点信息"
            showStatus(missing, true)
            return
        }

        loading = true
        tvEmpty.visibility = View.GONE
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
        adapter.setItems(posts)
        if (posts.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "这个目录下还没有文章\n点右上角「新建」写第一篇"
            showStatus("0 篇", false)
        } else {
            tvEmpty.visibility = View.GONE
            showStatus("共 ${posts.size} 篇", false)
        }
    }

    private fun onLoadFailed(error: String) {
        loading = false
        adapter.setItems(emptyList())
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "读取失败\n检查网络与服务器配置后点「刷新」"
        showStatus(error, true)
    }

    private fun showStatus(message: String, isError: Boolean) {
        tvStatus.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (message.isBlank()) return
        tvStatus.setTextColor(Color.parseColor(if (isError) "#D93025" else "#6B7280"))
        tvStatus.text = message
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
