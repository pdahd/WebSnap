package com.example.websnap

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.websnap.databinding.ActivityMainBinding
import com.example.websnap.databinding.BottomSheetBookmarksBinding
import com.example.websnap.databinding.BottomSheetRefreshBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    private val maxCaptureHeight = 20000
    private val desktopViewportWidth = 1024

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

    private val webViewSchemes = setOf("http", "https", "about", "data", "javascript")
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
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    private var isPageLoaded = false
    private var mobileUserAgent: String = ""
    private var isPcMode = false
    private var desktopModeAppliedForCurrentPage = false
    private var currentLoadingUrl: String? = null
    private var currentPageTitle: String = ""

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

    /** 用户在弹窗中选择的定时时间 */
    private var selectedScheduledTime: Calendar? = null

    /** 用户自定义的间隔秒数 */
    private var customIntervalSeconds: Long? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RefreshService.RefreshBinder
            refreshService = binder.getService()
            refreshService?.setCallback(this@MainActivity)
            isServiceBound = true

            // 恢复 UI 状态
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

                if (url != currentLoadingUrl) {
                    currentLoadingUrl = url
                    desktopModeAppliedForCurrentPage = false
                }

                url?.let {
                    binding.editTextUrl.setText(it)
                    binding.editTextUrl.setSelection(it.length)
                }

                updateNavigationButtons()
                updateBookmarkButton()

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
                updateBookmarkButton()

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

        // 书签按钮：单击切换收藏状态
        binding.buttonBookmark.setOnClickListener {
            toggleBookmark()
        }

        // 书签按钮：长按显示书签列表
        binding.buttonBookmark.setOnLongClickListener {
            showBookmarkSheet()
            true
        }

        // PC 模式按钮
        binding.buttonPcMode.setOnClickListener {
            togglePcMode()
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

        // 刷新按钮：单击刷新
        binding.buttonRefresh.setOnClickListener {
            performRefresh()
        }

        // 刷新按钮：长按显示设置
        binding.buttonRefresh.setOnLongClickListener {
            showRefreshSheet()
            true
        }

        // 截图按钮
        binding.buttonCapture.setOnClickListener {
            performCapture()
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
    // 书签功能
    // ═══════════════════════════════════════════════════════════════

    private fun updateBookmarkButton() {
        val currentUrl = binding.webView.url

        val isBookmarked = if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
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

        if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
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
        // 使用自定义主题
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
        if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
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

    /**
     * 更新刷新按钮状态（文字和激活状态）
     */
    private fun updateRefreshButtonState() {
        val service = refreshService

        if (service != null && service.hasActiveTask()) {
            // 有活动任务：设置激活状态
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
            // 无活动任务：恢复默认状态
            binding.buttonRefresh.isActivated = false
            binding.buttonRefresh.text = getString(R.string.button_refresh_default)
        }
    }

    /**
     * 显示刷新设置 BottomSheet
     */
    private fun showRefreshSheet() {
        // 使用自定义主题
        val bottomSheet = BottomSheetDialog(this, R.style.Theme_WebSnap_BottomSheet)
        val sheetBinding = BottomSheetRefreshBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)

        // 设置间隔选项 Spinner
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
        sheetBinding.spinnerInterval.setSelection(4) // 默认选择 5 分钟

        // 重置选中状态
        selectedScheduledTime = null
        customIntervalSeconds = null

        // 自定义间隔按钮
        sheetBinding.buttonCustomInterval.setOnClickListener {
            showCustomIntervalDialog { seconds ->
                customIntervalSeconds = seconds
                // 自动选中间隔刷新
                sheetBinding.radioInterval.isChecked = true
                showToast("已设置自定义间隔: ${getIntervalDisplayText(seconds)}")
            }
        }

        // 时间选择按钮
        sheetBinding.buttonPickTime.setOnClickListener {
            showTimePicker(sheetBinding)
        }

        // RadioButton 互斥逻辑
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
                // 清除自定义间隔
                customIntervalSeconds = null
            }
        }

        // 默认选中间隔刷新
        sheetBinding.radioInterval.isChecked = true

        // 显示当前任务状态
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

        // 取消任务按钮
        sheetBinding.buttonCancelTask.setOnClickListener {
            refreshService?.stopTask()
            stopRefreshService()
            showToast(getString(R.string.toast_refresh_cancelled))
            bottomSheet.dismiss()
        }

        // 确认按钮
        sheetBinding.buttonConfirm.setOnClickListener {
            when {
                sheetBinding.radioInterval.isChecked -> {
                    // 优先使用自定义间隔
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

    /**
     * 显示自定义间隔对话框
     */
    private fun showCustomIntervalDialog(onConfirm: (Long) -> Unit) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_custom_interval, null)

        val editHours = dialogView.findViewById<EditText>(R.id.editTextHours)
        val editMinutes = dialogView.findViewById<EditText>(R.id.editTextMinutes)
        val editSeconds = dialogView.findViewById<EditText>(R.id.editTextSeconds)
        val buttonCancel = dialogView.findViewById<Button>(R.id.buttonCancel)
        val buttonOk = dialogView.findViewById<Button>(R.id.buttonOk)

        // 设置默认值
        editHours.setText("0")
        editMinutes.setText("5")
        editSeconds.setText("0")

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // 设置对话框背景透明，以显示自定义圆角背景
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

    /**
     * 显示时间选择器（应用圆角主题）
     */
    private fun showTimePicker(sheetBinding: BottomSheetRefreshBinding) {
        val calendar = Calendar.getInstance()

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        // 使用自定义主题
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

    /**
     * 显示秒选择器（应用圆角主题）
     */
    private fun showSecondPicker(
        sheetBinding: BottomSheetRefreshBinding,
        hour: Int,
        minute: Int
    ) {
        val seconds = arrayOf("00", "15", "30", "45")
        val secondValues = intArrayOf(0, 15, 30, 45)

        // 使用自定义主题
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

    /**
     * 启动间隔刷新
     */
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

    /**
     * 启动定时刷新
     */
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

    /**
     * 停止刷新服务
     */
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
                is RefreshTask.Scheduled -> {
                    // 定时模式显示目标时间，不变
                }
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

    private fun performCapture() {
        if (!isPageLoaded) {
            showToast(getString(R.string.toast_page_not_loaded))
            return
        }

        binding.buttonCapture.isEnabled = false

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
