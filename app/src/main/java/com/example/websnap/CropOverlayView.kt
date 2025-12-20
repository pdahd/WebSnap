package com.example.websnap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 裁剪框覆盖层
 *
 * 功能：
 * - 绘制可拖动的裁剪框
 * - 四条边独立拖动
 * - 四个角同时拖动两条边
 * - 裁剪框外显示半透明遮罩
 * - 九宫格辅助线
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ═══════════════════════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════════════════════

    companion object {
        /** 边缘触摸热区宽度 (dp) */
        private const val TOUCH_THRESHOLD_DP = 40f

        /** 角落触摸热区 (dp) */
        private const val CORNER_THRESHOLD_DP = 50f

        /** 最小裁剪尺寸 (px) */
        private const val MIN_CROP_SIZE = 50f

        /** 边框线宽度 (dp) */
        private const val BORDER_WIDTH_DP = 2f

        /** 角手柄长度 (dp) */
        private const val CORNER_LENGTH_DP = 24f

        /** 角手柄宽度 (dp) */
        private const val CORNER_WIDTH_DP = 4f
    }

    // ═══════════════════════════════════════════════════════════════
    // 拖动模式
    // ═══════════════════════════════════════════════════════════════

    private enum class DragMode {
        NONE,
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    // ═══════════════════════════════════════════════════════════════
    // 尺寸（像素）
    // ═══════════════════════════════════════════════════════════════

    private val touchThreshold: Float
    private val cornerThreshold: Float
    private val borderWidth: Float
    private val cornerLength: Float
    private val cornerWidth: Float

    // ═══════════════════════════════════════════════════════════════
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    /** 裁剪框矩形（View 坐标系） */
    private val cropRect = RectF()

    /** 图片边界（View 坐标系） */
    private var imageBounds = RectF()

    /** 当前拖动模式 */
    private var currentDragMode = DragMode.NONE

    /** 上次触摸位置 */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** 关联的 CropImageView */
    private var cropImageView: CropImageView? = null

    /** 裁剪框变化监听器 */
    private var onCropRectChangeListener: OnCropRectChangeListener? = null

    // ═══════════════════════════════════════════════════════════════
    // 画笔
    // ═══════════════════════════════════════════════════════════════

    /** 遮罩画笔 */
    private val overlayPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    /** 边框画笔 */
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /** 角手柄画笔 */
    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.SQUARE
    }

    /** 网格线画笔 */
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // ═══════════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════════

    init {
        val density = context.resources.displayMetrics.density
        touchThreshold = TOUCH_THRESHOLD_DP * density
        cornerThreshold = CORNER_THRESHOLD_DP * density
        borderWidth = BORDER_WIDTH_DP * density
        cornerLength = CORNER_LENGTH_DP * density
        cornerWidth = CORNER_WIDTH_DP * density

        borderPaint.strokeWidth = borderWidth
        cornerPaint.strokeWidth = cornerWidth
        gridPaint.strokeWidth = 1f * density
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置关联的 CropImageView
     */
    fun setCropImageView(imageView: CropImageView) {
        this.cropImageView = imageView
        imageView.setOnMatrixChangeListener(object : CropImageView.OnMatrixChangeListener {
            override fun onMatrixChanged(view: CropImageView) {
                updateImageBounds()
                invalidate()
            }
        })
    }

    /**
     * 更新图片边界并初始化裁剪框
     */
    fun updateImageBounds() {
        val imageView = cropImageView ?: return
        imageBounds = imageView.getImageBounds()

        // 如果裁剪框未初始化或超出图片边界，重置为全图
        if (cropRect.isEmpty || !imageBounds.contains(cropRect)) {
            resetCropRect()
        }
    }

    /**
     * 重置裁剪框为全图
     */
    fun resetCropRect() {
        cropRect.set(imageBounds)
        invalidate()
        notifyCropRectChanged()
    }

    /**
     * 获取裁剪框（Bitmap 像素坐标）
     */
    fun getCropRectInBitmap(): RectF {
        val imageView = cropImageView ?: return RectF()

        val topLeft = imageView.viewToBitmapCoord(cropRect.left, cropRect.top)
        val bottomRight = imageView.viewToBitmapCoord(cropRect.right, cropRect.bottom)

        return RectF(
            topLeft[0],
            topLeft[1],
            bottomRight[0],
            bottomRight[1]
        )
    }

    /**
     * 设置裁剪框变化监听器
     */
    fun setOnCropRectChangeListener(listener: OnCropRectChangeListener?) {
        this.onCropRectChangeListener = listener
    }

    // ═══════════════════════════════════════════════════════════════
    // 绘制
    // ═══════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cropRect.isEmpty) return

        // 1. 绘制半透明遮罩
        drawOverlay(canvas)

        // 2. 绘制网格线
        drawGrid(canvas)

        // 3. 绘制边框
        canvas.drawRect(cropRect, borderPaint)

        // 4. 绘制四个角手柄
        drawCorners(canvas)
    }

    private fun drawOverlay(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 上方遮罩
        canvas.drawRect(0f, 0f, w, cropRect.top, overlayPaint)
        // 下方遮罩
        canvas.drawRect(0f, cropRect.bottom, w, h, overlayPaint)
        // 左侧遮罩
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
        // 右侧遮罩
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, overlayPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val thirdWidth = cropRect.width() / 3f
        val thirdHeight = cropRect.height() / 3f

        // 垂直线
        canvas.drawLine(
            cropRect.left + thirdWidth, cropRect.top,
            cropRect.left + thirdWidth, cropRect.bottom,
            gridPaint
        )
        canvas.drawLine(
            cropRect.left + thirdWidth * 2, cropRect.top,
            cropRect.left + thirdWidth * 2, cropRect.bottom,
            gridPaint
        )

        // 水平线
        canvas.drawLine(
            cropRect.left, cropRect.top + thirdHeight,
            cropRect.right, cropRect.top + thirdHeight,
            gridPaint
        )
        canvas.drawLine(
            cropRect.left, cropRect.top + thirdHeight * 2,
            cropRect.right, cropRect.top + thirdHeight * 2,
            gridPaint
        )
    }

    private fun drawCorners(canvas: Canvas) {
        // 左上角
        canvas.drawLine(
            cropRect.left, cropRect.top,
            cropRect.left + cornerLength, cropRect.top,
            cornerPaint
        )
        canvas.drawLine(
            cropRect.left, cropRect.top,
            cropRect.left, cropRect.top + cornerLength,
            cornerPaint
        )

        // 右上角
        canvas.drawLine(
            cropRect.right, cropRect.top,
            cropRect.right - cornerLength, cropRect.top,
            cornerPaint
        )
        canvas.drawLine(
            cropRect.right, cropRect.top,
            cropRect.right, cropRect.top + cornerLength,
            cornerPaint
        )

        // 左下角
        canvas.drawLine(
            cropRect.left, cropRect.bottom,
            cropRect.left + cornerLength, cropRect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            cropRect.left, cropRect.bottom,
            cropRect.left, cropRect.bottom - cornerLength,
            cornerPaint
        )

        // 右下角
        canvas.drawLine(
            cropRect.right, cropRect.bottom,
            cropRect.right - cornerLength, cropRect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            cropRect.right, cropRect.bottom,
            cropRect.right, cropRect.bottom - cornerLength,
            cornerPaint
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 触摸事件
    // ═══════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentDragMode = detectDragMode(x, y)
                lastTouchX = x
                lastTouchY = y
                return currentDragMode != DragMode.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (currentDragMode != DragMode.NONE) {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    updateCropRect(dx, dy)

                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    notifyCropRectChanged()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentDragMode = DragMode.NONE
            }
        }

        return super.onTouchEvent(event)
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        // 检测四个角（优先级最高）
        val nearLeft = abs(x - cropRect.left) < cornerThreshold
        val nearRight = abs(x - cropRect.right) < cornerThreshold
        val nearTop = abs(y - cropRect.top) < cornerThreshold
        val nearBottom = abs(y - cropRect.bottom) < cornerThreshold

        if (nearLeft && nearTop) return DragMode.TOP_LEFT
        if (nearRight && nearTop) return DragMode.TOP_RIGHT
        if (nearLeft && nearBottom) return DragMode.BOTTOM_LEFT
        if (nearRight && nearBottom) return DragMode.BOTTOM_RIGHT

        // 检测四条边
        val inVerticalRange = y > cropRect.top - touchThreshold &&
                y < cropRect.bottom + touchThreshold
        val inHorizontalRange = x > cropRect.left - touchThreshold &&
                x < cropRect.right + touchThreshold

        if (abs(x - cropRect.left) < touchThreshold && inVerticalRange) {
            return DragMode.LEFT
        }
        if (abs(x - cropRect.right) < touchThreshold && inVerticalRange) {
            return DragMode.RIGHT
        }
        if (abs(y - cropRect.top) < touchThreshold && inHorizontalRange) {
            return DragMode.TOP
        }
        if (abs(y - cropRect.bottom) < touchThreshold && inHorizontalRange) {
            return DragMode.BOTTOM
        }

        return DragMode.NONE
    }

    private fun updateCropRect(dx: Float, dy: Float) {
        when (currentDragMode) {
            DragMode.LEFT -> {
                cropRect.left = constrainLeft(cropRect.left + dx)
            }
            DragMode.RIGHT -> {
                cropRect.right = constrainRight(cropRect.right + dx)
            }
            DragMode.TOP -> {
                cropRect.top = constrainTop(cropRect.top + dy)
            }
            DragMode.BOTTOM -> {
                cropRect.bottom = constrainBottom(cropRect.bottom + dy)
            }
            DragMode.TOP_LEFT -> {
                cropRect.left = constrainLeft(cropRect.left + dx)
                cropRect.top = constrainTop(cropRect.top + dy)
            }
            DragMode.TOP_RIGHT -> {
                cropRect.right = constrainRight(cropRect.right + dx)
                cropRect.top = constrainTop(cropRect.top + dy)
            }
            DragMode.BOTTOM_LEFT -> {
                cropRect.left = constrainLeft(cropRect.left + dx)
                cropRect.bottom = constrainBottom(cropRect.bottom + dy)
            }
            DragMode.BOTTOM_RIGHT -> {
                cropRect.right = constrainRight(cropRect.right + dx)
                cropRect.bottom = constrainBottom(cropRect.bottom + dy)
            }
            DragMode.NONE -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界约束
    // ═══════════════════════════════════════════════════════════════

    private fun constrainLeft(value: Float): Float {
        val minLeft = imageBounds.left
        val maxLeft = cropRect.right - MIN_CROP_SIZE
        return value.coerceIn(minLeft, maxLeft)
    }

    private fun constrainRight(value: Float): Float {
        val minRight = cropRect.left + MIN_CROP_SIZE
        val maxRight = imageBounds.right
        return value.coerceIn(minRight, maxRight)
    }

    private fun constrainTop(value: Float): Float {
        val minTop = imageBounds.top
        val maxTop = cropRect.bottom - MIN_CROP_SIZE
        return value.coerceIn(minTop, maxTop)
    }

    private fun constrainBottom(value: Float): Float {
        val minBottom = cropRect.top + MIN_CROP_SIZE
        val maxBottom = imageBounds.bottom
        return value.coerceIn(minBottom, maxBottom)
    }

    // ═══════════════════════════════════════════════════════════════
    // 监听器
    // ═══════════════════════════════════════════════════════════════

    private fun notifyCropRectChanged() {
        onCropRectChangeListener?.onCropRectChanged(cropRect)
    }

    /**
     * 裁剪框变化监听器
     */
    interface OnCropRectChangeListener {
        fun onCropRectChanged(rect: RectF)
    }
}
