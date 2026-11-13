package io.github.aixtin.blogpad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 封面取景器。
 *
 * 只有两件事交给用户：框放哪、框多大。比例默认锁在预设上（拖角等比缩放），
 * 点「自由」之后拖角才放开比例——预设是起点，不是约束。
 *
 * 框里拖动 = 整体平移框，图片本身永远不动。用户要的是"裁哪一块"，
 * 不是"把照片挪一挪"，所以拖动作用在框上比作用在图上更符合直觉。
 */
class CoverCropView(context: Context) : View(context) {

    private companion object {
        const val MODE_NONE = 0
        const val MODE_MOVE = 1
        const val MODE_CORNER = 2

        /** 角索引：0 左上 1 右上 2 右下 3 左下 */
        const val CORNER_TL = 0
        const val CORNER_TR = 1
        const val CORNER_BR = 2
        const val CORNER_BL = 3

        /** 默认框相对垂直居中向上偏移的比例，人脸通常在正中偏上 */
        const val BIAS_Y = 0.10f

        /**
         * 图片自己要往屏幕里让出的空白。左右是系统侧滑返回，最容易被抢事件，
         * 所以图片不铺满宽度，两侧各留 24dp；垂直方向这个 view 上下还各压着一条
         * 顶/底操作栏，图片根本贴不到屏幕边，不用再让。
         */
        const val SAFE_H_DP = 24f
        const val SAFE_V_DP = 0f
    }

    private val bitmapRef = arrayOfNulls<Bitmap>(1)

    /** 图片在 view 里的实际绘制区域 */
    private val imageDst = RectF()

    /** 取景框，view 坐标 */
    private val frame = RectF()

    /**
     * 取景框的活动范围，恒等于 imageDst。
     * 边距让在图片身上而不是框身上：框可以贴死图片边缘，但图片本身离屏幕边
     * 已经 24dp，所以框贴边时框角也不会压到系统侧滑返回的判定区。
     */
    private val safeRect = RectF()

    private var presetW = 16f
    private var presetH = 9f

    /** 拖角时保持的宽高比；切回锁定时取当前框的比例，等于"锁在现状上" */
    private var lockedRatio = 16f / 9f

    var locked = true
        set(value) {
            field = value
            if (value && frame.width() > 0f) lockedRatio = frame.width() / frame.height()
            invalidate()
        }

