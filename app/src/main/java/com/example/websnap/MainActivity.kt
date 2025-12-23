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
import android.os.IBinder
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.websnap.databinding.ActivityMainBinding
import com.example.websnap.databinding.BottomSheetBookmarksBinding
import com.example.websnap.databinding.BottomSheetRefreshBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    
    /** viewport 中设置的 initial-scale 值，用于截图时的高度补偿计算 */
    private val viewportInitialScale = 0.67f
    
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
                    var content = 'width=' + desktopWidth + ', initial-scale=$viewportInitialScale, minimum-scale=0.1, maximum-scale=10';
                    
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
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            refreshBottomSheet?.isShowing == true -> refreshBottomSheet?.dismiss()
            bookmarkBottomSheet?.isShowing == true -> bookmarkBottomSheet?.dismiss()
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部逻辑：文件、权限、WebView
    // ═══════════════════════════════════════════════════════════════
    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return
        if (resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return
        }
        val results: Array<Uri>? = when {
            data?.data != null -> arrayOf(data.data!!)
            data?.clipData != null -> {
                val clipData = data.clipData!!
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            }
            else -> null
        }
        callback.onReceiveValue(results)
    }

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
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
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

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val errorMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        error?.description?.toString() ?: "Unknown error"
                    } else "Load failed"
                    showToast(getString(R.string.error_page_message, errorMsg))
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase() ?: return false
                desktopModeAppliedForCurrentPage = false
                return when {
                    scheme in webViewSchemes -> false
                    scheme in systemSchemes -> { handleSystemScheme(url); true }
                    else -> true
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
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
                view.postDelayed({ if (isPcMode && isPageLoaded) view.reload() }, 300)
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) binding.progressBar.visibility = View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                currentPageTitle = title ?: ""
            }

            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
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
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (!hasCameraPermission()) permissionsToRequest.add(Manifest.permission.CAMERA)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (!hasMicrophonePermission()) permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    if (permissionsToRequest.isEmpty()) {
                        permRequest.grant(requestedResources)
                        pendingPermissionRequest = null
                    } else {
                        ActivityCompat.requestPermissions(this@MainActivity, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CAMERA)
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
    // 权限检查与结果处理
    // ═══════════════════════════════════════════════════════════════
    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CAMERA, PERMISSION_REQUEST_MICROPHONE -> {
                pendingPermissionRequest?.let { request ->
                    val grantedResources = mutableListOf<String>()
                    for (resource in request.resources) {
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (hasCameraPermission()) grantedResources.add(resource)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (hasMicrophonePermission()) grantedResources.add(resource)
                        }
                    }
                    if (grantedResources.isNotEmpty()) request.grant(grantedResources.toTypedArray()) else request.deny()
                    pendingPermissionRequest = null
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 事件监听与功能入口
    // ═══════════════════════════════════════════════════════════════
    private fun setupListeners() {
        binding.buttonGo.setOnClickListener { loadUrl() }
        binding.editTextUrl.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isEnterKey || actionId == EditorInfo.IME_ACTION_GO) { loadUrl(); true } else false
        }
        binding.buttonBookmark.setOnClickListener { toggleBookmark() }
        binding.buttonBookmark.setOnLongClickListener { showBookmarkSheet(); true }
        binding.buttonPcMode.setOnClickListener { togglePcMode() }
        binding.buttonBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.buttonForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.buttonHome.setOnClickListener { loadHomePage() }
        binding.buttonRefresh.setOnClickListener { performRefresh() }
        binding.buttonRefresh.setOnLongClickListener { showRefreshSheet(); true }
        binding.buttonCapture.setOnClickListener { captureVisibleArea() }
        binding.buttonCapture.setOnLongClickListener { captureWholePage(); true }
    }

    private fun loadHomePage() { binding.editTextUrl.setText(""); binding.webView.loadUrl(homePageUrl) }
    private fun updateNavigationButtons() {
        binding.buttonBack.isEnabled = binding.webView.canGoBack()
        binding.buttonForward.isEnabled = binding.webView.canGoForward()
    }

    private fun handleSystemScheme(url: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, url).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) { e.printStackTrace() }
    }

    // ═══════════════════════════════════════════════════════════════
    // 书签、PC 模式、刷新逻辑
    // ═══════════════════════════════════════════════════════════════
    private fun updateBookmarkButton() {
        val currentUrl = binding.webView.url
        val isBookmarked = if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank" && !currentUrl.startsWith("file:")) bookmarkManager.contains(currentUrl) else false
        binding.buttonBookmark.text = if (isBookmarked) getString(R.string.button_bookmark_filled) else getString(R.string.button_bookmark_empty)
    }

    private fun toggleBookmark() {
        val currentUrl = binding.webView.url
        if (currentUrl.isNullOrBlank() || currentUrl == "about:blank" || currentUrl.startsWith("file:")) {
            showToast(getString(R.string.toast_bookmark_need_page)); return
        }
        if (bookmarkManager.contains(currentUrl)) {
            bookmarkManager.remove(currentUrl); showToast(getString(R.string.toast_bookmark_removed))
        } else {
            bookmarkManager.add(Bookmark(title = currentPageTitle.ifBlank { currentUrl }, url = currentUrl))
            showToast(getString(R.string.toast_bookmark_added))
        }
        updateBookmarkButton()
    }

    private fun showBookmarkSheet() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetBookmarksBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)
        val adapter = BookmarkAdapter(
            onItemClick = { bookmark -> bottomSheet.dismiss(); binding.webView.loadUrl(bookmark.url) },
            onDeleteClick = { bookmark, position ->
                bookmarkManager.removeAt(position)
                (sheetBinding.recyclerViewBookmarks.adapter as? BookmarkAdapter)?.removeAt(position)
                showToast(getString(R.string.toast_bookmark_deleted, bookmark.title))
                if (bookmarkManager.isEmpty()) { sheetBinding.recyclerViewBookmarks.visibility = View.GONE; sheetBinding.emptyStateContainer.visibility = View.VISIBLE }
                updateBookmarkButton()
            }
        )
        sheetBinding.recyclerViewBookmarks.layoutManager = LinearLayoutManager(this)
        sheetBinding.recyclerViewBookmarks.adapter = adapter
        val bookmarks = bookmarkManager.getAll()
        if (bookmarks.isEmpty()) { sheetBinding.recyclerViewBookmarks.visibility = View.GONE; sheetBinding.emptyStateContainer.visibility = View.VISIBLE }
        else { sheetBinding.recyclerViewBookmarks.visibility = View.VISIBLE; sheetBinding.emptyStateContainer.visibility = View.GONE; adapter.submitList(bookmarks) }
        bottomSheet.show()
    }

    private fun togglePcMode() {
        isPcMode = !isPcMode; binding.buttonPcMode.isSelected = isPcMode
        binding.webView.settings.apply {
            userAgentString = if (isPcMode) desktopUserAgent else mobileUserAgent
            useWideViewPort = true
            loadWithOverviewMode = !isPcMode
        }
        showToast(getString(if (isPcMode) R.string.toast_pc_mode_on else R.string.toast_pc_mode_off))
        if (!binding.webView.url.isNullOrBlank() && binding.webView.url != "about:blank" && !binding.webView.url!!.startsWith("file:")) binding.webView.reload()
    }

    private fun performRefresh() { if (binding.webView.url.isNullOrBlank() || binding.webView.url == "about:blank") showToast(getString(R.string.toast_refresh_need_page)) else binding.webView.reload() }

    private fun updateRefreshButtonState() {
        val service = refreshService
        if (service != null && service.hasActiveTask()) {
            binding.buttonRefresh.isActivated = true
            val task = service.getCurrentTask()
            val remaining = service.getRemainingSeconds()
            binding.buttonRefresh.text = when (task) {
                is RefreshTask.Interval -> getString(R.string.button_refresh_countdown, formatSeconds(remaining))
                is RefreshTask.Scheduled -> getString(R.string.button_refresh_scheduled, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(task.targetTimeMillis)))
                null -> { binding.buttonRefresh.isActivated = false; getString(R.string.button_refresh_default) }
            }
        } else { binding.buttonRefresh.isActivated = false; binding.buttonRefresh.text = getString(R.string.button_refresh_default) }
    }

    private fun showRefreshSheet() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetRefreshBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)
        sheetBinding.spinnerInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resources.getStringArray(R.array.interval_options)).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sheetBinding.spinnerInterval.setSelection(4)
        selectedScheduledTime = null; customIntervalSeconds = null
        sheetBinding.buttonCustomInterval.setOnClickListener { showCustomIntervalDialog { seconds -> customIntervalSeconds = seconds; sheetBinding.radioInterval.isChecked = true; showToast("已设置自定义间隔") } }
        sheetBinding.buttonPickTime.setOnClickListener { showTimePicker(sheetBinding) }
        sheetBinding.radioInterval.setOnCheckedChangeListener { _, isChecked -> if (isChecked) { sheetBinding.radioScheduled.isChecked = false; sheetBinding.containerInterval.alpha = 1f; sheetBinding.containerScheduled.alpha = 0.5f } }
        sheetBinding.radioScheduled.setOnCheckedChangeListener { _, isChecked -> if (isChecked) { sheetBinding.radioInterval.isChecked = false; sheetBinding.containerInterval.alpha = 0.5f; sheetBinding.containerScheduled.alpha = 1f } }
        sheetBinding.radioInterval.isChecked = true
        refreshService?.let { if (it.hasActiveTask()) { sheetBinding.containerCurrentTask.visibility = View.VISIBLE; sheetBinding.buttonCancelTask.visibility = View.VISIBLE } }
        sheetBinding.buttonCancelTask.setOnClickListener { refreshService?.stopTask(); stopRefreshService(); showToast(getString(R.string.toast_refresh_cancelled)); bottomSheet.dismiss() }
        sheetBinding.buttonConfirm.setOnClickListener {
            if (sheetBinding.radioInterval.isChecked) {
                val seconds = customIntervalSeconds ?: resources.getIntArray(R.array.interval_values)[sheetBinding.spinnerInterval.selectedItemPosition].toLong()
                startIntervalRefresh(seconds); showToast(getString(R.string.toast_refresh_started)); bottomSheet.dismiss()
            } else if (sheetBinding.radioScheduled.isChecked) {
                selectedScheduledTime?.let { startScheduledRefresh(it.timeInMillis); showToast(getString(R.string.toast_refresh_started)); bottomSheet.dismiss() } ?: showToast(getString(R.string.toast_refresh_select_time))
            }
        }
        bottomSheet.show()
    }

    private fun showCustomIntervalDialog(onConfirm: (Long) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_custom_interval, null)
        val h = view.findViewById<EditText>(R.id.editTextHours); val m = view.findViewById<EditText>(R.id.editTextMinutes); val s = view.findViewById<EditText>(R.id.editTextSeconds)
        h.setText("0"); m.setText("5"); s.setText("0")
        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.buttonOk).setOnClickListener {
            val total = (h.text.toString().toIntOrNull() ?: 0) * 3600L + (m.text.toString().toIntOrNull() ?: 0) * 60L + (s.text.toString().toIntOrNull() ?: 0)
            if (total > 0) { onConfirm(total); dialog.dismiss() } else showToast(getString(R.string.toast_invalid_interval))
        }
        dialog.show()
    }

    private fun showTimePicker(sheetBinding: BottomSheetRefreshBinding) {
        val c = Calendar.getInstance()
        TimePickerDialog(this, R.style.Theme_WebSnap_TimePicker, { _, hour, min ->
            val seconds = arrayOf("00", "15", "30", "45"); val secondValues = intArrayOf(0, 15, 30, 45)
            AlertDialog.Builder(this, R.style.Theme_WebSnap_SecondPicker).setTitle("选择秒数").setItems(seconds) { _, which ->
                selectedScheduledTime = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min); set(Calendar.SECOND, secondValues[which]); set(Calendar.MILLISECOND, 0) }
                sheetBinding.buttonPickTime.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hour, min, secondValues[which])
            }.show()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun startIntervalRefresh(s: Long) { val i = Intent(this, RefreshService::class.java).apply { action = RefreshService.ACTION_START_TASK; putExtra(RefreshService.EXTRA_TASK_TYPE, "interval"); putExtra(RefreshService.EXTRA_INTERVAL_SECONDS, s) }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i) }
    private fun startScheduledRefresh(t: Long) { val i = Intent(this, RefreshService::class.java).apply { action = RefreshService.ACTION_START_TASK; putExtra(RefreshService.EXTRA_TASK_TYPE, "scheduled"); putExtra(RefreshService.EXTRA_TARGET_TIME, t) }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i) }
    private fun stopRefreshService() { startService(Intent(this, RefreshService::class.java).apply { action = RefreshService.ACTION_STOP_TASK }); updateRefreshButtonState() }
    private fun getIntervalDisplayText(s: Long): String = String.format(Locale.getDefault(), "%d时%d分%d秒", s / 3600, (s % 3600) / 60, s % 60)
    private fun formatSeconds(s: Long): String = if (s / 3600 > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format(Locale.getDefault(), "%02d:%02d", (s % 3600) / 60, s % 60)

    override fun onTaskStarted(task: RefreshTask) { runOnUiThread { updateRefreshButtonState() } }
    override fun onCountdownTick(s: Long) { runOnUiThread { if (refreshService?.getCurrentTask() is RefreshTask.Interval) binding.buttonRefresh.text = getString(R.string.button_refresh_countdown, formatSeconds(s)) } }
    override fun onRefreshTriggered() { runOnUiThread { binding.buttonRefresh.text = getString(R.string.button_refreshing); binding.webView.reload() } }
    override fun onTaskCancelled() { runOnUiThread { binding.buttonRefresh.isActivated = false; binding.buttonRefresh.text = getString(R.string.button_refresh_default) } }

    private fun loadUrl() {
        val input = binding.editTextUrl.text.toString().trim()
        if (input.isEmpty()) { showToast(getString(R.string.toast_url_empty)); return }
        val url = if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
        hideKeyboard(); desktopModeAppliedForCurrentPage = false; binding.webView.loadUrl(url)
    }

    // ═══════════════════════════════════════════════════════════════
    // 截图功能核心（已整合 Claude 的最新 12.5% 补偿逻辑）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 计算截图所需的缩放比例
     * 整合了 Claude 的补偿因子公式：1 + (1 - initial-scale) * 0.38
     * 用于彻底消除 PC 模式下 Header 区域 10-15% 的压扁残余。
     */
    private fun getEffectiveScale(): Float {
        return if (isPcMode) {
            // 基础宽度比例
            val widthRatio = binding.webView.width.toFloat() / desktopViewportWidth.toFloat()
            // 补偿因子计算：抵消 initial-scale=0.67 对 contentHeight 的隐式影响
            val compensationFactor = 1f + (1f - viewportInitialScale) * 0.38f
            widthRatio * compensationFactor
        } else {
            @Suppress("DEPRECATION")
            binding.webView.scale
        }
    }

    private fun captureVisibleArea() {
        if (!isPageLoaded) { showToast(getString(R.string.toast_page_not_loaded)); return }
        binding.buttonCapture.isEnabled = false
        binding.webView.post {
            try {
                captureVisibleBitmap()?.let { CropBitmapHolder.set(it, false); showToast(getString(R.string.toast_capture_visible)); startActivity(Intent(this, CropActivity::class.java)) }
            } catch (e: Exception) { showToast(getString(R.string.toast_capture_failed)) }
            finally { binding.buttonCapture.isEnabled = true }
        }
    }

    private fun captureWholePage() {
        if (!isPageLoaded) { showToast(getString(R.string.toast_page_not_loaded)); return }
        binding.buttonCapture.isEnabled = false
        binding.webView.post {
            try {
                captureFullPageBitmap()?.let { CropBitmapHolder.set(it, true); showToast(getString(R.string.toast_capture_fullpage)); startActivity(Intent(this, CropActivity::class.java)) }
            } catch (e: OutOfMemoryError) { showToast(getString(R.string.toast_memory_insufficient)); System.gc() }
            catch (e: Exception) { showToast(getString(R.string.toast_capture_failed)) }
            finally { binding.buttonCapture.isEnabled = true }
        }
    }

    private fun captureVisibleBitmap(): Bitmap? {
        if (binding.webView.width <= 0 || binding.webView.height <= 0) return null
        val bitmap = Bitmap.createBitmap(binding.webView.width, binding.webView.height, Bitmap.Config.ARGB_8888)
        binding.webView.draw(Canvas(bitmap))
        return bitmap
    }

    private fun captureFullPageBitmap(): Bitmap? {
        val webView = binding.webView
        val scale = getEffectiveScale() // 使用修正后的 12.5% 补偿比例
        val contentWidth = webView.width
        var contentHeight = (webView.contentHeight * scale).toInt()
        
        if (contentWidth <= 0 || contentHeight <= 0) return null
        
        var wasTruncated = false
        if (contentHeight > maxCaptureHeight) { contentHeight = maxCaptureHeight; wasTruncated = true }

        // 内存安全检查
        if (contentWidth.toLong() * contentHeight.toLong() * 4L > (Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory()) * 0.8) {
            showToast(getString(R.string.toast_memory_insufficient)); return null
        }

        val originalLayerType = webView.layerType
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val bitmap: Bitmap?
        try {
            bitmap = Bitmap.createBitmap(contentWidth, contentHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap!!)
            
            // 应用缩放比例到画布，确保绘制比例与计算高度精准匹配
            canvas.scale(scale, scale)
            
            webView.draw(canvas)
        } catch (e: Exception) { e.printStackTrace(); return null }
        finally { webView.setLayerType(originalLayerType, null) }

        if (wasTruncated) showToast(getString(R.string.toast_page_too_long, maxCaptureHeight))
        return bitmap
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        binding.editTextUrl.clearFocus()
    }

    private fun showToast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}

