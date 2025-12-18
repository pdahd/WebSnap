package com.example.websnap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台刷新服务
 *
 * 负责在后台维持定时任务，确保 App 在后台时也能准时刷新。
 */
class RefreshService : Service() {

    // ═══════════════════════════════════════════════════════════════
    // 常量
    // ═══════════════════════════════════════════════════════════════

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "websnap_refresh_channel"
        private const val CHANNEL_NAME = "自动刷新"

        // Action 定义
        const val ACTION_START_TASK = "com.example.websnap.START_TASK"
        const val ACTION_STOP_TASK = "com.example.websnap.STOP_TASK"
        const val EXTRA_TASK_TYPE = "task_type"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"
        const val EXTRA_TARGET_TIME = "target_time"
    }

    // ═══════════════════════════════════════════════════════════════
    // Binder
    // ═══════════════════════════════════════════════════════════════

    private val binder = RefreshBinder()

    inner class RefreshBinder : Binder() {
        fun getService(): RefreshService = this@RefreshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ═══════════════════════════════════════════════════════════════
    // 状态变量
    // ═══════════════════════════════════════════════════════════════

    private val handler = Handler(Looper.getMainLooper())
    private var currentTask: RefreshTask? = null
    private var state: RefreshState = RefreshState.IDLE

    /** 下一次刷新的时间戳（毫秒） */
    private var nextRefreshTimeMillis: Long = 0

    /** 回调监听器 */
    private var listener: RefreshCallback? = null

    /** 通知管理器 */
    private lateinit var notificationManager: NotificationManager

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TASK -> {
                val taskType = intent.getStringExtra(EXTRA_TASK_TYPE)
                when (taskType) {
                    "interval" -> {
                        val seconds = intent.getLongExtra(EXTRA_INTERVAL_SECONDS, 60)
                        startIntervalTask(seconds)
                    }
                    "scheduled" -> {
                        val targetTime = intent.getLongExtra(EXTRA_TARGET_TIME, 0)
                        if (targetTime > 0) {
                            startScheduledTask(targetTime)
                        }
                    }
                }
            }
            ACTION_STOP_TASK -> {
                stopTask()
            }
        }

        // START_STICKY: 服务被杀后尝试重启
        return START_STICKY
    }

    override fun onDestroy() {
        stopTask()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置回调监听器
     */
    fun setCallback(callback: RefreshCallback?) {
        this.listener = callback
    }

    /**
     * 获取当前任务
     */
    fun getCurrentTask(): RefreshTask? = currentTask

    /**
     * 获取当前状态
     */
    fun getState(): RefreshState = state

    /**
     * 获取剩余秒数
     */
    fun getRemainingSeconds(): Long {
        if (nextRefreshTimeMillis <= 0) return 0
        val remaining = (nextRefreshTimeMillis - System.currentTimeMillis()) / 1000
        return maxOf(0, remaining)
    }

    /**
     * 是否有活动任务
     */
    fun hasActiveTask(): Boolean = currentTask != null && state == RefreshState.COUNTING

    /**
     * 启动间隔刷新任务
     */
    fun startIntervalTask(intervalSeconds: Long) {
        stopTask()

        currentTask = RefreshTask.Interval(intervalSeconds)
        nextRefreshTimeMillis = System.currentTimeMillis() + (intervalSeconds * 1000)
        state = RefreshState.COUNTING

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 开始倒计时
        startCountdown()

        listener?.onTaskStarted(currentTask!!)
    }

    /**
     * 启动定时刷新任务
     */
    fun startScheduledTask(targetTimeMillis: Long) {
        // 如果目标时间已过，则设为明天同一时间
        var adjustedTime = targetTimeMillis
        if (adjustedTime <= System.currentTimeMillis()) {
            adjustedTime += 24 * 60 * 60 * 1000 // +24小时
        }

        stopTask()

        currentTask = RefreshTask.Scheduled(adjustedTime)
        nextRefreshTimeMillis = adjustedTime
        state = RefreshState.COUNTING

        startForeground(NOTIFICATION_ID, createNotification())
        startCountdown()

        listener?.onTaskStarted(currentTask!!)
    }

    /**
     * 停止当前任务
     */
    fun stopTask() {
        handler.removeCallbacksAndMessages(null)
        
        val hadTask = currentTask != null
        currentTask = null
        nextRefreshTimeMillis = 0
        state = RefreshState.IDLE

        stopForeground(STOP_FOREGROUND_REMOVE)

        if (hadTask) {
            listener?.onTaskCancelled()
        }
    }

    /**
     * 手动触发刷新（单击刷新按钮时）
     */
    fun triggerManualRefresh() {
        listener?.onRefreshTriggered()
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部逻辑
    // ═══════════════════════════════════════════════════════════════

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (currentTask == null) return

            val remaining = getRemainingSeconds()

            if (remaining <= 0) {
                // 时间到，执行刷新
                performRefresh()
            } else {
                // 更新倒计时
                listener?.onCountdownTick(remaining)
                updateNotification()

                // 每秒更新一次
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun startCountdown() {
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
    }

    private fun performRefresh() {
        state = RefreshState.REFRESHING
        listener?.onRefreshTriggered()

        // 刷新后处理
        handler.postDelayed({
            when (val task = currentTask) {
                is RefreshTask.Interval -> {
                    // 间隔刷新：重新开始计时
                    nextRefreshTimeMillis = System.currentTimeMillis() + (task.intervalSeconds * 1000)
                    state = RefreshState.COUNTING
                    startCountdown()
                }
                is RefreshTask.Scheduled -> {
                    // 定时刷新：任务完成，设为明天同一时间
                    nextRefreshTimeMillis = task.targetTimeMillis + (24 * 60 * 60 * 1000)
                    currentTask = RefreshTask.Scheduled(nextRefreshTimeMillis)
                    state = RefreshState.COUNTING
                    startCountdown()
                    listener?.onTaskStarted(currentTask!!)
                }
                null -> {
                    state = RefreshState.IDLE
                }
            }
        }, 1000)
    }

    // ═══════════════════════════════════════════════════════════════
    // 通知管理
    // ═══════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebSnap 自动刷新倒计时"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // 点击通知返回 App
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 取消任务的 Action
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RefreshService::class.java).apply {
                action = ACTION_STOP_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when (val task = currentTask) {
            is RefreshTask.Interval -> {
                val remaining = getRemainingSeconds()
                "间隔刷新 | 倒计时: ${formatSeconds(remaining)}"
            }
            is RefreshTask.Scheduled -> {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                "定时刷新 | 目标: ${timeFormat.format(Date(task.targetTimeMillis))}"
            }
            null -> "等待中..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("WebSnap 自动刷新")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (currentTask != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification())
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
    // 回调接口
    // ═══════════════════════════════════════════════════════════════

    interface RefreshCallback {
        /** 任务已启动 */
        fun onTaskStarted(task: RefreshTask)

        /** 倒计时更新（每秒调用） */
        fun onCountdownTick(remainingSeconds: Long)

        /** 需要执行刷新 */
        fun onRefreshTriggered()

        /** 任务已取消 */
        fun onTaskCancelled()
    }
}
