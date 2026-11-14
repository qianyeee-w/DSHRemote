package io.github.qianyeeew.dshremote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ImagePart
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.image.ImagePreparer
import io.github.qianyeeew.dshremote.data.image.ImageRef
import io.github.qianyeeew.dshremote.data.image.ImageStore
import io.github.qianyeeew.dshremote.data.image.ImageSupport
import io.github.qianyeeew.dshremote.ui.chat.ChatActions
import io.github.qianyeeew.dshremote.ui.chat.ChatComposer
import io.github.qianyeeew.dshremote.ui.chat.ChatContent
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import io.github.qianyeeew.dshremote.ui.chat.LocalImageStore
import io.github.qianyeeew.dshremote.ui.chat.Picker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/** 图片附件（落地方案 C2）：输入框的加号和缩略图、气泡里的图片和大图，以及手机上的压缩。 */
@RunWith(AndroidJUnit4::class)
class ImageUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val imageId = "ab".repeat(32)

    // ---------------------------------------------------------------- 输入框

    @Test
    fun unavailablePlusExplainsWhy() {
        var explained = 0
        renderComposer(
            onlineChatState().copy(imageSupport = ImageSupport.TEXT_ONLY_MODEL),
            ChatActions(onImagesUnavailable = { explained++ }),
        )
        compose.onNodeWithContentDescription(text(R.string.image_add_unavailable)).assertIsDisplayed()
        compose.onNodeWithTag("chat-add-image").performClick()
        compose.runOnIdle { assertEquals(1, explained) }
    }

    @Test
    fun availablePlusIsShownInBothComposerStates() {
        renderComposer(onlineChatState().copy(imageSupport = ImageSupport.YES))
        // 收起时一行：加号、占位文字、发送键；写字时加号还在输入框左边（键盘弹出时下面的配置行会收起）
        compose.onNodeWithContentDescription(text(R.string.image_add)).assertIsDisplayed()
        compose.onNodeWithTag("chat-input").performClick()
        compose.onNodeWithContentDescription(text(R.string.image_add)).assertIsDisplayed()
    }

    /** 加了图片时输入框一直展开；没写字也能发；右上角的叉移除。 */
    @Test
    fun addedImagesShowAsThumbnailsAndCanBeSentAlone() {
        val removed = CopyOnWriteArrayList<String>()
        val state =
            onlineChatState().copy(
                imageSupport = ImageSupport.YES,
                images = listOf(outgoing("a", Color.rgb(200, 80, 40)), outgoing("b", Color.rgb(40, 120, 200))),
                preparingImages = 1,
            )
        renderComposer(
            state,
            ChatActions(onRemoveImage = { removed += it }, onSend = { true }),
        )
        compose.onNodeWithTag("chat-composer-options").assertIsDisplayed()
        compose.onAllNodesWithTag("chat-image").assertCountEquals(2)
        compose.onNodeWithTag("chat-image-preparing").assertIsDisplayed()
        save("c2-composer-images")
        compose.onAllNodesWithTag("chat-image-remove").onFirst().performClick()
        compose.runOnIdle { assertEquals(listOf("a"), removed) }
        // 还在压缩时先不能发
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
    }

    @Test
    fun imagesWithoutTextCanBeSent() {
        val sent = CopyOnWriteArrayList<String>()
        renderComposer(
            onlineChatState().copy(imageSupport = ImageSupport.YES, images = listOf(outgoing("a", Color.GREEN))),
            ChatActions(onSend = {
                sent += it
                true
            }),
        )
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(""), sent) }
    }

    // ---------------------------------------------------------------- 气泡和大图

    @Test
    fun bubbleImagesLoadByReferenceAndOpenFullScreen() {
        val fetched = CopyOnWriteArrayList<ImageRef.Remote>()
        val png = png(640, 480, Color.rgb(90, 160, 90))
        val store = store { ref -> png.also { fetched += ref } }
        val state =
            chatState(
                Message("u1", MessageRole.USER, listOf(ImagePart(imageId, "image/png", 640, 480), TextPart("这个界面对吗")), 0, turn = 1),
                Message(
                    "u2",
                    MessageRole.USER,
                    listOf(ImagePart("cd".repeat(32), "image/png", 640, 480), ImagePart(imageId, "image/png", 640, 480)),
                    0,
                    turn = 2,
                ),
            )
        renderChat(state, store)
        compose.waitUntil(5_000) { fetched.isNotEmpty() }
        compose.onNodeWithText("这个界面对吗").assertIsDisplayed()
        compose.onAllNodesWithTag("bubble-image").assertCountEquals(3)
        // 只有图片的消息没有文字气泡
        compose.onAllNodesWithTag("user-bubble").assertCountEquals(1)
        compose.runOnIdle { assertTrue(fetched.all { it.hostId == "host-1" && it.sessionId == "visual-test" }) }
        save("c2-bubble-images")
        compose.onAllNodesWithTag("bubble-image").onFirst().performClick()
        compose.onNodeWithTag("image-viewer").assertIsDisplayed()
        compose.onNodeWithTag("image-viewer-close").performClick()
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
    }

    @Test
    fun unreadableImageSaysSoAndRetriesOnTap() {
        var fail = true
        val png = png(64, 64, Color.BLUE)
        val store = store { if (fail) null else png }
        renderChat(chatState(Message("u1", MessageRole.USER, listOf(ImagePart(imageId, "image/png", 64, 64)), 0)), store)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("bubble-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.image_failed)).assertIsDisplayed()
        fail = false
        compose.onNodeWithTag("bubble-image").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text(R.string.image_failed)).fetchSemanticsNodes().isEmpty() }
    }

    // ---------------------------------------------------------------- 压缩

    /** 手机上的压缩（用户确认）：长边缩到 2048；不透明的编成 JPEG，有透明像素的编成 PNG。 */
    @Test
    fun preparerShrinksAndPicksTheFormat() =
        runBlocking<Unit> {
            val preparer = ImagePreparer(context.contentResolver)
            val photo = preparer.prepare(file("photo.png", png(4000, 3000, Color.rgb(10, 120, 200))))
            assertNotNull(photo)
            photo!!
            assertEquals(OutgoingImage.JPEG, photo.mediaType)
            assertEquals(2048 to 1536, photo.width to photo.height)
            assertDecodes(photo)

            // 声明了透明通道、其实全是不透明的截图也编成 JPEG
            val opaqueWithAlpha = preparer.prepare(file("screenshot.png", png(1080, 2400, Color.WHITE)))!!
            assertEquals(OutgoingImage.JPEG, opaqueWithAlpha.mediaType)
            assertEquals(2048, max(opaqueWithAlpha.width, opaqueWithAlpha.height))

            val transparent = preparer.prepare(file("logo.png", png(3000, 1000, Color.argb(120, 200, 0, 0))))!!
            assertEquals(OutgoingImage.PNG, transparent.mediaType)
            assertEquals(2048 to 683, transparent.width to transparent.height)
            assertDecodes(transparent)

            val small = preparer.prepare(file("small.png", png(300, 200, Color.RED)))!!
            assertEquals("小图不放大", 300 to 200, small.width to small.height)

            assertNull("不是图片", preparer.prepare(file("not-image.png", "hello".toByteArray())))
            assertNull("文件不存在", preparer.prepare(Uri.fromFile(File(context.cacheDir, "missing.png"))))
        }

    /** 拍照的方向（EXIF）：转正以后宽高对调。 */
    @Test
    fun preparerAppliesCameraOrientation() =
        runBlocking<Unit> {
            val jpeg =
                ByteArrayOutputStream().use { out ->
                    Bitmap
                        .createBitmap(
                            400,
                            200,
                            Bitmap.Config.ARGB_8888,
                        ).apply { eraseColor(Color.GRAY) }
                        .compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }
            val target = File(context.cacheDir, "rotated.jpg").apply { writeBytes(jpeg) }
            android.media.ExifInterface(target.absolutePath).apply {
                setAttribute(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_ROTATE_90
                        .toString(),
                )
                saveAttributes()
            }
            val prepared = ImagePreparer(context.contentResolver).prepare(Uri.fromFile(target))!!
            assertEquals(200 to 400, prepared.width to prepared.height)
        }

    // ---------------------------------------------------------------- 工具

    private fun text(id: Int) = context.getString(id)

    private fun store(fetch: suspend (ImageRef.Remote) -> ByteArray?): ImageStore =
        ImageStore(File(context.cacheDir, "image-ui-test-${System.nanoTime()}"), fetch)

    private fun renderComposer(
        state: ChatState,
        actions: ChatActions = ChatActions(),
    ) {
        val store = store { null }
        compose.setContent {
            val focus = LocalFocusManager.current
            CompositionLocalProvider(LocalImageStore provides store) {
                DshRemoteTheme {
                    Column {
                        TextButton(onClick = { focus.clearFocus() }) { Text("移开焦点") }
                        ChatComposer(state, actions)
                    }
                }
            }
        }
    }

    private fun renderChat(
        state: ChatState,
        store: ImageStore,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalImageStore provides store) {
                DshRemoteTheme { ChatContent(state, notice = null, picker = Picker.Closed, actions = ChatActions()) }
            }
        }
    }

    private fun chatState(vararg messages: Message): ChatState =
        onlineChatState().copy(
            hostId = "host-1",
            sessionId = "visual-test",
            items = messages.map { ChatItem.Entry(it.id, it) },
            imageSupport = ImageSupport.YES,
        )

    private fun png(
        width: Int,
        height: Int,
        color: Int,
    ): ByteArray =
        ByteArrayOutputStream().use { out ->
            Bitmap
                .createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(color) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    private fun outgoing(
        key: String,
        color: Int,
    ) = OutgoingImage(key, OutgoingImage.PNG, png(120, 90, color), 120, 90)

    private fun file(
        name: String,
        bytes: ByteArray,
    ): Uri = Uri.fromFile(File(context.cacheDir, name).apply { writeBytes(bytes) })

    private fun assertDecodes(image: OutgoingImage) {
        val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        assertEquals(image.width to image.height, bitmap.width to bitmap.height)
        assertTrue(image.bytes.size <= OutgoingImage.MAX_BYTES)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "visual-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
