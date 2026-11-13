package io.github.aixtin.blogpad

import android.content.Intent
import android.os.Build
import android.provider.MediaStore

/**
 * 附件与封面共用的取图入口。
 *
 * 相册统一走系统 Photo Picker（Android 13+）：网格里图片视频混排、可多选、不需要存储权限，
 * 各家 ROM 表现一致。机型不支持或组件缺失时返回 null，由调用方回落到文档选择器。
 */
object MediaPick {
    /** 多选上限，与文档选择器的截断数保持一致 */
    const val MAX_MULTI = 9

    val galleryMimes: Array<String> = arrayOf("image/*", "video/*")
    val imageMimes: Array<String> = arrayOf("image/*")

    /**
     * 系统相册选择器。
     * @param imagesOnly true 时只列图片（封面用），false 时图片视频混排（附件相册用）
     * @param max 单次可选数量，1 即单选
     */
    fun photoPicker(imagesOnly: Boolean, max: Int = MAX_MULTI): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max)
            putExtra(Intent.EXTRA_MIME_TYPES, if (imagesOnly) imageMimes else galleryMimes)
        }
    }

    /**
     * 文档选择器，相册兜底与「其他」入口共用。
     * @param mimes null 表示不限类型
     */
    fun documentPicker(mimes: Array<String>?, multiple: Boolean): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (mimes != null) putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
