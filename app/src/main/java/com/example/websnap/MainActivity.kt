package com.example.websnap

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.websnap.databinding.ActivityMainBinding
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 最大截图高度限制（像素），防止 OOM */
    private val maxCaptureHeight = 20000

    /** 页面是否已加载完成 */
    private var isPageLoaded = false

    /** 原始移动端 UserAgent（在 setupWebView 时获取） */
    private var mobileUserAgent: String = ""

    /** 桌面端 UserAgent */
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    /** 允许 WebView 直接加载的 URL Scheme 白名单 */
    private val webViewSchemes = setOf("http", "https", "about", "data", "javascript")

    /** 允许通过系统 Intent 打开的 Scheme */
    private val systemSchemes = setOf("tel", "mailto", "sms")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ═══════════════════════════════════════════════════════════════
        // 【关键】必须在任何 WebView 实例化之前调用！
        // 这会让 WebView 保留整个文档的绘制数据，而不仅仅是可见区域。
        // 否则 draw(canvas) 只会绘制当前可见部分，其余区域为空白。
        // ═══════════════════════════════════════════════════════════════
        WebView.enableSlowWholeDocumentDraw()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupListeners()
    }

    /**
     * 配置 WebView 设置
     */
    private fun setupWebView() {
        binding.webView.apply {
            // 保存原始 UserAgent
            mobileUserAgent = settings.userAgentString

            // WebView 设置
            settings.apply {
                // 启用 JavaScript
                javaScriptEnabled = true
                // 启用 DOM Storage（localStorage、sessionStorage）
                domStorageEnabled = true
                // 允许数据库存储
                databaseEnabled = true
                // 使用宽视口
                useWideViewPort = true
                // 页面自适应屏幕
                loadWithOverviewMode = true
                // 启用缩放
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false // 隐藏缩放按钮
                // 允许混合内容（HTTP 和 HTTPS）
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // 设置缓存模式
                cacheMode = WebSettings.LOAD_DEFAULT
                // 允许文件访问
                allowFileAccess = true
                // 清理 UserAgent 中的 wv 标识（减少被识别为 WebView 的概率）
                userAgentString = userAgentString.replace("; wv", "")
            }

            // 更新保存的 mobileUserAgent（已清理）
            mobileUserAgent = settings.userAgentString

            // 页面加载监听
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    isPageLoaded = false
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = 0
                    binding.buttonCapture.isEnabled = false

                    // 同步地址栏显示
                    url?.let {
                        binding.editTextUrl.setText(it)
                        binding.editTextUrl.setSelection(it.length)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    binding.progressBar.visibility = View.GONE
                    binding.buttonCapture.isEnabled = true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    // 只处理主框架的错误
                    if (request?.isForMainFrame == true) {
                        val errorMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            error?.description?.toString() ?: "Unknown error"
                        } else {
                            "Load failed"
                        }
                        showToast(getString(R.string.error_page_message, errorMsg))
                    }
                }

                /**
                 * 拦截 URL 跳转，实现白名单策略
                 * - http/https: WebView 加载
                 * - tel/mailto/sms: 系统 Intent 处理
                 * - 其他协议: 静默拦截，防止 ERR_UNKNOWN_URL_SCHEME
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return false
                    val scheme = url.scheme?.lowercase() ?: return false

                    return when {
                        // ✅ 网页协议：WebView 直接加载
                        scheme in webViewSchemes -> false

                        // ✅ 系统协议：通过 Intent 调用系统功能
                        scheme in systemSchemes -> {
                            handleSystemScheme(url)
                            true
                        }

                        // ⛔ 其他协议（如 baiduboxapp://、weixin://）：静默拦截
                        else -> {
                            // 可选：显示拦截提示
                            // showToast(getString(R.string.toast_scheme_blocked))
                            true
                        }
                    }
                }
            }

            // 进度监听
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    binding.progressBar.progress = newProgress
                    if (newProgress >= 100) {
                        binding.progressBar.visibility = View.GONE
                    }
                }

                // 处理网页标题变化（可选）
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    // 可以在这里更新 ActionBar 标题等
                }
            }
        }
    }

    /**
     * 处理系统级 URL Scheme（tel:, mailto:, sms:）
     */
    private fun handleSystemScheme(url: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // 如果没有应用能处理此 Intent，静默失败
            e.printStackTrace()
        }
    }

    /**
     * 设置各种监听器
     */
    private fun setupListeners() {
        // GO 按钮点击
        binding.buttonGo.setOnClickListener {
            loadUrl()
        }

        // 输入框键盘事件（回车键触发加载）
        binding.editTextUrl.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER
                    && event.action == KeyEvent.ACTION_DOWN
            val isGoAction = actionId == EditorInfo.IME_ACTION_GO

            if (isEnterKey || isGoAction) {
                loadUrl()
                true
            } else {
                false
            }
        }

        // PC 模式复选框切换
        binding.checkBoxPcMode.setOnCheckedChangeListener { _, isChecked ->
            togglePcMode(isChecked)
        }

        // 截图按钮点击
        binding.buttonCapture.setOnClickListener {
            performCapture()
        }
    }

    /**
     * 切换 PC 桌面模式
     */
    private fun togglePcMode(enableDesktopMode: Boolean) {
        val webSettings = binding.webView.settings

        if (enableDesktopMode) {
            // 切换到桌面模式
            webSettings.userAgentString = desktopUserAgent
            showToast(getString(R.string.toast_pc_mode_on))
        } else {
            // 切换回移动模式
            webSettings.userAgentString = mobileUserAgent
            showToast(getString(R.string.toast_pc_mode_off))
        }

        // 如果已经加载了页面，刷新以应用新 UA
        val currentUrl = binding.webView.url
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
            binding.webView.reload()
        }
    }

    /**
     * 加载用户输入的 URL
     */
    private fun loadUrl() {
        val inputUrl = binding.editTextUrl.text.toString().trim()

        if (inputUrl.isEmpty()) {
            showToast(getString(R.string.toast_url_empty))
            return
        }

        // 自动补全 URL 协议
        val url = when {
            inputUrl.startsWith("http://") || inputUrl.startsWith("https://") -> inputUrl
            inputUrl.startsWith("www.") -> "https://$inputUrl"
            else -> "https://$inputUrl"
        }

        // 隐藏软键盘
        hideKeyboard()

        // 加载网页
        binding.webView.loadUrl(url)
    }

    /**
     * 执行截图操作
     */
    private fun performCapture() {
        // 检查页面是否加载完成
        if (!isPageLoaded) {
            showToast(getString(R.string.toast_page_not_loaded))
            return
        }

        // 禁用按钮，防止重复点击
        binding.buttonCapture.isEnabled = false
        binding.buttonCapture.text = getString(R.string.button_capturing)

        // 使用 post 确保 UI 更新后再执行截图
        binding.webView.post {
            try {
                val bitmap = captureWebViewToBitmap()

                if (bitmap != null) {
                    val saved = saveBitmapToGallery(bitmap)
                    bitmap.recycle() // 及时回收内存

                    if (saved) {
                        showToast(getString(R.string.toast_capture_success))
                    } else {
                        showToast(getString(R.string.toast_capture_failed))
                    }
                } else {
                    showToast(getString(R.string.toast_capture_failed))
                }

            } catch (e: OutOfMemoryError) {
                // OOM 捕获
                showToast(getString(R.string.toast_memory_insufficient))
                System.gc() // 请求垃圾回收

            } catch (e: Exception) {
                // 其他异常
                showToast(getString(R.string.toast_capture_failed))
                e.printStackTrace()

            } finally {
                // 恢复按钮状态
                binding.buttonCapture.isEnabled = true
                binding.buttonCapture.text = getString(R.string.button_capture)
            }
        }
    }

    /**
     * 将 WebView 内容绘制到 Bitmap
     * 包含：高度限制、内存检查、硬件加速处理
     */
    private fun captureWebViewToBitmap(): Bitmap? {
        val webView = binding.webView

        // ═══════════════════════════════════════════════════════════════
        // 步骤1：计算实际内容尺寸
        // ═══════════════════════════════════════════════════════════════

        // contentHeight 返回的是 CSS 像素，需要乘以缩放比例得到实际像素
        @Suppress("DEPRECATION")
        val scale = webView.scale
        val contentWidth = webView.width
        var contentHeight = (webView.contentHeight * scale).toInt()

        // 基本校验
        if (contentWidth <= 0 || contentHeight <= 0) {
            return null
        }

        // ═══════════════════════════════════════════════════════════════
        // 步骤2：高度限制检查（防止超长页面导致 OOM）
        // ═══════════════════════════════════════════════════════════════

        var wasTruncated = false
        if (contentHeight > maxCaptureHeight) {
            contentHeight = maxCaptureHeight
            wasTruncated = true
        }

        // ═══════════════════════════════════════════════════════════════
        // 步骤3：内存可用性检查
        // ═══════════════════════════════════════════════════════════════

        // ARGB_8888 格式：每像素 4 字节
        val requiredMemory = contentWidth.toLong() * contentHeight.toLong() * 4L
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()

        // 如果需要的内存超过可用内存的 80%，拒绝操作
        if (requiredMemory > freeMemory * 0.8) {
            showToast(getString(R.string.toast_memory_insufficient))
            return null
        }

        // ═══════════════════════════════════════════════════════════════
        // 步骤4：临时切换到软件渲染（解决硬件加速白屏问题）
        // ═══════════════════════════════════════════════════════════════

        val originalLayerType = webView.layerType
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val bitmap: Bitmap?
        try {
            // ═══════════════════════════════════════════════════════════
            // 步骤5：创建 Bitmap 并绘制
            // ═══════════════════════════════════════════════════════════

            bitmap = Bitmap.createBitmap(
                contentWidth,
                contentHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)

            // 将 WebView 完整内容绘制到 Canvas
            webView.draw(canvas)

        } finally {
            // ═══════════════════════════════════════════════════════════
            // 步骤6：恢复原始渲染层类型
            // ═══════════════════════════════════════════════════════════
            webView.setLayerType(originalLayerType, null)
        }

        // 如果页面被截断，通知用户
        if (wasTruncated) {
            showToast(getString(R.string.toast_page_too_long, maxCaptureHeight))
        }

        return bitmap
    }

    /**
     * 使用 MediaStore API 将 Bitmap 保存到系统相册
     * 保存路径：Pictures/WebSnap/
     */
    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        // 生成文件名：WebSnap_20231225_143052.png
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val filename = "WebSnap_$timestamp.png"

        // 构建 ContentValues
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            // Android 10 (Q) 及以上使用相对路径
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/WebSnap"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        var imageUri: Uri? = null

        return try {
            // 插入记录，获取 Uri
            imageUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Failed to create MediaStore entry")

            // 打开输出流，写入 Bitmap 数据
            resolver.openOutputStream(imageUri)?.use { outputStream ->
                val compressed = bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    outputStream
                )
                if (!compressed) {
                    throw IOException("Failed to compress bitmap")
                }
            } ?: throw IOException("Failed to open output stream")

            // Android 10+ 标记写入完成
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            true // 保存成功

        } catch (e: Exception) {
            e.printStackTrace()

            // 保存失败时，清理已创建的条目
            imageUri?.let {
                try {
                    resolver.delete(it, null, null)
                } catch (ignored: Exception) {
                    // 忽略删除失败
                }
            }

            false // 保存失败
        }
    }

    /**
     * 隐藏软键盘
     */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { view ->
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
        // 同时让输入框失去焦点
        binding.editTextUrl.clearFocus()
    }

    /**
     * 显示 Toast 消息
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 处理返回键
     * 如果 WebView 可以后退则后退，否则退出应用
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * 页面销毁时清理 WebView
     */
    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
