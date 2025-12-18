package com.example.websnap

import org.json.JSONObject

/**
 * 书签数据类
 *
 * @property title 网页标题
 * @property url 网页地址（作为唯一标识）
 */
data class Bookmark(
    val title: String,
    val url: String
) {
    /**
     * 转换为 JSON 对象
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put(KEY_TITLE, title)
            put(KEY_URL, url)
        }
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_URL = "url"

        /**
         * 从 JSON 对象解析书签
         */
        fun fromJson(json: JSONObject): Bookmark {
            return Bookmark(
                title = json.optString(KEY_TITLE, ""),
                url = json.optString(KEY_URL, "")
            )
        }
    }
}
