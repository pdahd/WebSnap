package com.example.websnap

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * 自定义 WebView，暴露 computeVerticalScrollRange() 方法
 * 用于在 PC 模式下获取精确的内容高度进行截图
 */
class CaptureWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /**
     * 获取内容的垂直滚动范围（精确的像素高度）
     * 这是对 protected 方法 computeVerticalScrollRange() 的公开包装
     */
    fun getContentHeightPx(): Int {
        return computeVerticalScrollRange()
    }
}
