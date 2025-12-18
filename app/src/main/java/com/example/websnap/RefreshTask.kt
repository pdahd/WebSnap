package com.example.websnap

import java.io.Serializable

/**
 * 刷新任务数据类
 */
sealed class RefreshTask : Serializable {

    /**
     * 间隔刷新任务
     * @param intervalSeconds 刷新间隔（秒）
     */
    data class Interval(
        val intervalSeconds: Long
    ) : RefreshTask() {
        override fun getDisplayName(): String = "间隔刷新"
    }

    /**
     * 定时刷新任务
     * @param targetTimeMillis 目标时间戳（毫秒）
     */
    data class Scheduled(
        val targetTimeMillis: Long
    ) : RefreshTask() {
        override fun getDisplayName(): String = "定时刷新"
    }

    abstract fun getDisplayName(): String

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * 刷新任务状态
 */
enum class RefreshState {
    IDLE,           // 空闲，无任务
    COUNTING,       // 倒计时中
    REFRESHING      // 正在刷新
}
