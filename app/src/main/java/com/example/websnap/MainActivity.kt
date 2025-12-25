package com.example.websnap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.websnap.databinding.ActivityMainBinding
import com.example.websnap.databinding.BottomSheetBookmarksBinding
import com.example.websnap.databinding.BottomSheetRefreshBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), RefreshService.RefreshCallback {

    private lateinit var binding: ActivityMainBinding

    // ═══════════════════════════════════════════════════════════════
    // 常量配置
    // ═══════════════════════════════════════════════════════════════

    private val homePageUrl = "file:///android_asset/home.html"
    private val maxCaptureHeight = 20000
    private val desktopViewportWidth = 1024

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    private val webViewSchemes = setOf("http", "https", "about", "data", "javascript", "file")
    private val systemSchemes = setOf("tel", "mailto", "sms")

    private val desktopModeScript: String
        get() = """
            (function() {
                var desktopWidth = $desktopViewportWidth;
                
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
                } catch(e) {}
                
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
                
                setDesktopViewport();
                
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', setDesktopViewport);
                }
                
                try {
                    window.dispatchEvent(new Event('resize'));
                } catch(e) {}
            })();
        """.trimIndent()

    /**
     * 防休眠心跳脚本
     * 通过模拟用户活动来欺骗服务器保持会话
     */
    private val antiSleepHeartbeatScript: String
        get() = """
            (function() {
                try {
                    // 1. 微小滚动（最有效的保活方式）
                    window.scrollBy(0, 1);
                    window.scrollBy(0, -1);
                    
                    // 2. 触发鼠标移动事件
                    document.dispatchEvent(new MouseEvent('mousemove', {
                        bubbles: true,
                        clientX: Math.random() * 100,
                        clientY: Math.random() * 100
                    }));
                    
                    // 3. 触发键盘事件（某些网站检测这个）
                    document.dispatchEvent(new KeyboardEvent('keydown', {
                        bubbles: true,
                        key: 'Shift',
                        code: 'ShiftLeft'
                    }));
                    document.dispatchEvent(new KeyboardEvent('keyup', {
                        bubbles: true,
                        key: 'Shift',
                        code: 'ShiftLeft'
                    }));
                    
                    // 4. 触发 focus 事件
                    window.dispatchEvent(new Event('focus'));
                    
                    console.log('[WebSnap] Anti-sleep heartbeat sent');
                } catch(e) {
                    console.log('[WebSnap] Heartbeat error: ' + e);
                }
            })();
        """.trimIndent()

    // ═══════════════════════════════════════════════════════════════
    // 权限请求码
    // ═══════════════════════════════════════════════════════════════

    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 1001
        private const val PERMISSION_REQUEST_MICROPHONE = 1002
        private const val PERMISSION_REQUEST_STORAGE = 1003

        // JS 记事本脚本
        private const val JS_EDITOR_SCRIPT = "javascript:(function(){var e=document.getElementById('temp-editor');if(e){e.remove();return;}var box=document.createElement('div');box.id='temp-editor';box.style.cssText='position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:999999;padding:10px;box-sizing:border-box;display:flex;flex-direction:column;';var bar=document.createElement('div');bar.style.cssText='display:flex;gap:10px;margin-bottom:10px;';var copyBtn=document.createElement('button');copyBtn.textContent='📋 复制';copyBtn.style.cssText='padding:10px 15px;font-size:16px;border:none;border-radius:5px;background:#4CAF50;color:white;';var clearBtn=document.createElement('button');clearBtn.textContent='🗑️ 清空';clearBtn.style.cssText='padding:10px 15px;font-size:16px;border:none;border-radius:5px;background:#ff9800;color:white;';var closeBtn=document.createElement('button');closeBtn.textContent='✖ 关闭';closeBtn.style.cssText='padding:10px 15px;font-size:16px;border:none;border-radius:5px;background:#f44336;color:white;margin-left:auto;';var ta=document.createElement('textarea');ta.style.cssText='flex:1;width:100%;font-size:16px;padding:15px;box-sizing:border-box;border:none;border-radius:5px;resize:none;line-height:1.6;';ta.placeholder='在这里输入或粘贴文字...';copyBtn.onclick=function(){ta.select();navigator.clipboard.writeText(ta.value).then(function(){copyBtn.textContent='✅ 已复制';setTimeout(function(){copyBtn.textContent='📋 复制';},1500);});};clearBtn.onclick=function(){ta.value='';ta.focus();};closeBtn.onclick=function(){box.remove();};bar.appendChild(copyBtn);bar.appendChild(clearBtn);bar.appendChild(closeBtn);box.appendChild(bar);box.appendChild(ta);document.body.appendChild(box);ta.focus();})();"
    }

    // ═══════════════════════════════════════════════════════════════
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    private var isPageLoaded = false
    private var mobileUserAgent: String = ""
    private var isPcMode = false
    private var desktopModeAppliedForCurrentPage = false
    private var currentLoadingUrl: String? = null
    private var currentPageTitle: String = ""

    // ═══════════════════════════════════════════════════════════════
    // 文件上传相关
    // ═══════════════════════════════════════════════════════════════

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 文件选择器启动器
     * 必须作为类成员变量在 Activity 创建前注册
     */
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result.resultCode, result.data)
        }

    // ═══════════════════════════════════════════════════════════════
    // WebView 权限请求相关
    // ═══════════════════════════════════════════════════════════════

    private var pendingPermissionRequest: PermissionRequest? = null

    // ═══════════════════════════════════════════════════════════════
    // 书签相关
    // ═══════════════════════════════════════════════════════════════

    private lateinit var bookmarkManager: BookmarkManager
    private var bookmarkBottomSheet: BottomSheetDialog? = null

    // ═══════════════════════════════════════════════════════════════
    // 刷新服务相关
    // ═══════════════════════════════════════════════════════════════

    private var refreshService: RefreshService? = null
    private var isServiceBound = false
    private var refreshBottomSheet: BottomSheetDialog? = null

    private var selectedScheduledTime: Calendar? = null
    private var customIntervalSeconds: Long? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RefreshService.RefreshBinder
            refreshService = binder.getService()
            refreshService?.setCallback(this@MainActivity)
            isServiceBound = true
            updateRefreshButtonState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            refreshService?.setCallback(null)
            refreshService = null
            isServiceBound = false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 下载相关
    // ═══════════════════════════════════════════════════════════════

    /** 后台执行器，用于图片下载等耗时操作 */
    private val executor = Executors.newSingleThreadExecutor()

    /** 待保存的图片 URL（等待权限授权后使用） */
    private var pendingImageUrl: String? = null

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.enableSlowWholeDocumentDraw()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarkManager = BookmarkManager.getInstance(this)

        setupWebView()
        setupListeners()
        updateNavigationButtons()
        updateBookmarkButton()
        updatePcModeButton()

        loadHomePage()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, RefreshService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (refreshService?.hasActiveTask() != true) {
            binding.webView.onResume()
            binding.webView.resumeTimers()
        }
        updateRefreshButtonState()
    }

    override fun onPause() {
        super.onPause()
        if (refreshService?.hasActiveTask() != true) {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
    }

    override fun onDestroy() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null

        if (isServiceBound) {
            refreshService?.setCallback(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }

        bookmarkBottomSheet?.dismiss()
        refreshBottomSheet?.dismiss()

        binding.webView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }

        executor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            refreshBottomSheet?.isShowing == true -> {
                refreshBottomSheet?.dismiss()
            }
            bookmarkBottomSheet?.isShowing == true -> {
                bookmarkBottomSheet?.dismiss()
            }
            binding.webView.canGoBack() -> {
                binding.webView.goBack()
            }
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 文件上传处理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 处理文件选择结果
     */
    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = fileUploadCallback
        fileUploadCallback = null

        if (callback == null) {
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return
        }

        val results: Array<Uri>? = when {
            data?.data != null -> {
                arrayOf(data.data!!)
            }
            data?.clipData != null -> {
                val clipData = data.clipData!!
                Array(clipData.itemCount) { i ->
                    clipData.getItemAt(i).uri
                }
            }
            else -> null
        }

        callback.onReceiveValue(results)
    }

    /**
     * 启动系统文件选择器
     */
    private fun launchSystemFilePicker(params: WebChromeClient.FileChooserParams?) {
        try {
            val allowMultiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

            val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }

            val chooserIntent = Intent.createChooser(contentIntent, null).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(documentIntent))
            }

            fileChooserLauncher.launch(chooserIntent)

        } catch (e: Exception) {
            e.printStackTrace()
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            showToast(getString(R.string.toast_file_picker_error))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WebView 配置
    // ═══════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
                mediaPlaybackRequiresUserGesture = false
            }

            mobileUserAgent = settings.userAgentString
            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()

            // ★ 添加 JavaScript 接口（用于 blob 下载）
            addJavascriptInterface(WebAppInterface(this@MainActivity), WebAppInterface.INTERFACE_NAME)

            // ★ 添加下载监听器
            setDownloadListener(createDownloadListener())

            // ★ 添加长按监听器
            setOnLongClickListener {
                handleLongPress()
                true
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)
    }

    /**
     * 创建下载监听器
     */
    private fun createDownloadListener(): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            handleDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }
    }

    /**
     * 处理下载请求
     */
    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long
    ) {
        when {
            // Blob URL 需要特殊处理
            url.startsWith("blob:") -> {
                handleBlobDownload(url, mimeType)
            }
            // Data URL 直接解析保存
            url.startsWith("data:") -> {
                handleDataUrlDownload(url)
            }
            // 普通 HTTP/HTTPS URL 使用系统下载管理器
            url.startsWith("http://") || url.startsWith("https://") -> {
                handleHttpDownload(url, userAgent, contentDisposition, mimeType)
            }
            else -> {
                showToast(getString(R.string.toast_download_unsupported))
            }
        }
    }

    /**
     * 处理 HTTP/HTTPS 下载
     */
    private fun handleHttpDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                // 设置 User-Agent
                addRequestHeader("User-Agent", userAgent)

                // 添加 Cookie
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                }

                // 生成文件名
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

                // 设置通知
                setTitle(fileName)
                setDescription("正在下载...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // 设置保存位置
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                // 允许在移动网络和 WiFi 下下载
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or
                    DownloadManager.Request.NETWORK_MOBILE
                )
            }

            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            showToast(getString(R.string.toast_download_started, fileName))

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.toast_download_failed))
        }
    }

    /**
     * 处理 Blob URL 下载
     * 通过注入 JavaScript 将 blob 转换为 base64 并传给 Android
     */
    private fun handleBlobDownload(blobUrl: String, mimeType: String) {
        val script = """
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$blobUrl', true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            var mime = xhr.response.type || '$mimeType';
                            window.${WebAppInterface.INTERFACE_NAME}.saveBase64File(base64, mime, null);
                        };
                        reader.readAsDataURL(xhr.response);
                    }
                };
                xhr.onerror = function() {
                    console.log('[WebSnap] Blob download failed');
                };
                xhr.send();
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
        showToast(getString(R.string.toast_download_started, "文件"))
    }

    /**
     * 处理 Data URL 下载
     */
    private fun handleDataUrlDownload(dataUrl: String) {
        try {
            // 解析 data URL: data:[<mediatype>][;base64],<data>
            val parts = dataUrl.substringAfter("data:").split(",", limit = 2)
            if (parts.size != 2) {
                showToast(getString(R.string.toast_download_failed))
                return
            }

            val metaPart = parts[0]
            val dataPart = parts[1]

            val mimeType = metaPart.substringBefore(";").ifBlank { "application/octet-stream" }
            val isBase64 = metaPart.contains("base64")

            val data = if (isBase64) {
                Base64.decode(dataPart, Base64.DEFAULT)
            } else {
                dataPart.toByteArray()
            }

            // 使用 WebAppInterface 的逻辑保存
            val webInterface = WebAppInterface(this)
            val base64Data = Base64.encodeToString(data, Base64.DEFAULT)
            webInterface.saveBase64File(base64Data, mimeType, null)

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.toast_download_failed))
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

                if (url != currentLoadingUrl) {
                    currentLoadingUrl = url
                    desktopModeAppliedForCurrentPage = false
                }

                if (url != null && !url.startsWith("file:")) {
                    binding.editTextUrl.setText(url)
                    binding.editTextUrl.setSelection(url.length)
                } else if (url?.startsWith("file:") == true) {
                    binding.editTextUrl.setText("")
                }

                updateNavigationButtons()
                updateBookmarkButton()

                if (isPcMode && view != null && url?.startsWith("file:") != true) {
                    view.evaluateJavascript(desktopModeScript, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                binding.progressBar.visibility = View.GONE
                binding.buttonCapture.isEnabled = true
                updateNavigationButtons()
                updateBookmarkButton()

                CookieManager.getInstance().flush()

                if (isPcMode && view != null && url?.startsWith("file:") != true) {
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
                updateBookmarkButton()
            }
        }
    }

    private fun handleDesktopModePageFinished(view: WebView) {
        view.evaluateJavascript(desktopModeScript) { _ ->
            if (!desktopModeAppliedForCurrentPage) {
                desktopModeAppliedForCurrentPage = true

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
                currentPageTitle = title ?: ""
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                launchSystemFilePicker(fileChooserParams)

                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { permRequest ->
                    pendingPermissionRequest = permRequest

                    val requestedResources = permRequest.resources
                    val permissionsToRequest = mutableListOf<String>()

                    for (resource in requestedResources) {
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                if (!hasCameraPermission()) {
                                    permissionsToRequest.add(Manifest.permission.CAMERA)
                                }
                            }
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                if (!hasMicrophonePermission()) {
                                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    }

                    if (permissionsToRequest.isEmpty()) {
                        permRequest.grant(requestedResources)
                        pendingPermissionRequest = null
                    } else {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            permissionsToRequest.toTypedArray(),
                            PERMISSION_REQUEST_CAMERA
                        )
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                super.onPermissionRequestCanceled(request)
                pendingPermissionRequest = null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 长按处理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 处理 WebView 长按事件
     */
    private fun handleLongPress(): Boolean {
        val hitTestResult = binding.webView.hitTestResult
        val type = hitTestResult.type
        val extra = hitTestResult.extra

        return when (type) {
            WebView.HitTestResult.IMAGE_TYPE -> {
                // 长按图片
                extra?.let { showImageContextMenu(it) }
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // 长按带链接的图片
                extra?.let { showImageLinkContextMenu(it) }
                true
            }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                // 长按链接
                extra?.let { showLinkContextMenu(it) }
                true
            }
            else -> false
        }
    }

    /**
     * 显示图片长按菜单
     */
    private fun showImageContextMenu(imageUrl: String) {
        val options = arrayOf(
            getString(R.string.menu_save_image),
            getString(R.string.menu_copy_image_url)
        )

        AlertDialog.Builder(this, R.style.Theme_WebSnap_AlertDialog)
            .setTitle(getString(R.string.menu_title_image))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveImage(imageUrl)
                    1 -> copyToClipboard(imageUrl)
                }
            }
            .show()
    }

    /**
     * 显示带链接的图片长按菜单
     */
    private fun showImageLinkContextMenu(imageUrl: String) {
        // 尝试获取链接地址（通过注入 JS）
        val options = arrayOf(
            getString(R.string.menu_save_image),
            getString(R.string.menu_copy_image_url)
        )

        AlertDialog.Builder(this, R.style.Theme_WebSnap_AlertDialog)
            .setTitle(getString(R.string.menu_title_image_link))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveImage(imageUrl)
                    1 -> copyToClipboard(imageUrl)
                }
            }
            .show()
    }

    /**
     * 显示链接长按菜单
     */
    private fun showLinkContextMenu(linkUrl: String) {
        val options = arrayOf(
            getString(R.string.menu_copy_link_url)
        )

        AlertDialog.Builder(this, R.style.Theme_WebSnap_AlertDialog)
            .setTitle(getString(R.string.menu_title_link))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard(linkUrl)
                }
            }
            .show()
    }

    /**
     * 复制文本到剪贴板
     */
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WebSnap", text)
            clipboard.setPrimaryClip(clip)
            showToast(getString(R.string.toast_link_copied))
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.toast_copy_failed))
        }
    }

    /**
     * 保存图片
     */
    private fun saveImage(imageUrl: String) {
        when {
            imageUrl.startsWith("data:") -> {
                // Base64 图片直接解码保存
                saveDataUrlImage(imageUrl)
            }
            imageUrl.startsWith("blob:") -> {
                // Blob URL 需要通过 JS 转换
                saveBlobImage(imageUrl)
            }
            imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> {
                // HTTP 图片需要下载
                saveHttpImage(imageUrl)
            }
            else -> {
                showToast(getString(R.string.toast_image_save_failed))
            }
        }
    }

    /**
     * 保存 Data URL 格式的图片
     */
    private fun saveDataUrlImage(dataUrl: String) {
        try {
            val parts = dataUrl.substringAfter("data:").split(",", limit = 2)
            if (parts.size != 2) {
                showToast(getString(R.string.toast_image_save_failed))
                return
            }

            val metaPart = parts[0]
            val dataPart = parts[1]
            val mimeType = metaPart.substringBefore(";").ifBlank { "image/png" }

            val imageData = Base64.decode(dataPart, Base64.DEFAULT)
            saveImageBytes(imageData, mimeType)

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.toast_image_save_failed))
        }
    }

    /**
     * 保存 Blob URL 格式的图片
     */
    private fun saveBlobImage(blobUrl: String) {
        val script = """
            (function() {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', '$blobUrl', true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            var mime = xhr.response.type || 'image/png';
                            window.${WebAppInterface.INTERFACE_NAME}.saveBase64Image(base64, mime);
                        };
                        reader.readAsDataURL(xhr.response);
                    }
                };
                xhr.send();
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
        showToast(getString(R.string.toast_image_saving))
    }

    /**
     * 保存 HTTP/HTTPS 格式的图片
     */
    private fun saveHttpImage(imageUrl: String) {
        showToast(getString(R.string.toast_image_saving))

        executor.execute {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()

                val inputStream: InputStream = connection.inputStream
                val imageData = inputStream.readBytes()
                inputStream.close()

                // 获取 MIME 类型
                val mimeType = connection.contentType ?: guessMimeTypeFromUrl(imageUrl)

                runOnUiThread {
                    saveImageBytes(imageData, mimeType)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    showToast(getString(R.string.toast_image_save_failed))
                }
            }
        }
    }

    /**
     * 从 URL 猜测 MIME 类型
     */
    private fun guessMimeTypeFromUrl(url: String): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(url)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/png"
    }

    /**
     * 将图片字节数据保存到相册
     */
    private fun saveImageBytes(imageData: ByteArray, mimeType: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val extension = when {
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                mimeType.contains("png") -> ".png"
                mimeType.contains("gif") -> ".gif"
                mimeType.contains("webp") -> ".webp"
                else -> ".png"
            }
            val fileName = "WebSnap_$timestamp$extension"

            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/WebSnap"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(imageData)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    true
                } else {
                    false
                }
            } else {
                // Android 9 及以下
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val webSnapDir = File(picturesDir, "WebSnap")
                if (!webSnapDir.exists()) {
                    webSnapDir.mkdirs()
                }

                val file = File(webSnapDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(imageData)
                }
                true
            }

            showToast(
                if (saved) getString(R.string.toast_image_saved)
                else getString(R.string.toast_image_save_failed)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.toast_image_save_failed))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 权限检查与请求
    // ═══════════════════════════════════════════════════════════════

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_CAMERA, PERMISSION_REQUEST_MICROPHONE -> {
                pendingPermissionRequest?.let { request ->
                    val grantedResources = mutableListOf<String>()

                    for (resource in request.resources) {
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                if (hasCameraPermission()) {
                                    grantedResources.add(resource)
                                } else {
                                    showToast(getString(R.string.toast_permission_camera_denied))
                                }
                            }
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                if (hasMicrophonePermission()) {
                                    grantedResources.add(resource)
                                } else {
                                    showToast(getString(R.string.toast_permission_mic_denied))
                                }
                            }
                        }
                    }

                    if (grantedResources.isNotEmpty()) {
                        request.grant(grantedResources.toTypedArray())
                    } else {
                        request.deny()
                    }
                    pendingPermissionRequest = null
                }
            }
            PERMISSION_REQUEST_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 权限获取成功，重试保存图片
                    pendingImageUrl?.let { saveImage(it) }
                } else {
                    showToast(getString(R.string.toast_download_no_permission))
                }
                pendingImageUrl = null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 事件监听
    // ═══════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
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

        // 注意：这里的 buttonBookmark 是在底部工具栏的新位置
        binding.buttonBookmark.setOnClickListener {
            toggleBookmark()
        }

        binding.buttonBookmark.setOnLongClickListener {
            showBookmarkSheet()
            true
        }

        binding.buttonPcMode.setOnClickListener {
            togglePcMode()
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

        binding.buttonHome.setOnClickListener {
            loadHomePage()
        }

        binding.buttonRefresh.setOnClickListener {
            performRefresh()
        }

        binding.buttonRefresh.setOnLongClickListener {
            showRefreshSheet()
            true
        }

        // 新增 JS 记事本按钮的监听器
        val buttonJsNotepad = findViewById<Button>(R.id.buttonJsNotepad)
        buttonJsNotepad.setOnClickListener {
            binding.webView.evaluateJavascript(JS_EDITOR_SCRIPT, null)
        }

        // 注意：这里的 buttonCapture 是在顶部工具栏的新位置
        binding.buttonCapture.setOnClickListener {
            captureVisibleArea()
        }

        binding.buttonCapture.setOnLongClickListener {
            captureWholePage()
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 主页功能
    // ═══════════════════════════════════════════════════════════════

    private fun loadHomePage() {
        binding.editTextUrl.setText("")
        binding.webView.loadUrl(homePageUrl)
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
    // 书签功能
    // ═══════════════════════════════════════════════════════════════

    private fun updateBookmarkButton() {
        val currentUrl = binding.webView.url

        val isBookmarked = if (!currentUrl.isNullOrBlank()
            && currentUrl != "about:blank"
            && !currentUrl.startsWith("file:")) {
            bookmarkManager.contains(currentUrl)
        } else {
            false
        }

        binding.buttonBookmark.text = if (isBookmarked) {
            getString(R.string.button_bookmark_filled)
        } else {
            getString(R.string.button_bookmark_empty)
        }
    }

    private fun toggleBookmark() {
        val currentUrl = binding.webView.url

        if (currentUrl.isNullOrBlank()
            || currentUrl == "about:blank"
            || currentUrl.startsWith("file:")) {
            showToast(getString(R.string.toast_bookmark_need_page))
            return
        }

        if (bookmarkManager.contains(currentUrl)) {
            bookmarkManager.remove(currentUrl)
            showToast(getString(R.string.toast_bookmark_removed))
        } else {
            val title = currentPageTitle.ifBlank { currentUrl }
            val bookmark = Bookmark(title = title, url = currentUrl)
            bookmarkManager.add(bookmark)
            showToast(getString(R.string.toast_bookmark_added))
        }

        updateBookmarkButton()
    }

    private fun showBookmarkSheet() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetBookmarksBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)

        val adapter = BookmarkAdapter(
            onItemClick = { bookmark ->
                bottomSheet.dismiss()
                binding.webView.loadUrl(bookmark.url)
            },
            onDeleteClick = { bookmark, position ->
                bookmarkManager.removeAt(position)
                (sheetBinding.recyclerViewBookmarks.adapter as? BookmarkAdapter)?.removeAt(position)
                showToast(getString(R.string.toast_bookmark_deleted, bookmark.title))

                if (bookmarkManager.isEmpty()) {
                    sheetBinding.recyclerViewBookmarks.visibility = View.GONE
                    sheetBinding.emptyStateContainer.visibility = View.VISIBLE
                }

                updateBookmarkButton()
            }
        )

        sheetBinding.recyclerViewBookmarks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
        }

        val bookmarks = bookmarkManager.getAll()
        if (bookmarks.isEmpty()) {
            sheetBinding.recyclerViewBookmarks.visibility = View.GONE
            sheetBinding.emptyStateContainer.visibility = View.VISIBLE
        } else {
            sheetBinding.recyclerViewBookmarks.visibility = View.VISIBLE
            sheetBinding.emptyStateContainer.visibility = View.GONE
            adapter.submitList(bookmarks)
        }

        bookmarkBottomSheet = bottomSheet
        bottomSheet.show()
    }

