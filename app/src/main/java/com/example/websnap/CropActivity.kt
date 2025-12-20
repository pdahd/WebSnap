package com.example.websnap

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.websnap.databinding.ActivityCropBinding
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片裁剪界面
 */
class CropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropBinding

    // ═══════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 检查是否有待裁剪的图片
        if (!CropBitmapHolder.hasBitmap()) {
            showToast(getString(R.string.toast_crop_no_image))
            finish()
            return
        }

        setupViews()
        setupListeners()
    }

    override fun onDestroy() {
        // 退出时清理 Bitmap
        CropBitmapHolder.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        CropBitmapHolder.clear()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ═══════════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════════

    private fun setupViews() {
        val bitmap = CropBitmapHolder.bitmap ?: return

        // 设置图片
        binding.cropImageView.setBitmap(bitmap)

        // 关联裁剪覆盖层
        binding.cropOverlayView.setCropImageView(binding.cropImageView)

        // 等待布局完成后更新裁剪框
        binding.cropImageView.post {
            binding.cropOverlayView.updateImageBounds()
        }
    }

    private fun setupListeners() {
        // 取消按钮
        binding.buttonCancel.setOnClickListener {
            showToast(getString(R.string.toast_crop_cancelled))
            CropBitmapHolder.clear()
            finish()
        }

        // 重置按钮
        binding.buttonReset.setOnClickListener {
            binding.cropImageView.resetToFitView()
            binding.cropOverlayView.resetCropRect()
        }

        // 保存按钮
        binding.buttonSave.setOnClickListener {
            performCropAndSave()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 裁剪与保存
    // ═══════════════════════════════════════════════════════════════

    private fun performCropAndSave() {
        val originalBitmap = CropBitmapHolder.bitmap
        if (originalBitmap == null || originalBitmap.isRecycled) {
            showToast(getString(R.string.toast_crop_no_image))
            finish()
            return
        }

        // 获取裁剪区域（Bitmap 像素坐标）
        val cropRectInBitmap = binding.cropOverlayView.getCropRectInBitmap()

        // 验证裁剪区域
        if (!isValidCropRect(cropRectInBitmap, originalBitmap)) {
            showToast(getString(R.string.toast_crop_area_too_small))
            return
        }

        try {
            // 执行裁剪
            val croppedBitmap = cropBitmap(originalBitmap, cropRectInBitmap)

            // 保存到相册
            val saved = saveBitmapToGallery(croppedBitmap)
            croppedBitmap.recycle()

            if (saved) {
                showToast(getString(R.string.toast_crop_saved))
                CropBitmapHolder.clear()
                finish()
            } else {
                showToast(getString(R.string.toast_crop_failed))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast(getString(R.string.toast_crop_failed))
        }
    }

    private fun isValidCropRect(rect: RectF, bitmap: Bitmap): Boolean {
        // 约束到 Bitmap 边界内
        rect.left = rect.left.coerceIn(0f, bitmap.width.toFloat())
        rect.top = rect.top.coerceIn(0f, bitmap.height.toFloat())
        rect.right = rect.right.coerceIn(0f, bitmap.width.toFloat())
        rect.bottom = rect.bottom.coerceIn(0f, bitmap.height.toFloat())

        // 检查最小尺寸
        return rect.width() >= 50f && rect.height() >= 50f
    }

    private fun cropBitmap(source: Bitmap, rect: RectF): Bitmap {
        val x = rect.left.toInt().coerceAtLeast(0)
        val y = rect.top.toInt().coerceAtLeast(0)
        var width = rect.width().toInt()
        var height = rect.height().toInt()

        // 确保不超出边界
        if (x + width > source.width) {
            width = source.width - x
        }
        if (y + height > source.height) {
            height = source.height - y
        }

        return Bitmap.createBitmap(source, x, y, width, height)
    }

    // ═══════════════════════════════════════════════════════════════
    // 保存到相册
    // ═══════════════════════════════════════════════════════════════

    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val filename = "WebSnap_Crop_$timestamp.png"

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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
