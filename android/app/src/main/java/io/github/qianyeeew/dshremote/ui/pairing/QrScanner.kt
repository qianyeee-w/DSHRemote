package io.github.qianyeeew.dshremote.ui.pairing

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * 相机预览加二维码识别（CameraX + ZXing，不依赖 Google Play 服务，android/AGENTS.md「技术选型」）。
 * 每识别出一帧就回调一次 [onText]（在主线程），由调用方决定是否忽略重复的结果。
 */
@Composable
fun QrScanner(
    onText: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(analysisExecutor) { image ->
                                decode(image)?.let { text -> mainExecutor.execute { if (!disposed) latestOnText(text) } }
                            }
                        }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: ExecutionException) {
                latestOnUnavailable()
            } catch (_: IllegalArgumentException) {
                // 没有后置相机
                latestOnUnavailable()
            } catch (_: IllegalStateException) {
                latestOnUnavailable()
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun decode(image: ImageProxy): String? =
    image.use {
        // YUV_420_888 的第一个平面就是亮度，正好是 ZXing 需要的
        val plane = it.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        QrDecoder.decode(bytes, plane.rowStride, it.width, it.height)
    }

/** 从亮度数据中识别二维码。单独拿出来，方便在 JVM 上测试。 */
internal object QrDecoder {
    private val hints =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            // 终端里打印的二维码在深色背景上是反色的（dev-server 的 pair 命令）
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )

    /** [rowStride] 是每行的字节数（可能大于 [width]）。识别不出来时返回 null。 */
    fun decode(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        }
    }
}
