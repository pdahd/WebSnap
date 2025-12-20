package com.example.websnap

import android.graphics.Bitmap

/**
 * Bitmap 持有者单例
 *
 * 用于在 Activity 间传递大图片，避免 Intent 的 Binder 限制。
 * 使用后务必调用 clear() 释放内存。
 */
object CropBitmapHolder {

    /** 待裁剪的原始 Bitmap */
    var bitmap: Bitmap? = null
        private set

    /** 截图类型标记 */
    var isFullPage: Boolean = false
        private set

    /**
     * 设置待裁剪的 Bitmap
     */
    fun set(bitmap: Bitmap, isFullPage: Boolean) {
        // 先释放旧的
        clear()
        this.bitmap = bitmap
        this.isFullPage = isFullPage
    }

    /**
     * 清空并释放 Bitmap
     */
    fun clear() {
        bitmap?.recycle()
        bitmap = null
        isFullPage = false
    }

    /**
     * 是否有可用的 Bitmap
     */
    fun hasBitmap(): Boolean = bitmap != null && !bitmap!!.isRecycled
}
