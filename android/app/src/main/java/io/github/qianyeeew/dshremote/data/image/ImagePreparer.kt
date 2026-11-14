package io.github.qianyeeew.dshremote.data.image

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/**
 * 把用户选的图片变成可以发出的文件（落地方案 C2，用户确认）：按拍摄方向转正，长边缩到 [ImageSizing.MAX_SIDE]；
 * 有透明像素的编成 PNG，其余编成 JPEG；编码后还超过 [OutgoingImage.MAX_BYTES] 就继续缩小。
 *
 * 读不出来（不是图片、文件没了、格式不支持）时返回 null。图片内容不写日志。
 */
class ImagePreparer(
    private val resolver: ContentResolver,
) {
    suspend fun prepare(uri: Uri): OutgoingImage? =
        withContext(Dispatchers.Default) {
            val decoded =
                try {
                    decode(uri)
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                } ?: return@withContext null
            try {
                encode(decoded)
            } finally {
                decoded.recycle()
            }
        }

    /** 解码并转正，长边不超过 [ImageSizing.MAX_SIDE]。 */
    private fun decode(uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 自己按 EXIF 的方向转正，也能读 HEIF
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (w, h) = ImageSizing.fit(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                // 要逐个读像素判断有没有透明，不能用硬件位图
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(uri)
        }

    private fun decodeLegacy(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val (w, h) = ImageSizing.fit(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, w, h) }
        val sampled = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val degrees = resolver.openInputStream(uri)?.use { rotationOf(it) } ?: 0
        val scaled = scaleTo(sampled, ImageSizing.MAX_SIDE)
        if (degrees == 0) return scaled
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (rotated !== scaled) scaled.recycle()
        return rotated
    }

    // 只在 Android 8.0、8.1 上用（9 起由 ImageDecoder 处理方向）。lint 提醒的是 7.1 以前框架版本的问题，
    // 这两个版本上的框架实现没有问题，不为它多引入一个依赖
    @SuppressLint("ExifInterface")
    private fun rotationOf(stream: java.io.InputStream): Int =
        try {
            when (android.media.ExifInterface(stream).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: IOException) {
            0
        }

    private fun encode(bitmap: Bitmap): OutgoingImage? {
        val png = hasTransparentPixel(bitmap)
        var side = max(bitmap.width, bitmap.height)
        while (true) {
            val scaled = scaleTo(bitmap, side, recycleSource = false)
            val bytes =
                ByteArrayOutputStream().use { out ->
                    if (png) {
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } else {
                        scaled.compress(Bitmap.CompressFormat.JPEG, ImageSizing.JPEG_QUALITY, out)
                    }
                    out.toByteArray()
                }
            val width = scaled.width
            val height = scaled.height
            if (scaled !== bitmap) scaled.recycle()
            if (bytes.size <= OutgoingImage.MAX_BYTES) {
                return OutgoingImage(
                    key = UUID.randomUUID().toString(),
                    mediaType = if (png) OutgoingImage.PNG else OutgoingImage.JPEG,
                    bytes = bytes,
                    width = width,
                    height = height,
                )
            }
            side = ImageSizing.nextSide(side) ?: return null
        }
    }

    private companion object {
        /** 等比缩到长边不超过 [side]。已经够小时原样返回。 */
        fun scaleTo(
            bitmap: Bitmap,
            side: Int,
            recycleSource: Boolean = true,
        ): Bitmap {
            val (w, h) = ImageSizing.fit(bitmap.width, bitmap.height, side)
            if (w == bitmap.width && h == bitmap.height) return bitmap
            val scaled = bitmap.scale(w, h)
            if (recycleSource && scaled !== bitmap) bitmap.recycle()
            return scaled
        }

        /** 有没有不是完全不透明的像素。很多截图声明了透明通道、其实全是不透明的，这种编成 JPEG 小得多。 */
        fun hasTransparentPixel(bitmap: Bitmap): Boolean {
            if (!bitmap.hasAlpha()) return false
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                if (row.any { it ushr 24 != 0xFF }) return true
            }
            return false
        }
    }
}
