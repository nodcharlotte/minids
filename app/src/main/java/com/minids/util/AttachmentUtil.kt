package com.minids.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.minids.data.Attachment
import com.minids.net.DeepSeekApi
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object AttachmentUtil {

    private const val MAX_SIDE = 1300

    suspend fun pickImage(app: Application, uri: Uri, key: String): Attachment {
        val resolver = app.contentResolver
        val bitmap = decode(uri, resolver) ?: throw IllegalStateException("无法读取这张图片")
        val dir = File(app.filesDir, "images").apply { mkdirs() }
        val out = File(dir, "img_${System.nanoTime()}.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        val fileId = DeepSeekApi.uploadImage(key, out.readBytes(), out.name)
        return Attachment(fileId = fileId, name = out.name, isImage = true, localPath = out.absolutePath)
    }

    suspend fun pickFile(app: Application, uri: Uri, key: String): Attachment {
        val resolver = app.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val lower = name.lowercase()
        val needsParsing = listOf(".pdf", ".doc", ".docx", ".ppt", ".pptx").any { lower.endsWith(it) }
        val purpose = if (needsParsing) "text_recognition" else "user_data"
        val dir = File(app.filesDir, "uploads").apply { mkdirs() }
        val ext = name.substringAfterLast('.', "bin").take(8)

        // 文本统一转成 UTF-8：记事本存的 GBK/ANSI 直接上传，模型读到的就是乱码
        if (TextCodec.isText(mime, name) && (size <= 0 || size < 8L * 1024 * 1024)) {
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取这个文件")
            val normalized = TextCodec.utf8(TextCodec.decode(raw))
            val fileId = DeepSeekApi.uploadBytes(key, normalized, name, mime, purpose)
            val out = File(dir, "u_${System.currentTimeMillis()}.$ext")
            out.writeBytes(normalized)
            return Attachment(fileId = fileId, name = name, isImage = false, localPath = out.absolutePath)
        }

        val fileId = DeepSeekApi.uploadStream(key, uri, resolver, name, mime, size, purpose)
        // 本地留一份，网页端才能读回并再次编辑
        val local = runCatching {
            val out = File(dir, "u_${System.currentTimeMillis()}.$ext")
            resolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.absolutePath
        }.getOrNull()
        return Attachment(fileId = fileId, name = name, isImage = false, localPath = local)
    }

    private fun decode(uri: Uri, resolver: android.content.ContentResolver): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        // 竖屏拍的照片，像素是横着存的，方向记在 EXIF 里。
        // BitmapFactory 不认 EXIF，我们重新压成 JPEG 后 EXIF 也丢了，
        // 结果模型看到的就是横躺的照片。这里把方向直接旋进像素。
        val upright = applyOrientation(raw, orientationOf(uri, resolver))

        val side = max(upright.width, upright.height)
        if (side <= MAX_SIDE) return upright

        val ratio = MAX_SIDE.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(
            upright,
            (upright.width * ratio).toInt().coerceAtLeast(1),
            (upright.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== upright) upright.recycle()
        return scaled
    }

    private fun orientationOf(uri: Uri, resolver: android.content.ContentResolver): Int = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.setRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.setRotate(-90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        val out = runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }.getOrNull() ?: return src
        if (out !== src) src.recycle()
        return out
    }
}
