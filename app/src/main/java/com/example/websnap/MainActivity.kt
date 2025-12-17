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

    // ═══════════════════════════════════════════════════════════════
    // 常量配置
    // ═══════════════════════════════════════════════════════════════

    /** 最大截图高度限制（像素），防止 OOM */
    private val maxCaptureHeight = 20000

    /** PC 模式下的初始缩放比例（67%） */
    private val pcModeInitialScale = 67

    /** 桌面端 UserAgent */
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    /** 允许 WebView 直接加载的 URL Scheme 白名单 */
    private val webViewSchemes = setOf("http", "https", "about", "data", "javascript")

    /** 允许通过系统 Intent 打开的 Scheme */
    private val systemSchemes = setOf("tel", "mailto", "sms")

    // ═══════════════════════════════════════════════════════════════
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    /** 页面是否已加载完成 */
    private var isPageLoaded = false

    /** 原始移动端 UserAgent（在 setupWebView 时获取） */
    private var mobileUserAgent: String = ""

    /** 当前是否为 PC 模式 */
    private var isPcMode = false

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【关键】必须在任何 WebView 实例化之前调用！
        // 这会让 WebView 保留整个文档的绘制数据，而不仅仅是可见区域。
        // 否则 draw(canvas) 只会绘制当前可见部分，其余区域为空白。
        WebView.enableSlowWholeDocumentDraw()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupListeners()
        updateNavigationButtons()
    }

    override fun onDestroy() {
        // 无痕浏览：清理所有 WebView 数据
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WebView 配置
    // ═══════════════════════════════════════════════════════════════

    private fun setupWebView() {
        binding.webView.apply {
            // 保存原始 UserAgent
            mobileUserAgent = settings.userAgentString

            settings.apply {
                // 启用 JavaScript
                javaScriptEnabled = true
                // 启用 DOM Storage
                domStorageEnabled = true
                // 允许数据库存储
                databaseEnabled = true
                // 使用宽视口
                useWideViewPort = true
                // 页面自适应屏幕（移动模式默认开启）
                loadWithOverviewMode = true
                // 启用缩放
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                // 允许混合内容
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // 缓存模式
                cacheMode = WebSettings.LOAD_DEFAULT
                // 允许文件访问
                allowFileAccess = true
                // 清理 UserAgent 中的 wv 标识
                userAgentString = userAgentString.replace("; wv", "")
            }

            // 更新保存的 mobileUserAgent（已清理）
            mobileUserAgent = settings.userAgentString

            // 设置 WebViewClient
            webViewClient = createWebViewClient()

            // 设置 WebChromeClient
            webChromeClient = createWebChromeClient()
        }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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

                // 更新导航按钮状态
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                binding.progressBar.visibility = View.GONE
                binding.buttonCapture.isEnabled = true

                // 更新导航按钮状态
                updateNavigationButtons()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
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
             * URL Scheme 白名单拦截
             * - http/https: WebView 加载
             * - tel/mailto/sms: 系统 Intent
             * - 其他: 静默拦截（防止 ERR_UNKNOWN_URL_SCHEME）
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase() ?: return false

                return when {
                    scheme in webViewSchemes -> false
                    scheme in systemSchemes -> {
                        handleSystemScheme(url)
                        true
                    }
                    else -> true // 静默拦截
                }
            }

            /**
             * 历史记录变化时更新导航按钮
             */
            override fun doUpdateVisitedHistory(
                view: WebView?,
                url: String?,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateNavigationButtons()
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // 可选：更新标题
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 事件监听
    // ═══════════════════════════════════════════════════════════════

    private fun setupListeners() {
        // GO 按钮
        binding.buttonGo.setOnClickListener {
            loadUrl()
        }

        // 输入框回车键
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

        // PC 模式切换
        binding.checkBoxPcMode.setOnCheckedChangeListener { _, isChecked ->
            togglePcMode(isChecked)
        }

        // 截图按钮
        binding.buttonCapture.setOnClickListener {
            performCapture()
        }

        // 后退按钮
        binding.buttonBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        // 前进按钮
        binding.buttonForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 导航功能
    // ═══════════════════════════════════════════════════════════════

    /**
     * 更新后退/前进按钮的启用状态
     */
    private fun updateNavigationButtons() {
        binding.buttonBack.isEnabled = binding.webView.canGoBack()
        binding.buttonForward.isEnabled = binding.webView.canGoForward()
    }

    /**
     * 处理系统级 URL Scheme
     */
    private fun handleSystemScheme(url: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PC 模式切换
    // ═══════════════════════════════════════════════════════════════

    /**
     * 切换 PC 桌面模式
     * - PC 模式：使用桌面 UA + 关闭 overview + 67% 缩放
     * - 移动模式：使用移动 UA + 开启 overview + 默认缩放
     */
    private fun togglePcMode(enableDesktopMode: Boolean) {
        val webSettings = binding.webView.settings
        isPcMode = enableDesktopMode

        if (enableDesktopMode) {
            // 切换到桌面模式
            webSettings.userAgentString = desktopUserAgent
            webSettings.loadWithOverviewMode = false
            webSettings.useWideViewPort = true
            binding.webView.setInitialScale(pcModeInitialScale)

            showToast(getString(R.string.toast_pc_mode_on))
        } else {
            // 切换回移动模式
            webSettings.userAgentString = mobileUserAgent
            webSettings.loadWithOverviewMode = true
            webSettings.useWideViewPort = true
            binding.webView.setInitialScale(0) // 0 = 使用默认

            showToast(getString(R.string.toast_pc_mode_off))
        }

        // 如果已加载页面，刷新以应用新设置
        val currentUrl = binding.webView.url
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
            binding.webView.reload()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // URL 加载
    // ═══════════════════════════════════════════════════════════════

    private fun loadUrl() {
        val inputUrl = binding.editTextUrl.text.toString().trim()

        if (inputUrl.isEmpty()) {
            showToast(getString(R.string.toast_url_empty))
            return
        }

        // 自动补全协议
        val url = when {
            inputUrl.startsWith("http://") || inputUrl.startsWith("https://") -> inputUrl
            inputUrl.startsWith("www.") -> "https://$inputUrl"
            else -> "https://$inputUrl"
        }

        hideKeyboard()

        // 如果是 PC 模式，确保缩放设置正确
        if (isPcMode) {
            binding.webView.setInitialScale(pcModeInitialScale)
        }

        binding.webView.loadUrl(url)
    }

    // ═══════════════════════════════════════════════════════════════
    // 截图功能
    // ═══════════════════════════════════════════════════════════════

    private fun performCapture() {
        if (!isPageLoaded) {
            showToast(getString(R.string.toast_page_not_loaded))
            return
        }

        // 禁用按钮，防止重复点击
        binding.buttonCapture.isEnabled = false
        binding.buttonCapture.text = getString(R.string.button_capturing)

        binding.webView.post {
            try {
                val bitmap = captureWebViewToBitmap()

                if (bitmap != null) {
                    val saved = saveBitmapToGallery(bitmap)
                    bitmap.recycle()

                    if (saved) {
                        showToast(getString(R.string.toast_capture_success))
                    } else {
                        showToast(getString(R.string.toast_capture_failed))
                    }
                } else {
                    showToast(getString(R.string.toast_capture_failed))
                }

            } catch (e: OutOfMemoryError) {
                showToast(getString(R.string.toast_memory_insufficient))
                System.gc()

            } catch (e: Exception) {
                showToast(getString(R.string.toast_capture_failed))
                e.printStackTrace()

            } finally {
                binding.buttonCapture.isEnabled = true
                binding.buttonCapture.text = getString(R.string.button_capture)
            }
        }
    }

    /**
     * 将 WebView 内容绘制到 Bitmap
     */
    private fun captureWebViewToBitmap(): Bitmap? {
        val webView = binding.webView

        // 步骤1：计算实际内容尺寸
        @Suppress("DEPRECATION")
        val scale = webView.scale
        val contentWidth = webView.width
        var contentHeight = (webView.contentHeight * scale).toInt()

        if (contentWidth <= 0 || contentHeight <= 0) {
            return null
        }

        // 步骤2：高度限制
        var wasTruncated = false
        if (contentHeight > maxCaptureHeight) {
            contentHeight = maxCaptureHeight
            wasTruncated = true
        }

        // 步骤3：内存检查
        val requiredMemory = contentWidth.toLong() * contentHeight.toLong() * 4L
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()

        if (requiredMemory > freeMemory * 0.8) {
            showToast(getString(R.string.toast_memory_insufficient))
            return null
        }

        // 步骤4：临时切换到软件渲染
        val originalLayerType = webView.layerType
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val bitmap: Bitmap?
        try {
            // 步骤5：创建 Bitmap 并绘制
            bitmap = Bitmap.createBitmap(
                contentWidth,
                contentHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)
            webView.draw(canvas)

        } finally {
            // 步骤6：恢复渲染类型
            webView.setLayerType(originalLayerType, null)
        }

        if (wasTruncated) {
            showToast(getString(R.string.toast_page_too_long, maxCaptureHeight))
        }

        return bitmap
    }

    // ═══════════════════════════════════════════════════════════════
    // 图片保存
    // ═══════════════════════════════════════════════════════════════

    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val filename = "WebSnap_$timestamp.png"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

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
            imageUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw IOException("Failed to create MediaStore entry")

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }

            true

        } catch (e: Exception) {
            e.printStackTrace()

            imageUri?.let {
                try {
                    resolver.delete(it, null, null)
                } catch (ignored: Exception) {
                }
            }

            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { view ->
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
        binding.editTextUrl.clearFocus()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