// ═══════════════════════════════════════════════════════════════
// ★★★ Part 1 结束 ★★★
// ★★★ Part 2 从「PC 模式」开始 ★★★
// ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════
    // PC 模式
    // ═══════════════════════════════════════════════════════════════

    private fun updatePcModeButton() {
        binding.buttonPcMode.isSelected = isPcMode
    }

    private fun togglePcMode() {
        isPcMode = !isPcMode
        updatePcModeButton()

        val webSettings = binding.webView.settings
        desktopModeAppliedForCurrentPage = false

        if (isPcMode) {
            webSettings.userAgentString = desktopUserAgent
            webSettings.useWideViewPort = true
            webSettings.loadWithOverviewMode = false
            showToast(getString(R.string.toast_pc_mode_on))
        } else {
            webSettings.userAgentString = mobileUserAgent
            webSettings.useWideViewPort = true
            webSettings.loadWithOverviewMode = true
            showToast(getString(R.string.toast_pc_mode_off))
        }

        val currentUrl = binding.webView.url
        if (!currentUrl.isNullOrBlank()
            && currentUrl != "about:blank"
            && !currentUrl.startsWith("file:")) {
            binding.webView.reload()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 刷新功能
    // ═══════════════════════════════════════════════════════════════

    private fun performRefresh() {
        val currentUrl = binding.webView.url

        if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
            showToast(getString(R.string.toast_refresh_need_page))
            return
        }

        binding.webView.reload()
    }

    private fun updateRefreshButtonState() {
        val service = refreshService

        if (service != null && service.hasActiveTask()) {
            binding.buttonRefresh.isActivated = true

            val task = service.getCurrentTask()
            val remaining = service.getRemainingSeconds()

            when (task) {
                is RefreshTask.Interval -> {
                    binding.buttonRefresh.text = getString(
                        R.string.button_refresh_countdown,
                        formatSeconds(remaining)
                    )
                }
                is RefreshTask.Scheduled -> {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    binding.buttonRefresh.text = getString(
                        R.string.button_refresh_scheduled,
                        timeFormat.format(Date(task.targetTimeMillis))
                    )
                }
                is RefreshTask.AntiSleep -> {
                    binding.buttonRefresh.text = getString(
                        R.string.button_anti_sleep_active,
                        formatSeconds(remaining)
                    )
                }
                null -> {
                    binding.buttonRefresh.isActivated = false
                    binding.buttonRefresh.text = getString(R.string.button_refresh_default)
                }
            }
        } else {
            binding.buttonRefresh.isActivated = false
            binding.buttonRefresh.text = getString(R.string.button_refresh_default)
        }
    }

    private fun showRefreshSheet() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetRefreshBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)

        val intervalOptions = resources.getStringArray(R.array.interval_options)
        val intervalValues = resources.getIntArray(R.array.interval_values)
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervalOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sheetBinding.spinnerInterval.adapter = spinnerAdapter
        sheetBinding.spinnerInterval.setSelection(4)

        selectedScheduledTime = null
        customIntervalSeconds = null

        // ═══════════════════════════════════════════════════════════
        // 防休眠模式 UI 逻辑
        // ═══════════════════════════════════════════════════════════

        val service = refreshService
        val isAntiSleepActive = service?.isAntiSleepMode() == true

        // 初始化防休眠按钮状态
        updateAntiSleepButtonState(sheetBinding, isAntiSleepActive)

        // 如果当前是防休眠模式，显示当前间隔
        if (isAntiSleepActive) {
            val task = service?.getCurrentTask() as? RefreshTask.AntiSleep
            task?.let {
                sheetBinding.editTextAntiSleepInterval.setText(it.intervalSeconds.toString())
            }
        }

        // 防休眠按钮点击事件
        sheetBinding.buttonAntiSleep.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl.isNullOrBlank()
                || currentUrl == "about:blank"
                || currentUrl.startsWith("file:")) {
                showToast(getString(R.string.toast_anti_sleep_need_page))
                return@setOnClickListener
            }

            if (refreshService?.isAntiSleepMode() == true) {
                // 当前是防休眠模式，关闭它
                stopAntiSleepMode()
                updateAntiSleepButtonState(sheetBinding, false)
                sheetBinding.containerCurrentTask.visibility = View.GONE
                sheetBinding.buttonCancelTask.visibility = View.GONE
                showToast(getString(R.string.toast_anti_sleep_stopped))
            } else {
                // 开启防休眠模式
                val intervalText = sheetBinding.editTextAntiSleepInterval.text.toString()
                val intervalSeconds = intervalText.toLongOrNull()

                if (intervalSeconds == null || intervalSeconds < 1 || intervalSeconds > 9999) {
                    showToast(getString(R.string.toast_anti_sleep_invalid_interval))
                    return@setOnClickListener
                }

                // 先停止现有任务（互斥）
                refreshService?.stopTask()

                // 启动防休眠模式
                startAntiSleepMode(intervalSeconds)
                updateAntiSleepButtonState(sheetBinding, true)

                // 更新当前任务显示
                updateCurrentTaskDisplay(sheetBinding)

                showToast(getString(R.string.toast_anti_sleep_started))
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 间隔/定时刷新 UI 逻辑
        // ═══════════════════════════════════════════════════════════

        sheetBinding.buttonCustomInterval.setOnClickListener {
            showCustomIntervalDialog { seconds ->
                customIntervalSeconds = seconds
                sheetBinding.radioInterval.isChecked = true
                showToast("已设置自定义间隔: ${getIntervalDisplayText(seconds)}")
            }
        }

        sheetBinding.buttonPickTime.setOnClickListener {
            showTimePicker(sheetBinding)
        }

        sheetBinding.radioInterval.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sheetBinding.radioScheduled.isChecked = false
                sheetBinding.containerInterval.alpha = 1f
                sheetBinding.buttonCustomInterval.alpha = 1f
                sheetBinding.containerScheduled.alpha = 0.5f
            }
        }

        sheetBinding.radioScheduled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sheetBinding.radioInterval.isChecked = false
                sheetBinding.containerInterval.alpha = 0.5f
                sheetBinding.buttonCustomInterval.alpha = 0.5f
                sheetBinding.containerScheduled.alpha = 1f
                customIntervalSeconds = null
            }
        }

        sheetBinding.radioInterval.isChecked = true

        // 显示当前任务状态
        updateCurrentTaskDisplay(sheetBinding)

        // 取消任务按钮
        sheetBinding.buttonCancelTask.setOnClickListener {
            refreshService?.stopTask()
            stopRefreshService()
            updateAntiSleepButtonState(sheetBinding, false)
            sheetBinding.containerCurrentTask.visibility = View.GONE
            sheetBinding.buttonCancelTask.visibility = View.GONE
            showToast(getString(R.string.toast_refresh_cancelled))
        }

        // 确认按钮（用于间隔/定时刷新）
        sheetBinding.buttonConfirm.setOnClickListener {
            when {
                sheetBinding.radioInterval.isChecked -> {
                    val seconds = customIntervalSeconds
                        ?: intervalValues[sheetBinding.spinnerInterval.selectedItemPosition].toLong()

                    if (seconds <= 0) {
                        showToast(getString(R.string.toast_invalid_interval))
                        return@setOnClickListener
                    }

                    // 停止防休眠模式（互斥）
                    if (refreshService?.isAntiSleepMode() == true) {
                        refreshService?.stopTask()
                        updateAntiSleepButtonState(sheetBinding, false)
                    }

                    startIntervalRefresh(seconds)
                    showToast(getString(R.string.toast_refresh_started))
                    bottomSheet.dismiss()
                }
                sheetBinding.radioScheduled.isChecked -> {
                    val time = selectedScheduledTime
                    if (time == null) {
                        showToast(getString(R.string.toast_refresh_select_time))
                    } else {
                        // 停止防休眠模式（互斥）
                        if (refreshService?.isAntiSleepMode() == true) {
                            refreshService?.stopTask()
                            updateAntiSleepButtonState(sheetBinding, false)
                        }

                        startScheduledRefresh(time.timeInMillis)
                        showToast(getString(R.string.toast_refresh_started))
                        bottomSheet.dismiss()
                    }
                }
                else -> {
                    showToast(getString(R.string.toast_refresh_select_mode))
                }
            }
        }

        refreshBottomSheet = bottomSheet
        bottomSheet.show()
    }

    /**
     * 更新防休眠按钮的视觉状态
     */
    private fun updateAntiSleepButtonState(
        sheetBinding: BottomSheetRefreshBinding,
        isActive: Boolean
    ) {
        sheetBinding.buttonAntiSleep.isActivated = isActive
        sheetBinding.buttonAntiSleep.isSelected = isActive
        sheetBinding.buttonAntiSleep.text = if (isActive) {
            getString(R.string.anti_sleep_on)
        } else {
            getString(R.string.anti_sleep_off)
        }
    }

    /**
     * 更新当前任务显示区域
     */
    private fun updateCurrentTaskDisplay(sheetBinding: BottomSheetRefreshBinding) {
        val service = refreshService
        if (service != null && service.hasActiveTask()) {
            sheetBinding.containerCurrentTask.visibility = View.VISIBLE
            sheetBinding.buttonCancelTask.visibility = View.VISIBLE

            val task = service.getCurrentTask()
            val remaining = service.getRemainingSeconds()

            when (task) {
                is RefreshTask.Interval -> {
                    sheetBinding.textViewCurrentTaskIcon.text = getString(R.string.refresh_current_task_icon)
                    val intervalText = getIntervalDisplayText(task.intervalSeconds)
                    sheetBinding.textViewCurrentTask.text = getString(
                        R.string.refresh_current_task_interval,
                        intervalText,
                        formatSeconds(remaining)
                    )
                }
                is RefreshTask.Scheduled -> {
                    sheetBinding.textViewCurrentTaskIcon.text = getString(R.string.refresh_current_task_icon)
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    sheetBinding.textViewCurrentTask.text = getString(
                        R.string.refresh_current_task_scheduled,
                        timeFormat.format(Date(task.targetTimeMillis))
                    )
                }
                is RefreshTask.AntiSleep -> {
                    sheetBinding.textViewCurrentTaskIcon.text = getString(R.string.anti_sleep_current_task_icon)
                    sheetBinding.textViewCurrentTask.text = getString(
                        R.string.refresh_current_task_anti_sleep,
                        task.intervalSeconds.toString(),
                        formatSeconds(remaining)
                    )
                }
                null -> {
                    sheetBinding.containerCurrentTask.visibility = View.GONE
                    sheetBinding.buttonCancelTask.visibility = View.GONE
                }
            }
        } else {
            sheetBinding.containerCurrentTask.visibility = View.GONE
            sheetBinding.buttonCancelTask.visibility = View.GONE
        }
    }

    private fun showCustomIntervalDialog(onConfirm: (Long) -> Unit) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_custom_interval, null)

        val editHours = dialogView.findViewById<EditText>(R.id.editTextHours)
        val editMinutes = dialogView.findViewById<EditText>(R.id.editTextMinutes)
        val editSeconds = dialogView.findViewById<EditText>(R.id.editTextSeconds)
        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonCancel)
        val buttonOk = dialogView.findViewById<Button>(R.id.buttonOk)

        editHours.setText("0")
        editMinutes.setText("5")
        editSeconds.setText("0")

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonCancel.setOnClickListener {
            dialog.dismiss()
        }

        buttonOk.setOnClickListener {
            val hours = editHours.text.toString().toIntOrNull() ?: 0
            val minutes = editMinutes.text.toString().toIntOrNull() ?: 0
            val seconds = editSeconds.text.toString().toIntOrNull() ?: 0

            val totalSeconds = (hours * 3600L) + (minutes * 60L) + seconds

            if (totalSeconds <= 0) {
                showToast(getString(R.string.toast_invalid_interval))
                return@setOnClickListener
            }

            onConfirm(totalSeconds)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTimePicker(sheetBinding: BottomSheetRefreshBinding) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        TimePickerDialog(
            this,
            R.style.Theme_WebSnap_TimePicker,
            { _, selectedHour, selectedMinute ->
                showSecondPicker(sheetBinding, selectedHour, selectedMinute)
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun showSecondPicker(
        sheetBinding: BottomSheetRefreshBinding,
        hour: Int,
        minute: Int
    ) {
        val seconds = arrayOf("00", "15", "30", "45")
        val secondValues = intArrayOf(0, 15, 30, 45)

        AlertDialog.Builder(this, R.style.Theme_WebSnap_SecondPicker)
            .setTitle("选择秒数")
            .setItems(seconds) { _, which ->
                val second = secondValues[which]

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, second)
                    set(Calendar.MILLISECOND, 0)
                }

                selectedScheduledTime = calendar

                val timeStr = String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    hour, minute, second
                )
                sheetBinding.buttonPickTime.text = timeStr
            }
            .show()
    }

    private fun startIntervalRefresh(intervalSeconds: Long) {
        val intent = Intent(this, RefreshService::class.java).apply {
            action = RefreshService.ACTION_START_TASK
            putExtra(RefreshService.EXTRA_TASK_TYPE, RefreshService.TASK_TYPE_INTERVAL)
            putExtra(RefreshService.EXTRA_INTERVAL_SECONDS, intervalSeconds)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startScheduledRefresh(targetTimeMillis: Long) {
        val intent = Intent(this, RefreshService::class.java).apply {
            action = RefreshService.ACTION_START_TASK
            putExtra(RefreshService.EXTRA_TASK_TYPE, RefreshService.TASK_TYPE_SCHEDULED)
            putExtra(RefreshService.EXTRA_TARGET_TIME, targetTimeMillis)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * 启动防休眠模式
     */
    private fun startAntiSleepMode(intervalSeconds: Long) {
        val intent = Intent(this, RefreshService::class.java).apply {
            action = RefreshService.ACTION_START_TASK
            putExtra(RefreshService.EXTRA_TASK_TYPE, RefreshService.TASK_TYPE_ANTI_SLEEP)
            putExtra(RefreshService.EXTRA_INTERVAL_SECONDS, intervalSeconds)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * 停止防休眠模式
     */
    private fun stopAntiSleepMode() {
        refreshService?.stopTask()
        stopRefreshService()
        updateRefreshButtonState()
    }

    private fun stopRefreshService() {
        val intent = Intent(this, RefreshService::class.java).apply {
            action = RefreshService.ACTION_STOP_TASK
        }
        startService(intent)
        updateRefreshButtonState()
    }

    private fun getIntervalDisplayText(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 && minutes > 0 && secs > 0 -> "${hours}时${minutes}分${secs}秒"
            hours > 0 && minutes > 0 -> "${hours}时${minutes}分"
            hours > 0 && secs > 0 -> "${hours}时${secs}秒"
            hours > 0 -> "${hours}小时"
            minutes > 0 && secs > 0 -> "${minutes}分${secs}秒"
            minutes > 0 -> "${minutes}分钟"
            else -> "${secs}秒"
        }
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RefreshService.RefreshCallback 实现
    // ═══════════════════════════════════════════════════════════════

    override fun onTaskStarted(task: RefreshTask) {
        runOnUiThread {
            updateRefreshButtonState()
        }
    }

    override fun onCountdownTick(remainingSeconds: Long) {
        runOnUiThread {
            val task = refreshService?.getCurrentTask()
            when (task) {
                is RefreshTask.Interval -> {
                    binding.buttonRefresh.text = getString(
                        R.string.button_refresh_countdown,
                        formatSeconds(remainingSeconds)
                    )
                }
                is RefreshTask.Scheduled -> {}
                else -> {}
            }
        }
    }

    override fun onRefreshTriggered() {
        runOnUiThread {
            binding.buttonRefresh.text = getString(R.string.button_refreshing)
            binding.webView.reload()
        }
    }

    override fun onTaskCancelled() {
        runOnUiThread {
            binding.buttonRefresh.isActivated = false
            binding.buttonRefresh.text = getString(R.string.button_refresh_default)
        }
    }

    /**
     * 防休眠倒计时更新
     */
    override fun onAntiSleepTick(remainingSeconds: Long) {
        runOnUiThread {
            binding.buttonRefresh.text = getString(
                R.string.button_anti_sleep_active,
                formatSeconds(remainingSeconds)
            )
        }
    }

    /**
     * 防休眠心跳触发 - 注入 JavaScript
     */
    override fun onAntiSleepHeartbeat() {
        runOnUiThread {
            // 注入心跳脚本
            binding.webView.evaluateJavascript(antiSleepHeartbeatScript, null)
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
        desktopModeAppliedForCurrentPage = false
        binding.webView.loadUrl(url)
    }

    // ═══════════════════════════════════════════════════════════════
    // 截图功能
    // ═══════════════════════════════════════════════════════════════

    /**
     * 计算截图所需的缩放比例
     * 在 PC 模式下，webView.scale 可能被 viewport 的 initial-scale 污染
     * 需要使用实际的宽度比例来计算
     */
    private fun getEffectiveScale(): Float {
        return if (isPcMode) {
            // PC 模式：viewport 宽度是 desktopViewportWidth (1024)
            // 实际显示宽度是 webView.width
            // 真正的缩放比例 = 实际宽度 / 虚拟视口宽度
            binding.webView.width.toFloat() / desktopViewportWidth.toFloat()
        } else {
            // 普通模式：直接使用 WebView 报告的缩放比例
            @Suppress("DEPRECATION")
            binding.webView.scale
        }
    }

    private fun captureVisibleArea() {
        if (!isPageLoaded) {
            showToast(getString(R.string.toast_page_not_loaded))
            return
        }

        binding.buttonCapture.isEnabled = false

        binding.webView.post {
            try {
                val bitmap = captureVisibleBitmap()

                if (bitmap != null) {
                    CropBitmapHolder.set(bitmap, isFullPage = false)
                    showToast(getString(R.string.toast_capture_visible))
                    startActivity(Intent(this, CropActivity::class.java))
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
            }
        }
    }

    private fun captureWholePage() {
        if (!isPageLoaded) {
            showToast(getString(R.string.toast_page_not_loaded))
            return
        }

        binding.buttonCapture.isEnabled = false

        binding.webView.post {
            try {
                val bitmap = captureFullPageBitmap()

                if (bitmap != null) {
                    CropBitmapHolder.set(bitmap, isFullPage = true)
                    showToast(getString(R.string.toast_capture_fullpage))
                    startActivity(Intent(this, CropActivity::class.java))
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
            }
        }
    }

    private fun captureVisibleBitmap(): Bitmap? {
        val webView = binding.webView

        val width = webView.width
        val height = webView.height

        if (width <= 0 || height <= 0) {
            return null
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)

        return bitmap
    }

    private fun captureFullPageBitmap(): Bitmap? {
        val webView = binding.webView

        // ★ 关键修复：使用正确的缩放比例
        val scale = getEffectiveScale()
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
