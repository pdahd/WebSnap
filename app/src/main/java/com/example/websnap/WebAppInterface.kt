package com.example.websnap

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JavaScript 接口类
 *
 * 用于处理网页与 Android 原生代码的交互，主要功能：
 * - 接收 blob/base64 格式的文件数据并保存到本地
 */
class WebAppInterface(private val context: Context) {

    companion object {
        /** JavaScript 中调用此接口的名称 */
        const val INTERFACE_NAME = "AndroidDownloader"
    }

    /**
     * 保存 Base64 编码的文件到下载目录
     *
     * @param base64Data Base64 编码的文件数据（不含 data: 前缀）
     * @param mimeType 文件的 MIME 类型
     * @param fileName 建议的文件名（可能为空）
     */
    @JavascriptInterface
    fun saveBase64File(base64Data: String, mimeType: String, fileName: String?) {
        try {
            // 解码 Base64 数据
            val data = Base64.decode(base64Data, Base64.DEFAULT)

            // 生成文件名
            val actualFileName = generateFileName(fileName, mimeType)

            // 根据 MIME 类型决定保存位置
            val saved = if (mimeType.startsWith("image/")) {
                saveImageToGallery(data, actualFileName, mimeType)
            } else {
                saveFileToDownloads(data, actualFileName, mimeType)
            }

            // 在主线程显示 Toast
            showToastOnMainThread(
                if (saved) {
                    context.getString(R.string.toast_download_complete, actualFileName)
                } else {
                    context.getString(R.string.toast_download_failed)
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            showToastOnMainThread(context.getString(R.string.toast_download_failed))
        }
    }

    /**
     * 保存 Base64 编码的图片到相册
     *
     * @param base64Data Base64 编码的图片数据（不含 data: 前缀）
     * @param mimeType 图片的 MIME 类型
     */
    @JavascriptInterface
    fun saveBase64Image(base64Data: String, mimeType: String) {
        try {
            val data = Base64.decode(base64Data, Base64.DEFAULT)
            val fileName = generateImageFileName(mimeType)

            val saved = saveImageToGallery(data, fileName, mimeType)

            showToastOnMainThread(
                if (saved) {
                    context.getString(R.string.toast_image_saved)
                } else {
                    context.getString(R.string.toast_image_save_failed)
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            showToastOnMainThread(context.getString(R.string.toast_image_save_failed))
        }
    }

    /**
     * 生成文件名
     */
    private fun generateFileName(suggestedName: String?, mimeType: String): String {
        if (!suggestedName.isNullOrBlank()) {
            return suggestedName
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val extension = getExtensionFromMimeType(mimeType)

        return "WebSnap_$timestamp$extension"
    }

    /**
     * 生成图片文件名
     */
    private fun generateImageFileName(mimeType: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val extension = getExtensionFromMimeType(mimeType)

        return "WebSnap_$timestamp$extension"
    }

    /**
     * 根据 MIME 类型获取文件扩展名
     */
    private fun getExtensionFromMimeType(mimeType: String): String {
        return when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
            mimeType.contains("png") -> ".png"
            mimeType.contains("gif") -> ".gif"
            mimeType.contains("webp") -> ".webp"
            mimeType.contains("svg") -> ".svg"
            mimeType.contains("pdf") -> ".pdf"
            mimeType.contains("zip") -> ".zip"
            mimeType.contains("json") -> ".json"
            mimeType.contains("javascript") -> ".js"
            mimeType.contains("html") -> ".html"
            mimeType.contains("css") -> ".css"
            mimeType.contains("text/plain") -> ".txt"
            mimeType.contains("xml") -> ".xml"
            mimeType.contains("mp4") -> ".mp4"
            mimeType.contains("mp3") -> ".mp3"
            mimeType.contains("wav") -> ".wav"
            mimeType.contains("octet-stream") -> ""  // 未知类型不加扩展名
            else -> ""
        }
    }

    /**
     * 保存图片到系统相册 (Pictures/WebSnap)
     */
    private fun saveImageToGallery(data: ByteArray, fileName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
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

                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return false

                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(data)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                true
            } else {
                // Android 9 及以下使用传统文件写入
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val webSnapDir = File(picturesDir, "WebSnap")
                if (!webSnapDir.exists()) {
                    webSnapDir.mkdirs()
                }

                val file = File(webSnapDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(data)
                }

                true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 保存文件到系统下载目录
     */
    private fun saveFileToDownloads(data: ByteArray, fileName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Downloads.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: return false

                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(data)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                true
            } else {
                // Android 9 及以下使用传统文件写入
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    outputStream.write(data)
                }

                true
            }
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 在主线程显示 Toast
     */
    private fun showToastOnMainThread(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
