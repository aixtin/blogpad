package io.github.aixtin.blogpad

import android.content.Context

/**
 * 发布流程：上传暂存附件 -> 替换正文占位 -> 写 md -> 构建 -> 清理暂存副本。
 *
 * 顺序不能反：附件必须先落到远端，md 里的链接才有东西可指；
 * 清理必须在构建成功之后，失败时暂存文件还得留着给下次重试。
 */
object PublishFlow {

    data class Outcome(
        val save: SaveResult,
        val uploaded: Int
    )

    fun run(
        context: Context,
        config: Config,
        draft: PostDraft,
        originalPath: String?,
        coverInboxId: String?,
        inbox: Map<String, InboxItem>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Outcome {
        val slug = SshPublisher.resolveSlug(draft.slug)
        val ids = AttachmentInbox.referencedIds(draft.body)

        val missing = ids.filter { !inbox.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw PublishException(
                "正文里有 ${missing.size} 个附件在本地暂存区找不到了（可能被清理过）。\n\n" +
                    "请把这几处引用删掉，或者重新插入附件后再发布。"
            )
        }

        // 同一个 id 既插进正文又被设为封面时只传一次，按封面落目录
        val uploads = LinkedHashMap<String, SshPublisher.MediaUpload>()
        ids.forEach { id ->
            val item = inbox[id] ?: return@forEach
            val kind = if (id == coverInboxId) MediaKind.COVER else item.kind
            uploads[id] = SshPublisher.MediaUpload(id, kind, item.file(context), item.displayName)
        }
        val coverItem = inbox[coverInboxId]
        if (coverItem != null && !uploads.containsKey(coverItem.id)) {
            uploads[coverItem.id] = SshPublisher.MediaUpload(
                coverItem.id,
                MediaKind.COVER,
                coverItem.file(context),
                coverItem.displayName
            )
        }

        val mapping = SshPublisher.uploadMediaBatch(config, slug, uploads.values.toList(), onProgress)

        var body = draft.body
        mapping.forEach { (id, ref) -> body = body.replace(AttachmentInbox.SCHEME + id, ref.urlPath) }

        val cover = coverItem?.let { mapping[it.id]?.urlPath } ?: draft.coverImage
        val save = PostRepository.save(config, draft.copy(body = body, coverImage = cover), originalPath)

        // 只有走到这里，附件才算真的进了站点，副本可以删了
        AttachmentInbox.remove(context, mapping.keys)
        return Outcome(save, mapping.size)
    }
}
