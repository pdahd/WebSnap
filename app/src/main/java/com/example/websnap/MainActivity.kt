package com.example.websnap

import android.annotation.SuppressLint
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

    /** PC 模式模拟的桌面视口宽度 */
    private val desktopViewportWidth = 1024

    /** 桌面端 UserAgent */
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    /** 允许 WebView 直接加载的 URL Scheme 白名单 */
    private val webViewSchemes = setOf("http", "https", "about", "data", "javascript")

    /** 允许通过系统 Intent 打开的 Scheme */
    private val systemSchemes = setOf("tel", "mailto", "sms")

    /**
     * 桌面模式 JavaScript 脚本
     * 功能：欺骗网页的视口宽度检测，强制使用桌面布局
     */
    private val desktopModeScript: String
        get() = """
            (function() {
                var desktopWidth = $desktopViewportWidth;
                
                // === 1. 覆盖 JavaScript 的屏幕宽度属性 ===
                try {
                    Object.defineProperty(window, 'innerWidth', {
                        get: function() { return desktopWidth; },
                        configurable: true
                    });
                    Object.defineProperty(window, 'outerWidth', {
                        get: function() { return desktopWidth; },
                        configurable: true
                    });
                    Object.defineProperty(document.documentElement, 'clientWidth', {
                        get: function() { return desktopWidth; },
                        configurable: true
                    });
                    Object.defineProperty(screen, 'width', {
                        get: function() { return desktopWidth; },
                        configurable: true
                    });
                    Object.defineProperty(screen, 'availWidth', {
                        get: function() { return desktopWidth; },
                        configurable: true
                    });
                } catch(e) {
                    console.log('WebSnap: Failed to override screen properties');
                }
                
                // === 2. 修改 viewport meta 标签 ===
                function setDesktopViewport() {
                    var viewport = document.querySelector('meta[name="viewport"]');
                    var content = 'width=' + desktopWidth + ', initial-scale=0.67, minimum-scale=0.1, maximum-scale=10';
                    
                    if (viewport) {
                        viewport.setAttribute('content', content);
                    } else if (document.head) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        viewport.content = content;
                        document.head.insertBefore(viewport, document.head.firstChild);
                    }
                }
                
                // === 3. 执行 viewport 修改 ===
                setDesktopViewport();
                
                // DOM 加载完成后再执行一次（确保生效）
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', setDesktopViewport);
                }
                
                // === 4. 触发 resize 事件，让 JS 响应式代码重新执行 ===
                try {
                    window.dispatchEvent(new Event('resize'));
                } catch(e) {}
                
                console.log('WebSnap: Desktop mode script injected, viewport=' + desktopWidth);
            })();
        """.trimIndent()

    // ═══════════════════════════════════════════════════════════════
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    /** 页面是否已加载完成 */
    private var isPageLoaded = false

    /** 原始移动端 UserAgent */
    private var mobileUserAgent: String = ""

    /** 当前是否为 PC 模式 */
    private var isPcMode = false

    /** 当前页面是否已应用桌面模式（防止重复 reload） */
    private var desktopModeAppliedForCurrentPage = false

    /** 当前正在加载的 URL（用于检测页面变化） */
    private var currentLoadingUrl: String? = null

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【关键】必须在任何 WebView 实例化之前调用！
        WebView.enableSlowWholeDocumentDraw()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupListeners()
        updateNavigationButtons()
    }

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            mobileUserAgent = settings.userAgentString

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                userAgentString = userAgentString.replace("; wv", "")
            }

            mobileUserAgent = settings.userAgentString
            webViewClient = createWebViewClient()
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

                // 检测是否是新页面
                if (url != currentLoadingUrl) {
                    currentLoadingUrl = url
                    desktopModeAppliedForCurrentPage = false
                }

                url?.let {
                    binding.editTextUrl.setText(it)
                    binding.editTextUrl.setSelection(it.length)
                }

                updateNavigationButtons()

                // PC 模式：在页面开始加载时就尝试注入脚本
                if (isPcMode && view != null) {
                    view.evaluateJavascript(desktopModeScript, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                binding.progressBar.visibility = View.GONE
                binding.buttonCapture.isEnabled = true
                updateNavigationButtons()

                // PC 模式：页面加载完成后的处理
                if (isPcMode && view != null) {
                    handleDesktopModePageFinished(view)
                }
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

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase() ?: return false

                // URL 变化时重置桌面模式标志
                desktopModeAppliedForCurrentPage = false

                return when {
                    scheme in webViewSchemes -> false
                    scheme in systemSchemes -> {
                        handleSystemScheme(url)
                        true
                    }
                    else -> true
                }
            }

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

    /**
     * PC 模式下页面加载完成的处理逻辑
     */
    private fun handleDesktopModePageFinished(view: WebView) {
        // 注入桌面模式脚本
        view.evaluateJavascript(desktopModeScript) { _ ->
            // 如果是首次加载此页面的桌面模式，执行一次 reload 让 viewport 生效
            if (!desktopModeAppliedForCurrentPage) {
                desktopModeAppliedForCurrentPage = true
                
                // 延迟 reload，让脚本有时间执行
                view.postDelayed({
                    if (isPcMode && isPageLoaded) {
                        view.reload()
                    }
                }, 300)
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
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 事件监听
    // ═══════════════════════════════════════════════════════════════

    private fun setupListeners() {
        binding.buttonGo.setOnClickListener {
            loadUrl()
        }

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

        binding.checkBoxPcMode.setOnCheckedChangeListener { _, isChecked ->
            togglePcMode(isChecked)
        }

        binding.buttonCapture.setOnClickListener {
            performCapture()
        }

        binding.buttonBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        binding.buttonForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 导航功能
    // ═══════════════════════════════════════════════════════════════

    private fun updateNavigationButtons() {
        binding.buttonBack.isEnabled = binding.webView.canGoBack()
        binding.buttonForward.isEnabled = binding.webView.canGoForward()
    }

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

    private fun togglePcMode(enableDesktopMode: Boolean) {
        val webSettings = binding.webView.settings
        isPcMode = enableDesktopMode

        // 重置桌面模式应用标志，允许重新应用
        desktopModeAppliedForCurrentPage = false

        if (enableDesktopMode) {
            // === 切换到桌面模式 ===
            webSettings.userAgentString = desktopUserAgent
            webSettings.useWideViewPort = true
            webSettings.loadWithOverviewMode = false
            
            showToast(getString(R.string.toast_pc_mode_on))
        } else {
            // === 切换回移动模式 ===
            webSettings.userAgentString = mobileUserAgent
            webSettings.useWideViewPort = true
            webSettings.loadWithOverviewMode = true
            
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

        val url = when {
            inputUrl.startsWith("http://") || inputUrl.startsWith("https://") -> inputUrl
            inputUrl.startsWith("www.") -> "https://$inputUrl"
            else -> "https://$inputUrl"
        }

        hideKeyboard()
        
        // 重置桌面模式标志
        desktopModeAppliedForCurrentPage = false
        
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

    private fun captureWebViewToBitmap(): Bitmap? {
        val webView = binding.webView

        @Suppress("DEPRECATION")
        val scale = webView.scale
        val contentWidth = webView.width
        var contentHeight = (webView.contentHeight * scale).toInt()

        if (contentWidth <= 0 || contentHeight <= 0) {
            return null
        }

        var wasTruncated = false
        if (contentHeight > maxCaptureHeight) {
            contentHeight = maxCaptureHeight
            wasTruncated = true
        }

        val requiredMemory = contentWidth.toLong() * contentHeight.toLong() * 4L
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()

        if (requiredMemory > freeMemory * 0.8) {
            showToast(getString(R.string.toast_memory_insufficient))
            return null
        }

        val originalLayerType = webView.layerType
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val bitmap: Bitmap?
        try {
            bitmap = Bitmap.createBitmap(
                contentWidth,
                contentHeight,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)
            webView.draw(canvas)

        } finally {
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
