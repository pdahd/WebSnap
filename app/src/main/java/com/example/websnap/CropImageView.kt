package com.example.websnap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 可缩放平移的图片视图
 *
 * 支持：
 * - 双指缩放（pinch zoom）
 * - 单指/双指平移（pan）
 * - 双击还原（double tap to reset）
 * - 边界限制（不会滑出可视区域）
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ═══════════════════════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════════════════════

    companion object {
        /** 最小缩放比例（相对于适应屏幕的基准） */
        private const val MIN_SCALE = 0.5f

        /** 最大缩放比例 */
        private const val MAX_SCALE = 5.0f
    }

    // ═══════════════════════════════════════════════════════════════
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    /** 要显示的 Bitmap */
    private var bitmap: Bitmap? = null

    /** 变换矩阵（用于缩放和平移） */
    private val imageMatrix = Matrix()

    /** 用于读取矩阵值的临时数组 */
    private val matrixValues = FloatArray(9)

    /** 图片在变换后的边界矩形 */
    private val imageRect = RectF()

    /** 基准缩放比例（让图片适应 View 宽度） */
    private var baseScale = 1f

    /** 当前相对缩放比例（1.0 = 基准大小） */
    private var currentRelativeScale = 1f

    /** 图片变化监听器 */
    private var onMatrixChangeListener: OnMatrixChangeListener? = null

    // ═══════════════════════════════════════════════════════════════
    // 手势检测器
    // ═══════════════════════════════════════════════════════════════

    /** 缩放手势检测器 */
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor

                // 计算新的缩放比例
                val newRelativeScale = currentRelativeScale * scaleFactor

                // 限制缩放范围
                if (newRelativeScale < MIN_SCALE || newRelativeScale > MAX_SCALE) {
                    return true
                }

                // 以焦点为中心缩放
                imageMatrix.postScale(
                    scaleFactor,
                    scaleFactor,
                    detector.focusX,
                    detector.focusY
                )

                currentRelativeScale = newRelativeScale
                constrainMatrix()
                invalidate()
                notifyMatrixChanged()

                return true
            }
        }
    )

    /** 普通手势检测器（用于平移和双击） */
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onScroll(
                e1: MotionEvent,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 平移图片
                imageMatrix.postTranslate(-distanceX, -distanceY)
                constrainMatrix()
                invalidate()
                notifyMatrixChanged()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击还原到初始状态
                resetToFitView()
                return true
            }
        }
    )

    // ═══════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置要显示的 Bitmap
     */
    fun setBitmap(bmp: Bitmap?) {
        this.bitmap = bmp
        if (bmp != null && width > 0 && height > 0) {
            resetToFitView()
        } else {
            invalidate()
        }
    }

    /**
     * 获取当前 Bitmap
     */
    fun getBitmap(): Bitmap? = bitmap

    /**
     * 重置到适应 View 的初始状态
     */
    fun resetToFitView() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return

        imageMatrix.reset()

        // 计算基准缩放比例：让图片宽度适应 View 宽度
        baseScale = width.toFloat() / bmp.width.toFloat()

        // 如果缩放后高度超出 View，则改为适应高度
        val scaledHeight = bmp.height * baseScale
        if (scaledHeight > height) {
            baseScale = height.toFloat() / bmp.height.toFloat()
        }

        // 应用缩放
        imageMatrix.setScale(baseScale, baseScale)

        // 居中显示
        val scaledWidth = bmp.width * baseScale
        val finalScaledHeight = bmp.height * baseScale
        val dx = (width - scaledWidth) / 2f
        val dy = (height - finalScaledHeight) / 2f
        imageMatrix.postTranslate(dx, dy)

        currentRelativeScale = 1f
        invalidate()
        notifyMatrixChanged()
    }

    /**
     * 获取图片在 View 坐标系中的边界矩形
     */
    fun getImageBounds(): RectF {
        val bmp = bitmap ?: return RectF()
        imageRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(imageRect)
        return RectF(imageRect)
    }

    /**
     * 将 View 坐标转换为 Bitmap 像素坐标
     */
    fun viewToBitmapCoord(viewX: Float, viewY: Float): FloatArray {
        val inverse = Matrix()
        imageMatrix.invert(inverse)

        val points = floatArrayOf(viewX, viewY)
        inverse.mapPoints(points)

        return points
    }

    /**
     * 将 Bitmap 像素坐标转换为 View 坐标
     */
    fun bitmapToViewCoord(bmpX: Float, bmpY: Float): FloatArray {
        val points = floatArrayOf(bmpX, bmpY)
        imageMatrix.mapPoints(points)
        return points
    }

    /**
     * 获取当前缩放比例（相对于原图）
     */
    fun getCurrentScale(): Float {
        imageMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /**
     * 设置矩阵变化监听器
     */
    fun setOnMatrixChangeListener(listener: OnMatrixChangeListener?) {
        this.onMatrixChangeListener = listener
    }

    // ═══════════════════════════════════════════════════════════════
    // View 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) {
            resetToFitView()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = bitmap ?: return

        canvas.save()
        canvas.concat(imageMatrix)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 先让缩放检测器处理
        scaleGestureDetector.onTouchEvent(event)

        // 如果不在缩放过程中，让普通手势检测器处理
        if (!scaleGestureDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 约束矩阵，防止图片滑出可视区域
     */
    private fun constrainMatrix() {
        val bmp = bitmap ?: return

        // 获取变换后的图片边界
        imageRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(imageRect)

        var dx = 0f
        var dy = 0f

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // 水平方向约束
        when {
            imageRect.width() <= viewWidth -> {
                // 图片宽度小于 View，居中
                dx = (viewWidth - imageRect.width()) / 2f - imageRect.left
            }
            imageRect.left > 0 -> {
                // 左边有空隙，往左推
                dx = -imageRect.left
            }
            imageRect.right < viewWidth -> {
                // 右边有空隙，往右推
                dx = viewWidth - imageRect.right
            }
        }

        // 垂直方向约束
        when {
            imageRect.height() <= viewHeight -> {
                // 图片高度小于 View，居中
                dy = (viewHeight - imageRect.height()) / 2f - imageRect.top
            }
            imageRect.top > 0 -> {
                // 上边有空隙，往上推
                dy = -imageRect.top
            }
            imageRect.bottom < viewHeight -> {
                // 下边有空隙，往下推
                dy = viewHeight - imageRect.bottom
            }
        }

        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
        }
    }

    /**
     * 通知监听器矩阵已变化
     */
    private fun notifyMatrixChanged() {
        onMatrixChangeListener?.onMatrixChanged(this)
    }

    // ═══════════════════════════════════════════════════════════════
    // 监听器接口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 矩阵变化监听器
     */
    interface OnMatrixChangeListener {
        fun onMatrixChanged(view: CropImageView)
    }
}
