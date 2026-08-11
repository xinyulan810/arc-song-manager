package com.arcaea.songpack.cover

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * 封面编辑器预览视图。
 *
 * 所见即所得: 每帧渲染"最终效果"——白色蒙版打底 + 源图按矩阵变换,
 * 用内缩蒙版裁切出艺术区, 轮廓内侧留白边, 轮廓外透明(透出深色背景)。
 *
 * 手势: 单指拖动平移, 双指缩放, 双击复位。平移/缩放后自动钳制,
 * 保证源图始终盖满内缩艺术区、缩放范围 [fit, fit*4]。
 *
 * 渲染策略:
 * - 整视图强制软件渲染: 蒙版裁切是 DST_IN 合成, 部分 GPU 的硬件渲染路径
 *   对"同帧修改位图再上屏"会闪白/丢内容, 软件渲染完全可控、结果确定。
 * - 最终预览只合成一次进 preview 位图, onDraw 仅做一次整图 blit;
 *   手势/矩阵变化时才重合成, 避免每帧多次 drawBitmap。
 * - 重绘用 postInvalidateOnAnimation 对齐帧, 不做人为限帧。
 *
 * 历史 bug(已修): clamp() 底部边界检查误用 p[3](右上角 y, 等于顶边),
 * 导致向上拖动时图像在"顶边对齐"与"整图掉出卡片"之间反复横跳 = 闪白屏;
 * 向下拖动方向与钳制一致所以不闪。修复为用 p[5](右下角 y = 底边)。
 */
class CoverEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var source: Bitmap? = null
    private var mask: Bitmap? = null

    /** 源图 -> 视图坐标的变换矩阵 */
    private val matrix = Matrix()
    private var fitScale = 1f

    /** 视图尺寸的白色剪影 / 内缩蒙版(固定, 手势只影响源图矩阵) */
    private var maskWhite: Bitmap? = null
    private var maskInset: Bitmap? = null
    /** 复用的离屏艺术层(避免每帧 saveLayer 导致的闪白/开销) */
    private var artCache: Bitmap? = null
    /** 最终预览缓存(白色剪影 + 艺术层一次合成), 仅在 dirty 时重合成 */
    private var preview: Bitmap? = null
    private var previewDirty = true

    /** 蒙版高/宽比, onMeasure 用 */
    private var maskRatio = 717f / 403f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstInPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
                clamp()
                requestRedraw()
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        }
    )

    init {
        // 蒙版 DST_IN 裁切在部分 GPU 的硬件渲染下会闪白, 统一走软件渲染
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun setMask(maskBitmap: Bitmap) {
        mask = maskBitmap
        rebuildLayers()
        if (source != null) reset()
        markDirty()
    }

    fun setSource(bitmap: Bitmap) {
        source = bitmap
        reset()
        markDirty()
    }

    /** 当前源图引用(供生成器读取) */
    fun sourceBitmap(): Bitmap? = source

    /** 当前变换矩阵(源图 -> 视图), 生成器用它映射到输出尺寸 */
    fun currentMatrix(): Matrix = Matrix(matrix)

    /** 复位: 源图 cover 视图并居中 */
    fun reset() {
        resetMatrix()
        markDirty()
    }

    /** 标记预览过期并请求重绘 */
    private fun markDirty() {
        previewDirty = true
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        var w = availW
        var h = (w * maskRatio).toInt()
        if (h > availH) {
            h = availH
            w = (h / maskRatio).toInt()
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        preview = null // 尺寸变化, 缓存位图废弃重建
        rebuildLayers()
        if (source != null) reset()
        markDirty()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val white = maskWhite ?: return
        val inner = maskInset ?: return
        if (width <= 0 || height <= 0) return

        // 最终预览只合成一次, onDraw 只做一次整图 blit
        val pv = preview
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { preview = it }
        if (previewDirty) {
            renderPreview(pv, src, white, inner)
            previewDirty = false
        }
        canvas.drawBitmap(pv, 0f, 0f, paint)
    }

    /**
     * 软件合成最终预览: 白色剪影 + 艺术层(源图按矩阵 + 内缩蒙版 DST_IN 裁切)。
     * 与离线原型语义一致, 但全部发生在离屏位图, 不与 GPU 逐帧交互。
     */
    private fun renderPreview(pv: Bitmap, src: Bitmap, white: Bitmap, inner: Bitmap) {
        val art = artCache
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { artCache = it }
        val ac = Canvas(art)
        ac.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        ac.drawBitmap(src, matrix, paint)
        ac.drawBitmap(inner, 0f, 0f, dstInPaint)

        val pc = Canvas(pv)
        pc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        pc.drawBitmap(white, 0f, 0f, null)
        pc.drawBitmap(art, 0f, 0f, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && (dx != 0f || dy != 0f)) {
                    matrix.postTranslate(dx, dy)
                    clamp()
                    requestRedraw()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 抬手补一帧, 确保最后状态上屏
                requestRedraw()
            }
        }
        return true
    }

    /** 矩阵变化后请求重绘(与帧对齐, 不做人为限帧) */
    private fun requestRedraw() {
        previewDirty = true
        postInvalidateOnAnimation()
    }

    private fun resetMatrix() {
        if (source == null) return
        if (width <= 0 || height <= 0) return
        fitMatrixToView()
        clamp()
    }

    /** 缩放钳制 [fit, fit*4] + 平移钳制(源图必须盖满内缩艺术区) */
    private fun clamp() {
        val src = source ?: return
        if (width <= 0 || height <= 0) return

        val unit = FloatArray(4)
        matrix.mapPoints(unit, floatArrayOf(0f, 0f, 1f, 0f))
        val cur = hypot(unit[2] - unit[0], unit[3] - unit[1])
        // 矩阵退化(异常路径): 直接复位, 避免画出的内容空白
        if (!cur.isFinite() || cur <= 0f) {
            fitMatrixToView()
            return
        }
        val target = cur.coerceIn(fitScale, fitScale * 4f)
        if (target != cur) {
            matrix.postScale(target / cur, target / cur, width / 2f, height / 2f)
        }

        val inset = borderInset()
        val minX = inset
        val minY = inset
        val maxX = width - inset
        val maxY = height - inset
        val p = FloatArray(8)
        matrix.mapPoints(
            p,
            floatArrayOf(
                0f, 0f,
                src.width.toFloat(), 0f,
                src.width.toFloat(), src.height.toFloat(),
                0f, src.height.toFloat()
            )
        )
        var dx = 0f
        var dy = 0f
        if (p[0] > minX) dx = minX - p[0]
        else if (p[2] < maxX) dx = maxX - p[2]
        if (p[1] > minY) dy = minY - p[1]
        else if (p[5] < maxY) dy = maxY - p[5]
        matrix.postTranslate(dx, dy)
    }

    /** 源图 cover 视图并居中(重置矩阵基础状态) */
    private fun fitMatrixToView() {
        val src = source ?: return
        if (width <= 0 || height <= 0) return
        fitScale = max(width / src.width.toFloat(), height / src.height.toFloat())
        matrix.reset()
        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate((width - src.width * fitScale) / 2f, (height - src.height * fitScale) / 2f)
    }

    /** 白边在视图坐标下的内缩量 */
    private fun borderInset(): Float =
        CoverGenerator.BORDER_PX * (width.toFloat() / (mask?.width ?: 403))

    private fun rebuildLayers() {
        val m = mask ?: return
        if (width <= 0 || height <= 0) return
        maskRatio = m.height.toFloat() / m.width
        artCache = null // 尺寸变化, 离屏层下次重建
        preview = null
        previewDirty = true
        val vw = width
        val vh = height

        // 白色剪影
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val white = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        Canvas(white).drawBitmap(m, null, Rect(0, 0, vw, vh), whitePaint)
        maskWhite = white

        // 内缩蒙版(视图尺寸)
        val inset = borderInset()
        val kx = 1f - 2f * inset / vw
        val ky = 1f - 2f * inset / vh
        val inner = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        val c = Canvas(inner)
        c.scale(kx, ky, vw / 2f, vh / 2f)
        c.drawBitmap(m, null, Rect(0, 0, vw, vh), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        maskInset = inner
    }
}
