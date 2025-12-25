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
import com.google.android.material.bottomsheet.BottomSheetBehavior
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

    private val homePageUrl = "file:///android_asset/home.html"
    private val maxCaptureHeight = 20000
    private val desktopViewportWidth = 1024

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val webViewSchemes = setOf("http", "https", "about", "data", "javascript", "file")
    private val systemSchemes = setOf("tel", "mailto", "sms")

    private val desktopModeScript: String
        get() = """
            (function() {
                var desktopWidth = $desktopViewportWidth;
                try {
                    Object.defineProperty(window, 'innerWidth', { get: function() { return desktopWidth; }, configurable: true });
                    Object.defineProperty(window, 'outerWidth', { get: function() { return desktopWidth; }, configurable: true });
                    Object.defineProperty(document.documentElement, 'clientWidth', { get: function() { return desktopWidth; }, configurable: true });
                    Object.defineProperty(screen, 'width', { get: function() { return desktopWidth; }, configurable: true });
                    Object.defineProperty(screen, 'availWidth', { get: function() { return desktopWidth; }, configurable: true });
                } catch(e) {}
                function setDesktopViewport() {
                    var viewport = document.querySelector('meta[name="viewport"]');
                    var content = 'width=' + desktopWidth + ', initial-scale=0.67, minimum-scale=0.1, maximum-scale=10';
                    if (viewport) { viewport.setAttribute('content', content); }
                    else if (document.head) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        viewport.content = content;
                        document.head.insertBefore(viewport, document.head.firstChild);
                    }
                }
                setDesktopViewport();
                if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', setDesktopViewport); }
                try { window.dispatchEvent(new Event('resize')); } catch(e) {}
            })();
        """.trimIndent()

    private val antiSleepHeartbeatScript: String
        get() = """
            (function() {
                try {
                    window.scrollBy(0, 1); window.scrollBy(0, -1);
                    document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: Math.random() * 100, clientY: Math.random() * 100 }));
                    document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: 'Shift', code: 'ShiftLeft' }));
                    document.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: 'Shift', code: 'ShiftLeft' }));
                    window.dispatchEvent(new Event('focus'));
                    console.log('[WebSnap] Anti-sleep heartbeat sent');
                } catch(e) { console.log('[WebSnap] Heartbeat error: ' + e); }
            })();
        """.trimIndent()

    companion object {
        private const val PERMISSION_REQUEST_CAMERA = 1001
        private const val PERMISSION_REQUEST_MICROPHONE = 1002
        private const val PERMISSION_REQUEST_STORAGE = 1003
    }

    private var isPageLoaded = false
    private var mobileUserAgent: String = ""
    private var isPcMode = false
    private var desktopModeAppliedForCurrentPage = false
    private var currentLoadingUrl: String? = null
    private var currentPageTitle: String = ""

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result.resultCode, result.data)
        }

    private var pendingPermissionRequest: PermissionRequest? = null
    private lateinit var bookmarkManager: BookmarkManager
    private var bookmarkBottomSheet: BottomSheetDialog? = null
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

    private val executor = Executors.newSingleThreadExecutor()
    private var pendingImageUrl: String? = null

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
            stopLoading(); clearHistory(); clearCache(true)
            loadUrl("about:blank"); removeAllViews(); destroy()
        }
        executor.shutdown()
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
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            val chooserIntent = Intent.createChooser(contentIntent, null).apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(documentIntent))
            }
            fileChooserLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            fileUploadCallback?.onReceiveValue(null); fileUploadCallback = null
            showToast(getString(R.string.toast_file_picker_error))
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        binding.webView.apply {
            mobileUserAgent = settings.userAgentString
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                useWideViewPort = true; loadWithOverviewMode = true; setSupportZoom(true)
                builtInZoomControls = true; displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT; allowFileAccess = true
                userAgentString = userAgentString.replace("; wv", "")
                mediaPlaybackRequiresUserGesture = false
            }
            mobileUserAgent = settings.userAgentString
            webViewClient = createWebViewClient()
            webChromeClient = createWebChromeClient()
            addJavascriptInterface(WebAppInterface(this@MainActivity), WebAppInterface.INTERFACE_NAME)
            setDownloadListener(createDownloadListener())
            setOnLongClickListener { handleLongPress(); true }
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(binding.webView, true) }
    }

    private fun createDownloadListener() = DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
        handleDownload(url, userAgent, contentDisposition, mimeType, contentLength)
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String, contentLength: Long) {
        when {
            url.startsWith("blob:") -> handleBlobDownload(url, mimeType)
            url.startsWith("data:") -> handleDataUrlDownload(url)
            url.startsWith("http://") || url.startsWith("https://") -> handleHttpDownload(url, userAgent, contentDisposition, mimeType)
            else -> showToast(getString(R.string.toast_download_unsupported))
        }
    }

    private fun handleHttpDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("User-Agent", userAgent)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
                setTitle(fileName); setDescription("正在下载..."); setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            showToast(getString(R.string.toast_download_started, fileName))
        } catch (e: Exception) { showToast(getString(R.string.toast_download_failed)) }
    }

    private fun handleBlobDownload(blobUrl: String, mimeType: String) {
        val script = """
            (function() {
                var xhr = new XMLHttpRequest(); xhr.open('GET', '$blobUrl', true); xhr.responseType = 'blob';
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            window.${WebAppInterface.INTERFACE_NAME}.saveBase64File(base64, xhr.response.type || '$mimeType', null);
                        };
                        reader.readAsDataURL(xhr.response);
                    }
                };
                xhr.send();
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
        showToast(getString(R.string.toast_download_started, "文件"))
    }

    private fun handleDataUrlDownload(dataUrl: String) {
        try {
            val parts = dataUrl.substringAfter("data:").split(",", limit = 2)
            if (parts.size != 2) return
            val imageData = if (parts[0].contains("base64")) Base64.decode(parts[1], Base64.DEFAULT) else parts[1].toByteArray()
            WebAppInterface(this).saveBase64File(Base64.encodeToString(imageData, Base64.DEFAULT), parts[0].substringBefore(";"), null)
        } catch (e: Exception) { showToast(getString(R.string.toast_download_failed)) }
    }

    private fun createWebViewClient() = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isPageLoaded = false; binding.progressBar.visibility = View.VISIBLE; binding.progressBar.progress = 0
            binding.buttonCapture.isEnabled = false
            if (url != currentLoadingUrl) { currentLoadingUrl = url; desktopModeAppliedForCurrentPage = false }
            binding.editTextUrl.setText(if (url?.startsWith("file:") == true) "" else url)
            updateNavigationButtons(); updateBookmarkButton()
            if (isPcMode && url?.startsWith("file:") != true) view?.evaluateJavascript(desktopModeScript, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url); isPageLoaded = true; binding.progressBar.visibility = View.GONE
            binding.buttonCapture.isEnabled = true; updateNavigationButtons(); updateBookmarkButton()
            CookieManager.getInstance().flush()
            if (isPcMode && url?.startsWith("file:") != true) handleDesktopModePageFinished(view!!)
        }
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url ?: return false
            val scheme = url.scheme?.lowercase() ?: return false
            desktopModeAppliedForCurrentPage = false
            return if (scheme in webViewSchemes) false else { handleSystemScheme(url); true }
        }
        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload); updateNavigationButtons(); updateBookmarkButton()
        }
    }

    private fun handleDesktopModePageFinished(view: WebView) {
        view.evaluateJavascript(desktopModeScript) { if (!desktopModeAppliedForCurrentPage) {
            desktopModeAppliedForCurrentPage = true
            view.postDelayed({ if (isPcMode && isPageLoaded) view.reload() }, 300)
        }}
    }

    private fun createWebChromeClient() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progressBar.progress = newProgress
            if (newProgress >= 100) binding.progressBar.visibility = View.GONE
        }
        override fun onReceivedTitle(view: WebView?, title: String?) { currentPageTitle = title ?: "" }
        override fun onShowFileChooser(v: WebView?, f: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
            fileUploadCallback?.onReceiveValue(null); fileUploadCallback = f; launchSystemFilePicker(p); return true
        }
        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.let { perm ->
                pendingPermissionRequest = perm
                val toRequest = mutableListOf<String>()
                for (res in perm.resources) {
                    if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !hasCameraPermission()) toRequest.add(Manifest.permission.CAMERA)
                    if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !hasMicrophonePermission()) toRequest.add(Manifest.permission.RECORD_AUDIO)
                }
                if (toRequest.isEmpty()) perm.grant(perm.resources)
                else ActivityCompat.requestPermissions(this@MainActivity, toRequest.toTypedArray(), PERMISSION_REQUEST_CAMERA)
            }
        }
    }

    private fun handleLongPress(): Boolean {
        val result = binding.webView.hitTestResult
        return when (result.type) {
            WebView.HitTestResult.IMAGE_TYPE -> { result.extra?.let { showImageContextMenu(it) }; true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> { result.extra?.let { showImageLinkContextMenu(it) }; true }
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { result.extra?.let { showLinkContextMenu(it) }; true }
            else -> false
        }
    }

    private fun showImageContextMenu(url: String) = AlertDialog.Builder(this, R.style.Theme_WebSnap_AlertDialog)
        .setTitle(getString(R.string.menu_title_image)).setItems(arrayOf(getString(R.string.menu_save_image), getString(R.string.menu_copy_image_url))) { _, w ->
            if (w == 0) saveImage(url) else copyToClipboard(url) }.show()

    private fun showImageLinkContextMenu(url: String) = showImageContextMenu(url)
    private fun showLinkContextMenu(url: String) = AlertDialog.Builder(this, R.style.Theme_WebSnap_AlertDialog)
        .setTitle(getString(R.string.menu_title_link)).setItems(arrayOf(getString(R.string.menu_copy_link_url))) { _, _ -> copyToClipboard(url) }.show()

    private fun copyToClipboard(text: String) {
        try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("WebSnap", text))
            showToast(getString(R.string.toast_link_copied)) } catch (e: Exception) { showToast(getString(R.string.toast_copy_failed)) }
    }

    private fun saveImage(url: String) {
        when {
            url.startsWith("data:") -> saveDataUrlImage(url)
            url.startsWith("blob:") -> saveBlobImage(url)
            else -> saveHttpImage(url)
        }
    }

    private fun saveDataUrlImage(dataUrl: String) {
        try {
            val parts = dataUrl.substringAfter("data:").split(",", limit = 2)
            if (parts.size == 2) saveImageBytes(Base64.decode(parts[1], Base64.DEFAULT), parts[0].substringBefore(";").ifBlank { "image/png" })
        } catch (e: Exception) { showToast(getString(R.string.toast_image_save_failed)) }
    }

    private fun saveBlobImage(blobUrl: String) {
        val script = "javascript:(function(){ var xhr=new XMLHttpRequest(); xhr.open('GET', '$blobUrl', true); xhr.responseType='blob'; xhr.onload=function(){ if(xhr.status===200){ var r=new FileReader(); r.onloadend=function(){ window.${WebAppInterface.INTERFACE_NAME}.saveBase64Image(r.result.split(',')[1], xhr.response.type||'image/png'); }; r.readAsDataURL(xhr.response); } }; xhr.send(); })();"
        binding.webView.evaluateJavascript(script, null); showToast(getString(R.string.toast_image_saving))
    }

    private fun saveHttpImage(imageUrl: String) {
        showToast(getString(R.string.toast_image_saving))
        executor.execute { try {
            val conn = URL(imageUrl).openConnection() as HttpURLConnection
            conn.doInput = true; conn.connect()
            val data = conn.inputStream.readBytes()
            runOnUiThread { saveImageBytes(data, conn.contentType ?: "image/png") }
        } catch (e: Exception) { runOnUiThread { showToast(getString(R.string.toast_image_save_failed)) } } }
    }

    private fun saveImageBytes(data: ByteArray, mime: String) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val ext = if (mime.contains("jpeg")) ".jpg" else if (mime.contains("gif")) ".gif" else ".png"
            val name = "WebSnap_$ts$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime); put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WebSnap"); put(MediaStore.Images.Media.IS_PENDING, 1) }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(data) }; cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); contentResolver.update(it, cv, null, null) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WebSnap")
                if (!dir.exists()) dir.mkdirs(); FileOutputStream(File(dir, name)).use { it.write(data) }
            }
            showToast(getString(R.string.toast_image_saved))
        } catch (e: Exception) { showToast(getString(R.string.toast_image_save_failed)) }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        if (rc == PERMISSION_REQUEST_CAMERA) pendingPermissionRequest?.let { req ->
            val granted = mutableListOf<String>()
            for (r in req.resources) {
                if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasCameraPermission()) granted.add(r)
                if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasMicrophonePermission()) granted.add(r)
            }
            if (granted.isNotEmpty()) req.grant(granted.toTypedArray()) else req.deny()
            pendingPermissionRequest = null
        }
    }

    private fun setupListeners() {
        binding.buttonGo.setOnClickListener { loadUrl() }
        binding.editTextUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { loadUrl(); true } else false
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

        // ★ 新增：JS 记事本点击事件（含自适应字体逻辑）
        binding.buttonJsNotepad.setOnClickListener {
            val fontSize = if (isPcMode) "18px" else "14px"
            val jsCode = """
                javascript:(function(){var e=document.getElementById('temp-editor');if(e){e.remove();return;}var box=document.createElement('div');box.id='temp-editor';box.style.cssText='position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:999999;padding:10px;box-sizing:border-box;display:flex;flex-direction:column;';var bar=document.createElement('div');bar.style.cssText='display:flex;gap:10px;margin-bottom:10px;';var copyBtn=document.createElement('button');copyBtn.textContent='📋 复制';copyBtn.style.cssText='padding:10px 15px;font-size:$fontSize;border:none;border-radius:5px;background:#4CAF50;color:white;';var clearBtn=document.createElement('button');clearBtn.textContent='🗑️ 清空';clearBtn.style.cssText='padding:10px 15px;font-size:$fontSize;border:none;border-radius:5px;background:#ff9800;color:white;';var closeBtn=document.createElement('button');closeBtn.textContent='✖ 关闭';closeBtn.style.cssText='padding:10px 15px;font-size:$fontSize;border:none;border-radius:5px;background:#f44336;color:white;margin-left:auto;';var ta=document.createElement('textarea');ta.style.cssText='flex:1;width:100%;font-size:$fontSize;padding:15px;box-sizing:border-box;border:none;border-radius:5px;resize:none;line-height:1.6;';ta.placeholder='在这里输入或粘贴文字...';copyBtn.onclick=function(){ta.select();navigator.clipboard.writeText(ta.value).then(function(){copyBtn.textContent='✅ 已复制';setTimeout(function(){copyBtn.textContent='📋 复制';},1500);});};clearBtn.onclick=function(){ta.value='';ta.focus();};closeBtn.onclick=function(){box.remove();};bar.appendChild(copyBtn);bar.appendChild(clearBtn);bar.appendChild(closeBtn);box.appendChild(bar);box.appendChild(ta);document.body.appendChild(box);ta.focus();})();
            """.trimIndent()
            binding.webView.evaluateJavascript(jsCode, null)
        }
    }

    private fun loadHomePage() { binding.editTextUrl.setText(""); binding.webView.loadUrl(homePageUrl) }
    private fun updateNavigationButtons() { binding.buttonBack.isEnabled = binding.webView.canGoBack(); binding.buttonForward.isEnabled = binding.webView.canGoForward() }
    private fun handleSystemScheme(u: Uri) { try { startActivity(Intent(Intent.ACTION_VIEW, u).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (e: Exception) {} }

    private fun updateBookmarkButton() {
        val url = binding.webView.url
        val isBookmarked = if (!url.isNullOrBlank() && url != "about:blank" && !url.startsWith("file:")) bookmarkManager.contains(url) else false
        binding.buttonBookmark.text = if (isBookmarked) getString(R.string.button_bookmark_filled) else getString(R.string.button_bookmark_empty)
    }

    private fun toggleBookmark() {
        val url = binding.webView.url
        if (url.isNullOrBlank() || url == "about:blank" || url.startsWith("file:")) { showToast(getString(R.string.toast_bookmark_need_page)); return }
        if (bookmarkManager.contains(url)) { bookmarkManager.remove(url); showToast(getString(R.string.toast_bookmark_removed)) }
        else { bookmarkManager.add(Bookmark(currentPageTitle.ifBlank { url }, url)); showToast(getString(R.string.toast_bookmark_added)) }
        updateBookmarkButton()
    }

    private fun showBookmarkSheet() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetBookmarksBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)
        val adapter = BookmarkAdapter(onItemClick = { b -> bottomSheet.dismiss(); binding.webView.loadUrl(b.url) }, onDeleteClick = { b, p ->
            bookmarkManager.removeAt(p); (sheetBinding.recyclerViewBookmarks.adapter as? BookmarkAdapter)?.removeAt(p)
            if (bookmarkManager.isEmpty()) { sheetBinding.recyclerViewBookmarks.visibility = View.GONE; sheetBinding.emptyStateContainer.visibility = View.VISIBLE }
            updateBookmarkButton() })
        sheetBinding.recyclerViewBookmarks.layoutManager = LinearLayoutManager(this); sheetBinding.recyclerViewBookmarks.adapter = adapter
        val bks = bookmarkManager.getAll()
        if (bks.isEmpty()) { sheetBinding.recyclerViewBookmarks.visibility = View.GONE; sheetBinding.emptyStateContainer.visibility = View.VISIBLE }
        else { sheetBinding.recyclerViewBookmarks.visibility = View.VISIBLE; sheetBinding.emptyStateContainer.visibility = View.GONE; adapter.submitList(bks) }
        bookmarkBottomSheet = bottomSheet; bottomSheet.show()
    }

    private fun updatePcModeButton() { binding.buttonPcMode.isSelected = isPcMode }
    private fun togglePcMode() {
        isPcMode = !isPcMode; updatePcModeButton()
        binding.webView.settings.apply { userAgentString = if (isPcMode) desktopUserAgent else mobileUserAgent; useWideViewPort = true; loadWithOverviewMode = !isPcMode }
        val url = binding.webView.url
        if (!url.isNullOrBlank() && url != "about:blank" && !url.startsWith("file:")) binding.webView.reload()
    }

    private fun performRefresh() {
        val url = binding.webView.url
        if (url.isNullOrBlank() || url == "about:blank") { showToast(getString(R.string.toast_refresh_need_page)); return }
        binding.webView.reload()
    }

    private fun updateRefreshButtonState() {
        val s = refreshService
        if (s != null && s.hasActiveTask()) {
            binding.buttonRefresh.isActivated = true
            val t = s.getCurrentTask()
            val r = s.getRemainingSeconds()
            binding.buttonRefresh.text = when (t) {
                is RefreshTask.Interval -> getString(R.string.button_refresh_countdown, formatSeconds(r))
                is RefreshTask.Scheduled -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t.targetTimeMillis))
                is RefreshTask.AntiSleep -> getString(R.string.button_anti_sleep_active, formatSeconds(r))
                else -> getString(R.string.button_refresh_default)
            }
        } else { binding.buttonRefresh.isActivated = false; binding.buttonRefresh.text = getString(R.string.button_refresh_default) }
    }

    private fun showRefreshSheet() {
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetRefreshBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)
        val intervalOptions = resources.getStringArray(R.array.interval_options)
        val intervalValues = resources.getIntArray(R.array.interval_values)
        sheetBinding.spinnerInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalOptions).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sheetBinding.spinnerInterval.setSelection(4)
        selectedScheduledTime = null; customIntervalSeconds = null
        val s = refreshService
        val isAnti = s?.isAntiSleepMode() == true
        updateAntiSleepButtonState(sheetBinding, isAnti)
        if (isAnti) (s?.getCurrentTask() as? RefreshTask.AntiSleep)?.let { sheetBinding.editTextAntiSleepInterval.setText(it.intervalSeconds.toString()) }
        sheetBinding.buttonAntiSleep.setOnClickListener {
            val url = binding.webView.url
            if (url.isNullOrBlank() || url == "about:blank" || url.startsWith("file:")) { showToast(getString(R.string.toast_anti_sleep_need_page)); return@setOnClickListener }
            if (refreshService?.isAntiSleepMode() == true) { stopAntiSleepMode(); updateAntiSleepButtonState(sheetBinding, false); sheetBinding.containerCurrentTask.visibility = View.GONE; sheetBinding.buttonCancelTask.visibility = View.GONE }
            else { val sec = sheetBinding.editTextAntiSleepInterval.text.toString().toLongOrNull(); if (sec == null || sec < 1 || sec > 9999) { showToast(getString(R.string.toast_anti_sleep_invalid_interval)); return@setOnClickListener }; refreshService?.stopTask(); startAntiSleepMode(sec); updateAntiSleepButtonState(sheetBinding, true); updateCurrentTaskDisplay(sheetBinding) }
        }
        sheetBinding.buttonCustomInterval.setOnClickListener { showCustomIntervalDialog { sec -> customIntervalSeconds = sec; sheetBinding.radioInterval.isChecked = true } }
        sheetBinding.buttonPickTime.setOnClickListener { showTimePicker(sheetBinding) }
        sheetBinding.radioInterval.setOnCheckedChangeListener { _, isChecked -> if (isChecked) { sheetBinding.radioScheduled.isChecked = false; sheetBinding.containerInterval.alpha = 1f; sheetBinding.containerScheduled.alpha = 0.5f } }
        sheetBinding.radioScheduled.setOnCheckedChangeListener { _, isChecked -> if (isChecked) { sheetBinding.radioInterval.isChecked = false; sheetBinding.containerInterval.alpha = 0.5f; sheetBinding.containerScheduled.alpha = 1f; customIntervalSeconds = null } }
        sheetBinding.radioInterval.isChecked = true; updateCurrentTaskDisplay(sheetBinding)
        sheetBinding.buttonCancelTask.setOnClickListener { refreshService?.stopTask(); stopRefreshService(); updateAntiSleepButtonState(sheetBinding, false); sheetBinding.containerCurrentTask.visibility = View.GONE; sheetBinding.buttonCancelTask.visibility = View.GONE }
        sheetBinding.buttonConfirm.setOnClickListener {
            if (sheetBinding.radioInterval.isChecked) { val sec = customIntervalSeconds ?: intervalValues[sheetBinding.spinnerInterval.selectedItemPosition].toLong(); if (refreshService?.isAntiSleepMode() == true) refreshService?.stopTask(); startIntervalRefresh(sec); bottomSheet.dismiss() }
            else if (sheetBinding.radioScheduled.isChecked) { selectedScheduledTime?.let { if (refreshService?.isAntiSleepMode() == true) refreshService?.stopTask(); startScheduledRefresh(it.timeInMillis); bottomSheet.dismiss() } ?: showToast(getString(R.string.toast_refresh_select_time)) }
        }
        bottomSheet.show()

        // ★ 关键优化：强制弹窗全展开，确保“确认设置”按钮可见
        bottomSheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun updateAntiSleepButtonState(sb: BottomSheetRefreshBinding, act: Boolean) { sb.buttonAntiSleep.isActivated = act; sb.buttonAntiSleep.isSelected = act; sb.buttonAntiSleep.text = if (act) getString(R.string.anti_sleep_on) else getString(R.string.anti_sleep_off) }
    private fun updateCurrentTaskDisplay(sb: BottomSheetRefreshBinding) {
        val s = refreshService
        if (s != null && s.hasActiveTask()) {
            sb.containerCurrentTask.visibility = View.VISIBLE; sb.buttonCancelTask.visibility = View.VISIBLE
            val t = s.getCurrentTask(); val r = s.getRemainingSeconds()
            when (t) {
                is RefreshTask.Interval -> { sb.textViewCurrentTaskIcon.text = getString(R.string.refresh_current_task_icon); sb.textViewCurrentTask.text = getString(R.string.refresh_current_task_interval, getIntervalDisplayText(t.intervalSeconds), formatSeconds(r)) }
                is RefreshTask.Scheduled -> { sb.textViewCurrentTaskIcon.text = getString(R.string.refresh_current_task_icon); sb.textViewCurrentTask.text = getString(R.string.refresh_current_task_scheduled, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t.targetTimeMillis))) }
                is RefreshTask.AntiSleep -> { sb.textViewCurrentTaskIcon.text = getString(R.string.anti_sleep_current_task_icon); sb.textViewCurrentTask.text = getString(R.string.refresh_current_task_anti_sleep, t.intervalSeconds.toString(), formatSeconds(r)) }
                else -> { sb.containerCurrentTask.visibility = View.GONE; sb.buttonCancelTask.visibility = View.GONE }
            }
        } else { sb.containerCurrentTask.visibility = View.GONE; sb.buttonCancelTask.visibility = View.GONE }
    }

    private fun showCustomIntervalDialog(onConfirm: (Long) -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_custom_interval, null)
        val h = v.findViewById<EditText>(R.id.editTextHours); val m = v.findViewById<EditText>(R.id.editTextMinutes); val s = v.findViewById<EditText>(R.id.editTextSeconds)
        val dialog = AlertDialog.Builder(this).setView(v).setCancelable(true).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        v.findViewById<Button>(R.id.buttonOk).setOnClickListener { val total = (h.text.toString().toIntOrNull() ?: 0) * 3600L + (m.text.toString().toIntOrNull() ?: 0) * 60L + (s.text.toString().toIntOrNull() ?: 0); if (total > 0) { onConfirm(total); dialog.dismiss() } }
        v.findViewById<Button>(R.id.buttonCancel).setOnClickListener { dialog.dismiss() }; dialog.show()
    }

    private fun showTimePicker(sb: BottomSheetRefreshBinding) {
        val c = Calendar.getInstance(); TimePickerDialog(this, R.style.Theme_WebSnap_TimePicker, { _, h, m -> showSecondPicker(sb, h, m) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun showSecondPicker(sb: BottomSheetRefreshBinding, h: Int, m: Int) {
        val secs = arrayOf("00", "15", "30", "45"); val vals = intArrayOf(0, 15, 30, 45)
        AlertDialog.Builder(this, R.style.Theme_WebSnap_SecondPicker).setTitle("选择秒数").setItems(secs) { _, w ->
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, vals[w]); set(Calendar.MILLISECOND, 0) }
            selectedScheduledTime = cal; sb.buttonPickTime.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, vals[w]) }.show()
    }

    private fun startIntervalRefresh(sec: Long) = startRefreshService(RefreshService.TASK_TYPE_INTERVAL, sec, 0)
    private fun startScheduledRefresh(time: Long) = startRefreshService(RefreshService.TASK_TYPE_SCHEDULED, 0, time)
    private fun startAntiSleepMode(sec: Long) = startRefreshService(RefreshService.TASK_TYPE_ANTI_SLEEP, sec, 0)
    private fun startRefreshService(type: String, sec: Long, time: Long) {
        val i = Intent(this, RefreshService::class.java).apply { action = RefreshService.ACTION_START_TASK; putExtra(RefreshService.EXTRA_TASK_TYPE, type); putExtra(RefreshService.EXTRA_INTERVAL_SECONDS, sec); putExtra(RefreshService.EXTRA_TARGET_TIME, time) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }
    private fun stopAntiSleepMode() { refreshService?.stopTask(); stopRefreshService(); updateRefreshButtonState() }
    private fun stopRefreshService() { startService(Intent(this, RefreshService::class.java).apply { action = RefreshService.ACTION_STOP_TASK }); updateRefreshButtonState() }

    private fun getIntervalDisplayText(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return when { h > 0 -> "${h}时${m}分${sec}秒"; m > 0 -> "${m}分${sec}秒"; else -> "${sec}秒" }
    }

    private fun formatSeconds(s: Long): String { val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60; return if (h > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, sec) else String.format(Locale.getDefault(), "%02d:%02d", m, sec) }

    override fun onTaskStarted(t: RefreshTask) { runOnUiThread { updateRefreshButtonState() } }
    override fun onCountdownTick(rem: Long) { runOnUiThread { val t = refreshService?.getCurrentTask(); if (t is RefreshTask.Interval) binding.buttonRefresh.text = getString(R.string.button_refresh_countdown, formatSeconds(rem)) } }
    override fun onRefreshTriggered() { runOnUiThread { binding.buttonRefresh.text = getString(R.string.button_refreshing); binding.webView.reload() } }
    override fun onTaskCancelled() { runOnUiThread { binding.buttonRefresh.isActivated = false; binding.buttonRefresh.text = getString(R.string.button_refresh_default) } }
    override fun onAntiSleepTick(rem: Long) { runOnUiThread { binding.buttonRefresh.text = getString(R.string.button_anti_sleep_active, formatSeconds(rem)) } }
    override fun onAntiSleepHeartbeat() { runOnUiThread { binding.webView.evaluateJavascript(antiSleepHeartbeatScript, null) } }

    private fun loadUrl() {
        val input = binding.editTextUrl.text.toString().trim()
        if (input.isEmpty()) return
        val url = if (input.startsWith("http")) input else "https://$input"
        hideKeyboard(); desktopModeAppliedForCurrentPage = false; binding.webView.loadUrl(url)
    }

    private fun getEffectiveScale() = if (isPcMode) binding.webView.width.toFloat() / desktopViewportWidth.toFloat() else @Suppress("DEPRECATION") binding.webView.scale
    private fun captureVisibleArea() { if (!isPageLoaded) return; binding.buttonCapture.isEnabled = false; binding.webView.post { try { captureVisibleBitmap()?.let { CropBitmapHolder.set(it, false); startActivity(Intent(this, CropActivity::class.java)) } } finally { binding.buttonCapture.isEnabled = true } } }
    private fun captureWholePage() { if (!isPageLoaded) return; binding.buttonCapture.isEnabled = false; binding.webView.post { try { captureFullPageBitmap()?.let { CropBitmapHolder.set(it, true); startActivity(Intent(this, CropActivity::class.java)) } } finally { binding.buttonCapture.isEnabled = true } } }

    private fun captureVisibleBitmap(): Bitmap? {
        val w = binding.webView.width; val h = binding.webView.height
        if (w <= 0 || h <= 0) return null
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); binding.webView.draw(Canvas(b)); return b
    }

    private fun captureFullPageBitmap(): Bitmap? {
        val s = getEffectiveScale(); val w = binding.webView.width; var h = (binding.webView.contentHeight * s).toInt()
        if (w <= 0 || h <= 0) return null
        if (h > maxCaptureHeight) h = maxCaptureHeight
        val b: Bitmap?; val old = binding.webView.layerType; binding.webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        try { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); binding.webView.draw(Canvas(b)) } finally { binding.webView.setLayerType(old, null) }
        return b
    }

    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0); binding.editTextUrl.clearFocus() }
    private fun showToast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
