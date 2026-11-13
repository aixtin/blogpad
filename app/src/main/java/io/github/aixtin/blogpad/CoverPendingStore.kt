package io.github.aixtin.blogpad

import android.content.Context

/**
 * 「封面已经选好、还没发布」这一小段状态的落盘。
 *
 * 封面字节本身早就复制进 AttachmentInbox 了，进程被杀也不会消失；
 * 丢的只是指向它的那个 id —— 它只活在 PublishMetaActivity 的字段和 Intent 里，
 * 后台一被回收，再进来就是空白，用户只能重新选图重新裁。
 * 所以这里只补一个 id，进页面时 Intent 没带就从这儿捡回来。
 */
object CoverPendingStore {
    private const val PREFS = "blogpad_cover_pending"
    private const val KEY_ID = "id"

    fun save(context: Context, id: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (id.isNullOrBlank()) editor.remove(KEY_ID) else editor.putString(KEY_ID, id)
        editor.apply()
    }

    fun load(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_ID, null)
        ?.ifBlank { null }

    fun clear(context: Context) = save(context, null)

    /** 记录的 id 还指得到真文件才作数，附件已被清掉就别拿死 id 出来 */
    fun loadAlive(context: Context): String? {
        val id = load(context) ?: return null
        if (AttachmentInbox.find(context, id) != null) return id
        clear(context)
        return null
    }
}
