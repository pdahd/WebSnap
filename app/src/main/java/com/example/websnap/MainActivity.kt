package com.example.websnap

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
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
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.websnap.databinding.ActivityMainBinding
import com.example.websnap.databinding.BottomSheetBookmarksBinding
import com.example.websnap.databinding.BottomSheetRefreshBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    // ═══════════════════════════════════════════════════════════════
    // 权限请求码
    // ═══════════════════════════════════════════════════════════════

    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 1001
        private const val PERMISSION_REQUEST_MICROPHONE = 1002
        private const val PERMISSION_REQUEST_STORAGE = 1003
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
    private var cameraPhotoUri: Uri? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

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
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.enableSlowWholeDocumentDraw()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarkManager = BookmarkManager.getInstance(this)

        setupFileChooserLauncher()
        setupWebView()
        setupListeners()
        updateNavigationButtons()
        updateBookmarkButton()
        updatePcModeButton()

        // 启动时加载主页
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
    // 文件选择器初始化
    // ═══════════════════════════════════════════════════════════════

    private fun setupFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleFileChooserResult(result.resultCode, result.data)
        }
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        if (fileUploadCallback == null) return

        val results: Array<Uri>? = when {
            resultCode != Activity.RESULT_OK -> null
            data?.data != null -> arrayOf(data.data!!)
            data?.clipData != null -> {
                val clipData = data.clipData!!
                Array(clipData.itemCount) { clipData.getItemAt(it).uri }
            }
            cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
            else -> null
        }

        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
        cameraPhotoUri = null
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

                // 启用媒体播放
                mediaPlaybackRequiresUserGesture = false
            }

            mobileUserAgent = settings.userAgentString
            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()
        }

        // Cookie 管理配置
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)
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

            // ═══════════════════════════════════════════════════════════
            // 文件上传处理
            // ═══════════════════════════════════════════════════════════

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 取消之前的回调
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                // 检查并请求存储权限
                if (!hasStoragePermission()) {
                    requestStoragePermission()
                    return true
                }

                showFileChooserDialog(fileChooserParams)
                return true
            }

            // ═══════════════════════════════════════════════════════════
            // WebView 权限请求处理（相机、麦克风）
            // ═══════════════════════════════════════════════════════════

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
                        // 所有权限已授予
                        permRequest.grant(requestedResources)
                        pendingPermissionRequest = null
                    } else {
                        // 请求权限
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
    // 文件选择对话框
    // ═══════════════════════════════════════════════════════════════

    private fun showFileChooserDialog(params: WebChromeClient.FileChooserParams?) {
        val acceptTypes = params?.acceptTypes ?: arrayOf("*/*")
        val isImageRequest = acceptTypes.any { it.startsWith("image/") }
        val isVideoRequest = acceptTypes.any { it.startsWith("video/") }
        val isMediaRequest = isImageRequest || isVideoRequest

        val options = mutableListOf<String>()
        val intents = mutableListOf<Intent>()

        // 相机选项（仅当请求图片或视频时）
        if (isMediaRequest || acceptTypes.contains("*/*")) {
            if (hasCameraPermission()) {
                options.add(getString(R.string.file_chooser_camera))
                intents.add(createCameraIntent())
            }
        }

        // 相册选项
        options.add(getString(R.string.file_chooser_gallery))
        intents.add(createGalleryIntent(acceptTypes))

        // 文件选项
        options.add(getString(R.string.file_chooser_file))
        intents.add(createFileIntent(acceptTypes, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE))

        if (options.size == 1) {
            // 只有一个选项，直接打开
            fileChooserLauncher.launch(intents[0])
        } else {
            // 多个选项，显示对话框
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.file_chooser_title))
                .setItems(options.toTypedArray()) { _, which ->
                    fileChooserLauncher.launch(intents[which])
                }
                .setOnCancelListener {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                }
                .show()
        }
    }

    private fun createCameraIntent(): Intent {
        val photoFile = createImageFile()
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
        }
    }

    private fun createGalleryIntent(acceptTypes: Array<String>): Intent {
        return Intent(Intent.ACTION_PICK).apply {
            type = when {
                acceptTypes.any { it.startsWith("image/") } -> "image/*"
                acceptTypes.any { it.startsWith("video/") } -> "video/*"
                else -> "*/*"
            }
        }
    }

    private fun createFileIntent(acceptTypes: Array<String>, allowMultiple: Boolean): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptTypes.isNotEmpty() && acceptTypes[0] != "") {
                acceptTypes[0]
            } else {
                "*/*"
            }
            if (acceptTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: cacheDir
        return File.createTempFile("CAMERA_${timestamp}_", ".jpg", storageDir)
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

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用细粒度权限
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 及以下
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions,
            PERMISSION_REQUEST_STORAGE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_CAMERA, PERMISSION_REQUEST_MICROPHONE -> {
                // 处理 WebView 权限请求结果
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
                // 处理存储权限请求结果
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 权限授予，重新触发文件选择
                    showFileChooserDialog(null)
                } else {
                    showToast(getString(R.string.toast_permission_storage_denied))
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                }
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

        val service = refreshService
        if (service != null && service.hasActiveTask()) {
            sheetBinding.containerCurrentTask.visibility = View.VISIBLE
            sheetBinding.buttonCancelTask.visibility = View.VISIBLE

            val task = service.getCurrentTask()
            val remaining = service.getRemainingSeconds()

            when (task) {
                is RefreshTask.Interval -> {
                    val intervalText = getIntervalDisplayText(task.intervalSeconds)
                    sheetBinding.textViewCurrentTask.text = getString(
                        R.string.refresh_current_task_interval,
                        intervalText,
                        formatSeconds(remaining)
                    )
                }
                is RefreshTask.Scheduled -> {
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    sheetBinding.textViewCurrentTask.text = getString(
                        R.string.refresh_current_task_scheduled,
                        timeFormat.format(Date(task.targetTimeMillis))
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

        sheetBinding.buttonCancelTask.setOnClickListener {
            refreshService?.stopTask()
            stopRefreshService()
            showToast(getString(R.string.toast_refresh_cancelled))
            bottomSheet.dismiss()
        }

        sheetBinding.buttonConfirm.setOnClickListener {
            when {
                sheetBinding.radioInterval.isChecked -> {
                    val seconds = customIntervalSeconds
                        ?: intervalValues[sheetBinding.spinnerInterval.selectedItemPosition].toLong()

                    if (seconds <= 0) {
                        showToast(getString(R.string.toast_invalid_interval))
                        return@setOnClickListener
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
            putExtra(RefreshService.EXTRA_TASK_TYPE, "interval")
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
            putExtra(RefreshService.EXTRA_TASK_TYPE, "scheduled")
            putExtra(RefreshService.EXTRA_TARGET_TIME, targetTimeMillis)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
                null -> {}
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
