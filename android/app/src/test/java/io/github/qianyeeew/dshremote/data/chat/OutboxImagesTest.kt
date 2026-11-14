package io.github.qianyeeew.dshremote.data.chat

import io.github.qianyeeew.dshremote.data.api.ImagePart
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePage
import io.github.qianyeeew.dshremote.data.api.MessagePart
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.image.ImageRef
import io.github.qianyeeew.dshremote.ui.chat.ChatBlock
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.chatBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

/** 落地方案 C2：带图片的消息怎么和会话里出现的消息对应，气泡里的图片从哪里读。 */
class OutboxImagesTest {
    private val image = OutgoingImage("k1", OutgoingImage.PNG, byteArrayOf(1), 4, 3)
    private val ref = ImagePart("ab".repeat(32), "image/png", 4, 3)

    private fun page(vararg messages: Message) = ChatTimeline().withLatest(MessagePage(messages.toList(), hasMore = false))

    private fun user(
        id: String,
        vararg parts: MessagePart,
    ) = Message(id, MessageRole.USER, parts.toList(), 0)

    @Test
    fun matchesByTextAndImageCount() {
        val withImage = OutgoingMessage("看图", PromptMode.QUEUE, listOf(image))
        val plain = OutgoingMessage("看图", PromptMode.QUEUE)
        val imageOnly = OutgoingMessage("", PromptMode.QUEUE, listOf(image))
        var box =
            Outbox()
                .add(withImage)
                .add(plain)
                .add(imageOnly)
                .reconcile(page())
        // 同样的文字、没有图片：对应的是不带图片的那条
        box = box.reconcile(page(user("u1", TextPart("看图"))))
        assertEquals(listOf(withImage, imageOnly), box.items.map { it.outgoing })
        box = box.reconcile(page(user("u1", TextPart("看图")), user("u2", ref)))
        assertEquals(listOf(withImage), box.items.map { it.outgoing })
        box = box.reconcile(page(user("u1", TextPart("看图")), user("u2", ref), user("u3", ref, TextPart("看图"))))
        assertEquals(emptyList<OutgoingMessage>(), box.items.map { it.outgoing })
    }

    @Test
    fun userBlocksCarryLocalAndRemoteImages() {
        val local = LocalMessage(OutgoingMessage("", PromptMode.QUEUE, listOf(image)), OutgoingStatus.SENDING)
        val blocks =
            chatBlocks(
                listOf(ChatItem.Entry("u1", user("u1", ref, TextPart("看图"))), ChatItem.Local("local:1", local)),
                hostId = "h1",
                sessionId = "s1",
            ).filterIsInstance<ChatBlock.User>()
        assertEquals("看图", blocks[0].text)
        assertEquals(listOf(ImageRef.Remote("h1", "s1", ref.id, 4, 3)), blocks[0].images)
        assertEquals("", blocks[1].text)
        assertEquals(listOf<ImageRef>(ImageRef.Local(image)), blocks[1].images)
    }
}
