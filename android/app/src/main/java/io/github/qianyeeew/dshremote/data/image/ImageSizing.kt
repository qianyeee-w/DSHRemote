package io.github.qianyeeew.dshremote.data.image

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片的尺寸计算（落地方案 C2）：发出前压缩、显示缩略图时按需解码。只有纯计算，单元测试覆盖。
 */
object ImageSizing {
    /** 发出前把长边缩到不超过多少像素（用户确认）。 */
    const val MAX_SIDE = 2048

    /** 编码后还超过单张上限时，每次再缩小到多少。 */
    const val SHRINK_STEP = 0.75

    /** JPEG 的质量。 */
    const val JPEG_QUALITY = 85

    /** 长边不超过 [maxSide]，等比缩小；本来就小的不放大。宽高至少 1。 */
    fun fit(
        width: Int,
        height: Int,
        maxSide: Int = MAX_SIDE,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSide > 0)
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * 解码时的 inSampleSize：2 的幂，解出来的图两边都不小于目标（之后再精确缩放），省内存。
     */
    fun sampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** 编码后超过上限时，下一次试的长边（[current] 的 [SHRINK_STEP] 倍）；太小了返回 null，放弃这张图。 */
    fun nextSide(current: Int): Int? = (current * SHRINK_STEP).toInt().takeIf { it >= MIN_SIDE }

    /** 再小就看不清了。 */
    private const val MIN_SIDE = 256

    /**
     * 气泡里单独一张图片显示多大（dp）：宽不超过 [maxWidth]、高不超过 [maxHeight]，保持比例；太窄或太矮的图
     * 两边都不小于 [minSide]（这时会裁切）。
     */
    fun bubbleSize(
        width: Int,
        height: Int,
        maxWidth: Float,
        maxHeight: Float,
        minSide: Float,
    ): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return maxWidth to maxWidth * 3 / 4
        val scale = minOf(maxWidth / width, maxHeight / height)
        return (width * scale).coerceIn(minSide, maxWidth) to (height * scale).coerceIn(minSide, maxHeight)
    }
}
