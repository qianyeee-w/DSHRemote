package io.github.qianyeeew.dshremote.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** 一张要显示的图片：还没发出的（字节在手机上），或者会话里的（按引用从电脑读）。 */
sealed interface ImageRef {
    val width: Int
    val height: Int

    /** 在缓存里区分图片。 */
    val cacheKey: String

    class Local(
        val image: OutgoingImage,
    ) : ImageRef {
        override val width get() = image.width
        override val height get() = image.height
        override val cacheKey get() = "local:${image.key}"

        override fun equals(other: Any?) = other is Local && other.image.key == image.key

        override fun hashCode() = image.key.hashCode()

        override fun toString() = "Local(${image.key})"
    }

    data class Remote(
        val hostId: String,
        val sessionId: String,
        /** protocol.md 的图片 id（内容的 sha256），同一个 id 的内容永远不变。 */
        val imageId: String,
        override val width: Int,
        override val height: Int,
    ) : ImageRef {
        override val cacheKey get() = "remote:$hostId:$imageId"
    }
}

/**
 * 图片的缓存（落地方案 C2）：解码好的位图放在内存里（按占用的字节数限制），从电脑读来的原图放在 App 的私有缓存目录
 * `cache/images/<主机>/<图片 id>`（总量超过上限时删掉最久没用的；主机被删掉时整个目录一起删）。
 * 同一张图片同时被要多次时只读一次。
 *
 * [fetch] 从电脑读一张图片；读不到（不在线、不存在）时返回 null 或抛出异常，这次不缓存，下次再试。
 */
class ImageStore(
    cacheRoot: File,
    private val fetch: suspend (ImageRef.Remote) -> ByteArray?,
    private val maxDiskBytes: Long = DEFAULT_DISK_BYTES,
    memoryBytes: Int = defaultMemoryBytes(),
) {
    private val root = File(cacheRoot, "images")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val memory =
        object : LruCache<String, Bitmap>(memoryBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ) = value.allocationByteCount
        }

    /** 解码好的图片，长边大约 [maxSide] 像素（按档位取整，同一张图不会解很多份）。读不到时返回 null。 */
    suspend fun load(
        ref: ImageRef,
        maxSide: Int,
    ): Bitmap? {
        val bucket = bucketOf(maxSide)
        val key = "${ref.cacheKey}@$bucket"
        memory.get(key)?.let { return it }
        val bytes = bytesOf(ref) ?: return null
        val bitmap = withContext(Dispatchers.Default) { decode(bytes, bucket) } ?: return null
        memory.put(key, bitmap)
        return bitmap
    }

    /** 只留下还在的主机的缓存。 */
    suspend fun retainHosts(hostIds: Set<String>) =
        withContext(Dispatchers.IO) {
            root.listFiles()?.filter { it.name !in hostIds }?.forEach { it.deleteRecursively() }
        }

    private suspend fun bytesOf(ref: ImageRef): ByteArray? =
        when (ref) {
            is ImageRef.Local -> ref.image.bytes
            is ImageRef.Remote -> {
                val pending = inflight.computeIfAbsent(ref.cacheKey) { scope.async { readOrFetch(ref) } }
                try {
                    pending.await()
                } finally {
                    inflight.remove(ref.cacheKey, pending)
                }
            }
        }

    private suspend fun readOrFetch(ref: ImageRef.Remote): ByteArray? {
        val file = fileOf(ref) ?: return null
        if (file.isFile) {
            try {
                val bytes = file.readBytes()
                file.setLastModified(System.currentTimeMillis())
                return bytes
            } catch (_: IOException) {
                file.delete()
            }
        }
        val bytes =
            try {
                fetch(ref)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
        write(file, bytes)
        return bytes
    }

    private fun fileOf(ref: ImageRef.Remote): File? {
        // 两段都只能是安全的文件名（主机 id 是 UUID，图片 id 是十六进制），防止拼出别的路径
        if (!SAFE.matches(ref.hostId) || !IMAGE_ID.matches(ref.imageId)) return null
        return File(File(root, ref.hostId), ref.imageId)
    }

    private fun write(
        file: File,
        bytes: ByteArray,
    ) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
            trim()
        } catch (_: IOException) {
            // 缓存写不进去不影响显示
        }
    }

    /** 总量超过上限时，从最久没用的删起。 */
    private fun trim() {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxDiskBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val DEFAULT_DISK_BYTES = 200L * 1024 * 1024
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val IMAGE_ID = Regex("^[0-9a-f]{64}$")
        private val BUCKETS = intArrayOf(256, 512, 1024, 2048, 4096)

        private fun defaultMemoryBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /** 把要求的大小取到不小于它的档位。 */
        internal fun bucketOf(maxSide: Int): Int = BUCKETS.firstOrNull { it >= maxSide } ?: BUCKETS.last()

        /** 解码到长边大约 [maxSide]（不放大）；电脑上发的图片可能带拍摄方向，Android 9 起按方向转正。 */
        private fun decode(
            bytes: ByteArray,
            maxSide: Int,
        ): Bitmap? =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        val (w, h) = ImageSizing.fit(info.size.width, info.size.height, maxSide)
                        decoder.setTargetSize(w, h)
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        null
                    } else {
                        val (w, h) = ImageSizing.fit(bounds.outWidth, bounds.outHeight, maxSide)
                        val options =
                            BitmapFactory.Options().apply {
                                inSampleSize =
                                    ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, w, h)
                            }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                }
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
    }
}
