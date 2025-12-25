package com.example.websnap

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * 书签管理器
 */
class BookmarkManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val bookmarks: MutableList<Bookmark> = mutableListOf()
    private val listeners: MutableSet<OnBookmarkChangeListener> = mutableSetOf()

    init {
        loadFromPrefs()
    }

    fun getAll(): List<Bookmark> = bookmarks.toList()

    fun contains(url: String): Boolean {
        val normalizedUrl = normalizeUrl(url)
        return bookmarks.any { normalizeUrl(it.url) == normalizedUrl }
    }

    fun add(bookmark: Bookmark): Boolean {
        val normalizedUrl = normalizeUrl(bookmark.url)
        val existingIndex = bookmarks.indexOfFirst { normalizeUrl(it.url) == normalizedUrl }

        if (existingIndex != -1) {
            val existing = bookmarks[existingIndex]
            if (existing.title != bookmark.title) {
                bookmarks[existingIndex] = bookmark
                saveToPrefs()
                notifyListeners()
            }
            return false
        }

        bookmarks.add(0, bookmark)
        saveToPrefs()
        notifyListeners()
        return true
    }

    fun remove(url: String): Bookmark? {
        val normalizedUrl = normalizeUrl(url)
        val index = bookmarks.indexOfFirst { normalizeUrl(it.url) == normalizedUrl }
        if (index == -1) return null
        val removed = bookmarks.removeAt(index)
        saveToPrefs()
        notifyListeners()
        return removed
    }

    fun removeAt(index: Int): Bookmark? {
        if (index < 0 || index >= bookmarks.size) return null
        val removed = bookmarks.removeAt(index)
        saveToPrefs()
        notifyListeners()
        return removed
    }

    fun count(): Int = bookmarks.size
    fun isEmpty(): Boolean = bookmarks.isEmpty()

    fun addListener(listener: OnBookmarkChangeListener) { listeners.add(listener) }
    fun removeListener(listener: OnBookmarkChangeListener) { listeners.remove(listener) }
    private fun notifyListeners() { listeners.forEach { it.onBookmarkChanged() } }

    /**
     * 加载逻辑：若列表为空则预置种子书签
     */
    private fun loadFromPrefs() {
        bookmarks.clear()
        val jsonString = prefs.getString(KEY_BOOKMARKS, null)

        if (jsonString.isNullOrBlank()) {
            // 首次启动，预置种子书签
            add(Bookmark(title = "谷歌主页", url = "https://www.google.com"))
            add(Bookmark(title = "百度主页", url = "https://www.baidu.com"))
        } else {
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
                bookmarks.clear()
            }
        }
    }

    private fun saveToPrefs() {
        val jsonArray = JSONArray()
        bookmarks.forEach { bookmark -> jsonArray.put(bookmark.toJson()) }
        prefs.edit().putString(KEY_BOOKMARKS, jsonArray.toString()).apply()
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase().let { 
            if (it.startsWith("http://")) it
            else if (it.startsWith("https://")) it
            else "https://$it"
        }
    }

    interface OnBookmarkChangeListener { fun onBookmarkChanged() }

    companion object {
        private const val PREFS_NAME = "websnap_bookmarks"
        private const val KEY_BOOKMARKS = "bookmarks"
        @Volatile private var instance: BookmarkManager? = null
        fun getInstance(context: Context): BookmarkManager {
            return instance ?: synchronized(this) {
                instance ?: BookmarkManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

