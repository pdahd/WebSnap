package com.example.websnap

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * 书签管理器
 *
 * 负责书签的增删查操作，使用 SharedPreferences 存储 JSON 格式数据。
 * 采用单例模式，确保全局数据一致性。
 */
class BookmarkManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /** 内存缓存，避免频繁读取磁盘 */
    private val bookmarks: MutableList<Bookmark> = mutableListOf()

    /** 数据变化监听器 */
    private val listeners: MutableSet<OnBookmarkChangeListener> = mutableSetOf()

    init {
        // 初始化时从 SharedPreferences 加载数据
        loadFromPrefs()
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取所有书签（返回副本，防止外部修改）
     */
    fun getAll(): List<Bookmark> {
        return bookmarks.toList()
    }

    /**
     * 检查 URL 是否已存在于书签中
     */
    fun contains(url: String): Boolean {
        val normalizedUrl = normalizeUrl(url)
        return bookmarks.any { normalizeUrl(it.url) == normalizedUrl }
    }

    /**
     * 添加书签
     *
     * @return true 添加成功，false URL 已存在
     */
    fun add(bookmark: Bookmark): Boolean {
        val normalizedUrl = normalizeUrl(bookmark.url)

        // 检查是否已存在
        val existingIndex = bookmarks.indexOfFirst {
            normalizeUrl(it.url) == normalizedUrl
        }

        if (existingIndex != -1) {
            // URL 已存在，更新标题
            val existing = bookmarks[existingIndex]
            if (existing.title != bookmark.title) {
                bookmarks[existingIndex] = bookmark
                saveToPrefs()
                notifyListeners()
            }
            return false
        }

        // 添加新书签（插入到列表开头，最新的在最前面）
        bookmarks.add(0, bookmark)
        saveToPrefs()
        notifyListeners()
        return true
    }

    /**
     * 根据 URL 移除书签
     *
     * @return 被移除的书签，如果不存在则返回 null
     */
    fun remove(url: String): Bookmark? {
        val normalizedUrl = normalizeUrl(url)
        val index = bookmarks.indexOfFirst {
            normalizeUrl(it.url) == normalizedUrl
        }

        if (index == -1) {
            return null
        }

        val removed = bookmarks.removeAt(index)
        saveToPrefs()
        notifyListeners()
        return removed
    }

    /**
     * 根据索引移除书签
     *
     * @return 被移除的书签
     */
    fun removeAt(index: Int): Bookmark? {
        if (index < 0 || index >= bookmarks.size) {
            return null
        }

        val removed = bookmarks.removeAt(index)
        saveToPrefs()
        notifyListeners()
        return removed
    }

    /**
     * 获取书签数量
     */
    fun count(): Int = bookmarks.size

    /**
     * 书签列表是否为空
     */
    fun isEmpty(): Boolean = bookmarks.isEmpty()

    // ═══════════════════════════════════════════════════════════════
    // 监听器管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 添加数据变化监听器
     */
    fun addListener(listener: OnBookmarkChangeListener) {
        listeners.add(listener)
    }

    /**
     * 移除数据变化监听器
     */
    fun removeListener(listener: OnBookmarkChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onBookmarkChanged() }
    }

    // ═══════════════════════════════════════════════════════════════
    // 数据持久化
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 SharedPreferences 加载书签数据
     */
    private fun loadFromPrefs() {
        bookmarks.clear()

        val jsonString = prefs.getString(KEY_BOOKMARKS, null) ?: return

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val bookmark = Bookmark.fromJson(jsonObject)
                if (bookmark.url.isNotBlank()) {
                    bookmarks.add(bookmark)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
            // JSON 解析失败，清空数据
            bookmarks.clear()
        }

        // 如果列表为空，则添加种子数据
        if (bookmarks.isEmpty()) {
            add(Bookmark("百度主页", "https://www.baidu.com"))
            add(Bookmark("谷歌主页", "https://www.google.com"))
        }
    }

    /**
     * 保存书签数据到 SharedPreferences
     */
    private fun saveToPrefs() {
        val jsonArray = JSONArray()
        bookmarks.forEach { bookmark ->
            jsonArray.put(bookmark.toJson())
        }

        prefs.edit()
            .putString(KEY_BOOKMARKS, jsonArray.toString())
            .apply()
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 标准化 URL，用于比较
     * 移除末尾斜杠，统一小写协议
     */
    private fun normalizeUrl(url: String): String {
        return url.trim()
            .trimEnd('/')
            .lowercase()
            .let { 
                // 统一 http/https 前缀格式
                if (it.startsWith("http://")) it
                else if (it.startsWith("https://")) it
                else "https://$it"
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // 监听器接口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 书签数据变化监听器
     */
    interface OnBookmarkChangeListener {
        fun onBookmarkChanged()
    }

    // ═══════════════════════════════════════════════════════════════
    // 单例
    // ═══════════════════════════════════════════════════════════════

    companion object {
        private const val PREFS_NAME = "websnap_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks"

        @Volatile
        private var instance: BookmarkManager? = null

        /**
         * 获取 BookmarkManager 单例
         */
        fun getInstance(context: Context): BookmarkManager {
            return instance ?: synchronized(this) {
                instance ?: BookmarkManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
