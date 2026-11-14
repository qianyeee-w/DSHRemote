package io.github.qianyeeew.dshremote.data.image

import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelOption
import io.github.qianyeeew.dshremote.data.api.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片的尺寸计算和「能不能加图片」的规则（落地方案 C2）。 */
class ImageSizingTest {
    @Test
    fun longSideIsCappedAt2048WithoutUpscaling() {
        assertEquals(2048 to 1536, ImageSizing.fit(4032, 3024))
        assertEquals(1152 to 2048, ImageSizing.fit(2160, 3840))
        assertEquals(800 to 600, ImageSizing.fit(800, 600))
        assertEquals(2048 to 2048, ImageSizing.fit(2048, 2048))
        // 极端的长条图：短边至少 1
        assertEquals(2048 to 1, ImageSizing.fit(100_000, 10))
    }

    @Test
    fun sampleSizeKeepsBothSidesAboveTheTarget() {
        assertEquals(1, ImageSizing.sampleSize(2048, 1536, 2048, 1536))
        assertEquals(2, ImageSizing.sampleSize(8064, 6048, 2048, 1536))
        assertEquals(4, ImageSizing.sampleSize(8192, 8192, 2048, 2048))
        assertEquals(1, ImageSizing.sampleSize(3000, 2000, 2048, 1365))
    }

    @Test
    fun shrinkingGivesUpWhenTooSmall() {
        assertEquals(1536, ImageSizing.nextSide(2048))
        assertEquals(256, ImageSizing.nextSide(342))
        assertNull(ImageSizing.nextSide(300))
    }

    @Test
    fun bubbleSizeKeepsAspectWithinBounds() {
        assertEquals(240f to 180f, ImageSizing.bubbleSize(4000, 3000, 240f, 280f, 72f))
        assertEquals(140f to 280f, ImageSizing.bubbleSize(1000, 2000, 240f, 280f, 72f))
        // 很窄的长图：宽不小于 72
        assertEquals(72f to 280f, ImageSizing.bubbleSize(100, 4000, 240f, 280f, 72f))
        // 小图也放大到气泡宽度
        assertEquals(240f to 120f, ImageSizing.bubbleSize(20, 10, 240f, 280f, 72f))
    }

    @Test
    fun imageSupportFollowsPluginAndModel() {
        val list =
            ModelList(
                default = ModelRef("p", "a"),
                models = listOf(option("a", true), option("b", false), option("c", null)),
                imageInput = true,
            )
        assertEquals(ImageSupport.CHECKING, ImageSupport.of(null, ModelRef("p", "a")))
        assertEquals(ImageSupport.OLD_PLUGIN, ImageSupport.of(list.copy(imageInput = false), ModelRef("p", "a")))
        assertEquals(ImageSupport.YES, ImageSupport.of(list, ModelRef("p", "a")))
        assertEquals(ImageSupport.TEXT_ONLY_MODEL, ImageSupport.of(list, ModelRef("p", "b")))
        assertEquals(ImageSupport.UNKNOWN, ImageSupport.of(list, ModelRef("p", "c")))
        assertEquals(ImageSupport.UNKNOWN, ImageSupport.of(list, ModelRef("p", "missing")))
        assertEquals("没说用哪个模型时看默认模型", ImageSupport.YES, ImageSupport.of(list, null))
        assertTrue(ImageSupport.YES.canAdd && ImageSupport.UNKNOWN.canAdd)
        assertFalse(ImageSupport.CHECKING.canAdd || ImageSupport.OLD_PLUGIN.canAdd || ImageSupport.TEXT_ONLY_MODEL.canAdd)
    }

    @Test
    fun memoryBucketsRoundUp() {
        assertEquals(256, ImageStore.bucketOf(56))
        assertEquals(512, ImageStore.bucketOf(257))
        assertEquals(4096, ImageStore.bucketOf(10_000))
    }

    private fun option(
        model: String,
        images: Boolean?,
    ) = ModelOption("p", "P", model, model, images = images)
}
