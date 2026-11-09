package io.github.qianyeeew.dshremote.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 二维码识别：用 ZXing 生成二维码的亮度数据，模拟相机的一帧。 */
class QrDecoderTest {
    private val uri =
        "dshremote://pair?v=1&h=192.168.3.26&p=3290&fp=Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M&c=K7M2Q9XP&n=%E4%B9%A6%E6%88%BF"

    @Test
    fun decodesFrameWithRowPadding() {
        val (bytes, stride, size) = frame(uri, padding = 64)
        assertEquals(uri, QrDecoder.decode(bytes, stride, size, size))
    }

    @Test
    fun decodesInvertedCode() {
        // 深色背景的终端里打印出来的二维码是反色的
        val (bytes, stride, size) = frame(uri, inverted = true)
        assertEquals(uri, QrDecoder.decode(bytes, stride, size, size))
    }

    @Test
    fun blankFrameHasNoCode() {
        assertNull(QrDecoder.decode(ByteArray(200 * 200) { 0x7f }, 200, 200, 200))
    }

    /** 白底黑码（或反色），每行末尾补 [padding] 字节，最后一行不补（和相机的缓冲区一样）。 */
    private fun frame(
        text: String,
        size: Int = 400,
        padding: Int = 0,
        inverted: Boolean = false,
    ): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val stride = size + padding
        val bytes = ByteArray(stride * (size - 1) + size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dark = matrix[x, y] != inverted
                bytes[y * stride + x] = if (dark) 0 else 0xff.toByte()
            }
        }
        return Triple(bytes, stride, size)
    }
}
