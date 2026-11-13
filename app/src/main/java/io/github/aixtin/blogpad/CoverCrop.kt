package io.github.aixtin.blogpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 封面处理分两步：解码给裁剪页显示，用户定好构图后再按框压缩。
 *
 * 这里不再替用户做「居中裁 16:9」的决定——各站点主题封面比例不一样，
 * 一刀切的构图发上去会被主题二次裁。比例和位置交给裁剪页。
 * 唯一还由这里管的是输出尺寸：宽最多 1280（够覆盖 og:image 的 1200×630），
 * 框里像素本来就不够 1280 时如实输出，不放大凑数。
 */
object CoverCrop {

    const val ASPECT_W = 16
    const val ASPECT_H = 9

    /** 输出宽度上限 */
    private const val TARGET_WIDTH = 1280

    /** 裁剪页展示用的解码长边上限。比输出宽留余量，框选得小也还有像素可用 */
    private const val DECODE_MAX = 3072

    private const val QUALITY = 85

    /** 解码并按 EXIF 摆正，返回可直接显示的位图；解不出来返回 null 由调用方兜底 */
    fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：inJustDecodeBounds 模式下 decodeStream 本来就返回 null，
        // 不能拿它的返回值做判空，否则这里永远提前返回，整张图都解不出来
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return@runCatching null

        var sample = 1
        while (rawW / sample > DECODE_MAX || rawH / sample > DECODE_MAX) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        decoded ?: return@runCatching null

        val fixed = applyOrientation(decoded, readOrientation(context, uri))
        fixed
    }.getOrNull()

    /** 把取景框（原图像素坐标）裁出来压成 JPEG */
    fun compress(source: Bitmap, rect: Rect): ByteArray? = runCatching {
        if (source.isRecycled) return@runCatching null
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 2 || cropH < 2) return@runCatching null

        val cut = Bitmap.createBitmap(source, left, top, cropW, cropH)
        val outW = minOf(cropW, TARGET_WIDTH)
        val outH = (outW.toLong() * cropH / cropW).toInt().coerceAtLeast(1)
        val scaled = if (outW == cut.width) cut else Bitmap.createScaledBitmap(cut, outW, outH, true)

        // 截图类图片可能带透明，JPEG 不支持，先铺白底压平
        val flat = if (scaled.hasAlpha()) {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bg ->
                Canvas(bg).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(scaled, 0f, 0f, null)
                }
            }
        } else {
            scaled
        }

        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)

        if (flat !== scaled && flat !== cut) flat.recycle()
        if (scaled !== cut) scaled.recycle()
        if (cut !== source) cut.recycle()

        out.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 暂存区文件名按实际输出尺寸写，用户一眼能看出这张多大 */
    fun name(cropW: Int, cropH: Int): String {
        if (cropW <= 0 || cropH <= 0) return "封面.jpg"
        val outW = minOf(cropW, TARGET_WIDTH)
        val outH = (outW.toLong() * cropH / cropW).toInt().coerceAtLeast(1)
        return "封面-${outW}x${outH}.jpg"
    }

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 手机拍的竖图在文件里常是横躺 + EXIF 标记旋转，不按标记摆正就会裁错方向 */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