    private val dimPaint = Paint().apply { color = Color.parseColor("#A6000000") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59000000")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3000000") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(12f)
    }

    private val handleRadius = dp(8f)
    private val handleTouch = dp(30f)
    private val labelPadH = dp(7f)

    private var mode = MODE_NONE
    private var activeCorner = -1
    private var downX = 0f
    private var downY = 0f
    private val startFrame = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // ---------- 对外 ----------

    fun setBitmap(bitmap: Bitmap) {
        bitmapRef[0] = bitmap
        layoutImage()
        resetFrame()
    }

    fun bitmapOrNull(): Bitmap? = bitmapRef[0]

    fun setPreset(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        presetW = w
        presetH = h
        if (locked) lockedRatio = w / h
        resetFrame()
    }

    /** 框回到预设比例 + 默认位置 */
    fun resetFrame() {
        if (bitmapRef[0] == null || safeRect.width() <= 0f) return
        var w = safeRect.width()
        var h = w * presetH / presetW
        if (h > safeRect.height()) {
            h = safeRect.height()
            w = h * presetW / presetH
        }
        if (w <= 0f || h <= 0f) return
        val left = safeRect.left + (safeRect.width() - w) / 2f
        var top = safeRect.top + (safeRect.height() - h) / 2f - h * BIAS_Y
        top = top.coerceIn(safeRect.top, safeRect.bottom - h)
        frame.set(left, top, left + w, top + h)
        if (locked) lockedRatio = w / h
        invalidate()
    }

    /** 取景框换算回原图像素坐标，给压缩用 */
    fun frameInBitmap(): Rect? {
        val img = bitmapRef[0] ?: return null
        if (imageDst.width() <= 0f || imageDst.height() <= 0f) return null
        val sx = img.width / imageDst.width()
        val sy = img.height / imageDst.height()
        val l = ((frame.left - imageDst.left) * sx).toInt().coerceIn(0, img.width - 1)
        val t = ((frame.top - imageDst.top) * sy).toInt().coerceIn(0, img.height - 1)
        val r = ((frame.right - imageDst.left) * sx).toInt().coerceIn(l + 1, img.width)
        val b = ((frame.bottom - imageDst.top) * sy).toInt().coerceIn(t + 1, img.height)
        return Rect(l, t, r, b)
    }

    // ---------- 布局 ----------

    private fun layoutImage() {
        val img = bitmapRef[0] ?: return
        if (width == 0 || height == 0) return
        // 安全边距让给图片本身：先在可用区域里扣掉左右 24dp，再按这个区域等比缩图。
        // 用户看到的是「图没铺满、两侧有黑边」，框拖到图片边缘，框角离屏幕边仍有 24dp。
        val padX = min(dp(SAFE_H_DP), width * 0.25f)
        val padY = min(dp(SAFE_V_DP), height * 0.25f)
        val availW = (width - padX * 2f).coerceAtLeast(1f)
        val availH = (height - padY * 2f).coerceAtLeast(1f)
        val scale = min(availW / img.width, availH / img.height)
        val w = img.width * scale
        val h = img.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageDst.set(left, top, left + w, top + h)
        safeRect.set(imageDst)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutImage()
        if (frame.isEmpty) resetFrame() else clampFrame()
        // 图片已经往内让了 24dp，这里再向系统要一块手势排除区，双保险。
        // 退出这页本来就有「取消」，吞掉侧滑返回不影响使用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            post { systemGestureExclusionRects = listOf(Rect(0, 0, width, height)) }
        }
    }

    /** 最小框：只保一个「还看得清、手柄不打架」的下限，用户想裁多小就多小 */
    private fun minWidth(): Float = min(safeRect.width(), max(safeRect.width() * 0.06f, dp(48f)))
    private fun minHeight(): Float = min(safeRect.height(), max(safeRect.height() * 0.06f, dp(36f)))

    private fun clampFrame() {
        if (safeRect.width() <= 0f) return
        if (frame.width() > safeRect.width()) {
            frame.left = safeRect.left
            frame.right = safeRect.right
        }
        if (frame.height() > safeRect.height()) {
            frame.top = safeRect.top
            frame.bottom = safeRect.bottom
        }
        if (frame.left < safeRect.left) frame.offset(safeRect.left - frame.left, 0f)
        if (frame.top < safeRect.top) frame.offset(0f, safeRect.top - frame.top)
        if (frame.right > safeRect.right) frame.offset(safeRect.right - frame.right, 0f)
        if (frame.bottom > safeRect.bottom) frame.offset(0f, safeRect.bottom - frame.bottom)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val img = bitmapRef[0] ?: return
        canvas.drawBitmap(img, null, imageDst, null)

        canvas.save()
        canvas.clipOutRect(frame)
        canvas.drawRect(imageDst, dimPaint)
        canvas.restore()

        canvas.drawRect(frame, borderPaint)

        val r = handleRadius
        canvas.drawCircle(frame.left, frame.top, r, handlePaint)
        canvas.drawCircle(frame.right, frame.top, r, handlePaint)
        canvas.drawCircle(frame.right, frame.bottom, r, handlePaint)
        canvas.drawCircle(frame.left, frame.bottom, r, handlePaint)
        canvas.drawCircle(frame.left, frame.top, r, handleRing)
        canvas.drawCircle(frame.right, frame.top, r, handleRing)
        canvas.drawCircle(frame.right, frame.bottom, r, handleRing)
        canvas.drawCircle(frame.left, frame.bottom, r, handleRing)

        drawRatioLabel(canvas)
    }

    private fun drawRatioLabel(canvas: Canvas) {
        if (frame.width() <= 0f || frame.height() <= 0f) return
        val text = ratioLabel(frame.width(), frame.height())
        val tw = labelPaint.measureText(text)
        val boxW = tw + labelPadH * 2
        val boxH = labelPaint.textSize + dp(10f)
        var left = frame.centerX() - boxW / 2f
        left = left.coerceIn(imageDst.left, max(imageDst.left, imageDst.right - boxW))
        var top = frame.top - boxH - dp(8f)
        if (top < imageDst.top) top = frame.top + dp(8f)
        canvas.drawRoundRect(
            left, top, left + boxW, top + boxH,
            dp(6f), dp(6f), labelBg
        )
        val fm = labelPaint.fontMetrics
        val baseline = top + (boxH - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(text, left + labelPadH, baseline, labelPaint)
    }

    // ---------- 触摸 ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmapRef[0] == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                activeCorner = hitCorner(x, y)
                mode = when {
                    activeCorner >= 0 -> MODE_CORNER
                    frame.contains(x, y) -> MODE_MOVE
                    else -> MODE_NONE
                }
                if (mode == MODE_NONE) return false
                downX = x
                downY = y
                startFrame.set(frame)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_MOVE) moveFrame(event.x, event.y)
                else if (mode == MODE_CORNER) dragCorner(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                activeCorner = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitCorner(x: Float, y: Float): Int {
        val t = handleTouch
        return when {
            abs(x - frame.left) <= t && abs(y - frame.top) <= t -> CORNER_TL
            abs(x - frame.right) <= t && abs(y - frame.top) <= t -> CORNER_TR
            abs(x - frame.right) <= t && abs(y - frame.bottom) <= t -> CORNER_BR
            abs(x - frame.left) <= t && abs(y - frame.bottom) <= t -> CORNER_BL
            else -> -1
        }
    }

    private fun moveFrame(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        val w = startFrame.width()
        val h = startFrame.height()
        val left = (startFrame.left + dx).coerceIn(safeRect.left, max(safeRect.left, safeRect.right - w))
        val top = (startFrame.top + dy).coerceIn(safeRect.top, max(safeRect.top, safeRect.bottom - h))
        frame.set(left, top, left + w, top + h)
        invalidate()
    }

    private fun dragCorner(x: Float, y: Float) {
        // 锚点是对角：拖哪个角，对角就钉住
        val anchorX = if (activeCorner == CORNER_TR || activeCorner == CORNER_BR) startFrame.left else startFrame.right
        val anchorY = if (activeCorner == CORNER_BR || activeCorner == CORNER_BL) startFrame.top else startFrame.bottom

        val dragLeft = activeCorner == CORNER_TL || activeCorner == CORNER_BL
        val dragTop = activeCorner == CORNER_TL || activeCorner == CORNER_TR

        val px = x.coerceIn(safeRect.left, safeRect.right)
        val py = y.coerceIn(safeRect.top, safeRect.bottom)

        val maxW = if (dragLeft) anchorX - safeRect.left else safeRect.right - anchorX
        val maxH = if (dragTop) anchorY - safeRect.top else safeRect.bottom - anchorY
        if (maxW <= 0f || maxH <= 0f) return

        var w: Float
        var h: Float
        if (locked) {
            val ratio = if (lockedRatio > 0f) lockedRatio else presetW / presetH
            w = max(abs(px - anchorX), abs(py - anchorY) * ratio)
            val capW = min(maxW, maxH * ratio)
            val floorW = min(capW, max(minWidth(), minHeight() * ratio))
            w = w.coerceIn(floorW, capW)
            h = w / ratio
        } else {
            w = abs(px - anchorX).coerceIn(min(maxW, minWidth()), maxW)
            h = abs(py - anchorY).coerceIn(min(maxH, minHeight()), maxH)
            // 自由拖时也别小于一个能看清的最小框
            if (w < minWidth()) w = min(maxW, minWidth())
            if (h < minHeight()) h = min(maxH, minHeight())
        }

        val left = if (dragLeft) anchorX - w else anchorX
        val top = if (dragTop) anchorY - h else anchorY
        frame.set(left, top, left + w, top + h)
        invalidate()
    }
}
