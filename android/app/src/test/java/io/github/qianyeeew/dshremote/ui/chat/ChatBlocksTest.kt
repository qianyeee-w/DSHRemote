package io.github.qianyeeew.dshremote.ui.chat

import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePart
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.ThinkingPart
import io.github.qianyeeew.dshremote.data.api.ToolPart
import io.github.qianyeeew.dshremote.data.api.ToolStatus
import io.github.qianyeeew.dshremote.data.chat.LocalMessage
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBlocksTest {
    private fun tool(
        name: String,
        vararg input: Pair<String, String>,
        status: ToolStatus = ToolStatus.DONE,
        output: String? = null,
        truncated: Boolean = false,
        id: String = name,
    ) = ToolPart(
        callId = id,
        name = name,
        status = status,
        input = JsonObject(input.associate { it.first to JsonPrimitive(it.second) }),
        output = output,
        truncated = truncated,
    )

    private fun user(
        id: String,
        text: String,
    ) = ChatItem.Entry(id, Message(id, MessageRole.USER, listOf(TextPart(text)), 0))

    private fun reply(
        id: String,
        vararg parts: MessagePart,
        draft: Boolean = false,
    ) = ChatItem.Entry(id, Message(id, MessageRole.ASSISTANT, parts.toList(), 0, draft = draft))

    @Test
    fun consecutiveThinkingAndToolsAcrossMessagesFoldIntoOneBlock() {
        val blocks =
            chatBlocks(
                listOf(
                    user("u1", "修一下"),
                    reply("a1", ThinkingPart("先看看"), tool("read", "file_path" to "/src/A.kt")),
                    reply("a2", tool("edit", "file_path" to "/src/A.kt", "old_string" to "a", "new_string" to "b", id = "e1")),
                    reply("a3", TextPart("改好了。"), tool("bash", "command" to "./gradlew test")),
                ),
            )
        assertEquals(listOf("u1", "steps:a1:0", "a3:0", "steps:a3:1"), blocks.map { it.key })
        val first = blocks[1] as ChatBlock.Steps
        assertEquals(listOf(StepAction.THINK, StepAction.READ, StepAction.EDIT), first.steps.map { it.action })
        assertEquals(1, first.changedFiles)
        assertEquals("改好了。", (blocks[2] as ChatBlock.Reply).text)
    }

    @Test
    fun userMessageEndsTheTurnSoStepsDoNotMergeAcrossIt() {
        val blocks =
            chatBlocks(
                listOf(
                    reply("a1", tool("read", "file_path" to "a")),
                    user("u1", "继续"),
                    reply("a2", tool("read", "file_path" to "b", id = "r2")),
                ),
            )
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is ChatBlock.Steps && blocks[2] is ChatBlock.Steps)
    }

    @Test
    fun localMessagesAreUserBlocksWithTheirStatus() {
        val local = LocalMessage(OutgoingMessage("你好", PromptMode.STEER), OutgoingStatus.SENT)
        val blocks = chatBlocks(listOf(ChatItem.Local("local:1", local)))
        assertEquals(ChatBlock.User("local:1", "你好", local), blocks.single())
    }

    @Test
    fun liveThinkingInADraftIsRunning() {
        val blocks = chatBlocks(listOf(reply("d", ThinkingPart("想"), draft = true)))
        val step = (blocks.single() as ChatBlock.Steps).steps.single()
        assertEquals(StepStatus.RUNNING, step.status)
        assertEquals(step, currentStep(blocks))
    }

    @Test
    fun blankTextAndThinkingAreSkipped() {
        assertEquals(emptyList<ChatBlock>(), chatBlocks(listOf(reply("a", TextPart("  "), ThinkingPart("")))))
    }

    @Test
    fun changedFilesCountsDistinctEditAndWritePaths() {
        val block =
            ChatBlock.Steps(
                "s",
                listOf(
                    stepOf("1", tool("edit", "file_path" to "/a/A.kt")),
                    stepOf("2", tool("edit", "file_path" to "/a/A.kt")),
                    stepOf("3", tool("write", "file_path" to "/a/B.kt")),
                    stepOf("4", tool("read", "file_path" to "/a/C.kt")),
                ),
            )
        assertEquals(2, block.changedFiles)
    }

    @Test
    fun toolsMapToActionAndTarget() {
        val cases =
            listOf(
                tool("bash", "command" to "./gradlew test\necho done") to (StepAction.RUN to "./gradlew test"),
                tool("pwsh", "command" to "Get-ChildItem") to (StepAction.RUN to "Get-ChildItem"),
                tool("read", "file_path" to "/home/u/src/Main.kt") to (StepAction.READ to "Main.kt"),
                tool("read_image", "file_path" to "C:\\shots\\a.png") to (StepAction.READ to "a.png"),
                tool("write", "file_path" to "/x/New.kt") to (StepAction.WRITE to "New.kt"),
                tool("edit", "file_path" to "/x/Old.kt") to (StepAction.EDIT to "Old.kt"),
                tool("grep", "pattern" to "seen.add") to (StepAction.SEARCH to "seen.add"),
                tool("glob", "pattern" to "**/*.kt") to (StepAction.SEARCH to "**/*.kt"),
                tool("web_fetch", "url" to "https://example.com") to (StepAction.OPEN to "https://example.com"),
                tool("web_search", "query" to "compose") to (StepAction.WEB_SEARCH to "compose"),
                tool("ask_user_question") to (StepAction.CALL to "ask_user_question"),
                tool("bash") to (StepAction.RUN to null),
            )
        for ((part, expected) in cases) {
            val step = stepOf("k", part)
            assertEquals(part.name, expected, step.action to step.target)
        }
    }

    @Test
    fun statusFollowsTheTool() {
        assertEquals(StepStatus.RUNNING, stepOf("k", tool("bash", status = ToolStatus.RUNNING)).status)
        assertEquals(StepStatus.FAILED, stepOf("k", tool("bash", status = ToolStatus.ERROR)).status)
        assertEquals(StepStatus.REJECTED, stepOf("k", tool("bash", status = ToolStatus.REJECTED)).status)
    }

    @Test
    fun resultsOnlyWhenTheDataIsComplete() {
        val edit = tool("edit", "file_path" to "A.kt", "old_string" to "a\nb\nc", "new_string" to "a\nB\nc\nd")
        assertEquals(StepResult.Changed(added = 2, removed = 1), stepOf("k", edit).result)
        val clipped = tool("edit", "file_path" to "A.kt", "old_string" to "a…", "new_string" to "b", truncated = true)
        assertNull(stepOf("k", clipped).result)
        assertEquals(StepResult.Lines(3), stepOf("k", tool("read", "file_path" to "A", output = "1\n2\n3\n")).result)
        assertNull(stepOf("k", tool("read", "file_path" to "A", output = "1\n2…", truncated = true)).result)
        assertNull(stepOf("k", tool("read", "file_path" to "A", output = "x", status = ToolStatus.ERROR)).result)
        assertEquals(StepResult.Changed(2, 0), stepOf("k", tool("write", "file_path" to "A", "content" to "x\ny\n")).result)
        // 命令没有可靠的结果（拿不到退出码和用时）
        assertNull(stepOf("k", tool("bash", "command" to "ls", output = "a\nb")).result)
    }

    @Test
    fun truncationIsDetectedPerString() {
        val part = tool("edit", "old_string" to "full", "new_string" to "cut…", truncated = true)
        assertEquals(false, isClipped(part, "full"))
        assertEquals(true, isClipped(part, "cut…"))
        // 没有 truncated 标记时，结尾本来就是省略号也不算截断
        assertEquals(false, isClipped(tool("edit"), "等等…"))
    }

    @Test
    fun lineDiffMarksAddedRemovedAndContext() {
        val diff = lineDiff("fun a() {\n    old()\n}", "fun a() {\n    new()\n    more()\n}")
        assertEquals(
            listOf(
                DiffLine(DiffKind.CONTEXT, "fun a() {"),
                DiffLine(DiffKind.REMOVED, "    old()"),
                DiffLine(DiffKind.ADDED, "    new()"),
                DiffLine(DiffKind.ADDED, "    more()"),
                DiffLine(DiffKind.CONTEXT, "}"),
            ),
            diff,
        )
        assertEquals(listOf(DiffLine(DiffKind.ADDED, "x")), lineDiff("", "x"))
        assertEquals(listOf(DiffLine(DiffKind.REMOVED, "x")), lineDiff("x", ""))
    }

    @Test
    fun outputTailKeepsTheLastLines() {
        val tail = outputTail("1\n2\n3\n4\n5\n6\n\n")
        assertEquals(listOf("3", "4", "5", "6"), tail.lines)
        assertEquals(6, tail.total)
        assertEquals(OutputTail(listOf("only"), 1), outputTail("only"))
    }

    @Test
    fun fileNameHandlesBothSeparators() {
        assertEquals("A.kt", fileName("/x/y/A.kt"))
        assertEquals("B.kt", fileName("C:\\x\\B.kt"))
        assertEquals("dir", fileName("/x/dir/"))
    }

    @Test
    fun currentStepIsTheLastRunningStepAtTheEnd() {
        val running = tool("bash", "command" to "npm test", status = ToolStatus.RUNNING, id = "b")
        val blocks = chatBlocks(listOf(reply("a", tool("read", "file_path" to "A"), running)))
        assertEquals("npm test", currentStep(blocks)?.target)
        // 后面已经有回复文字：不再有正在做的步骤
        assertNull(currentStep(chatBlocks(listOf(reply("a", running, TextPart("好了"))))))
    }
}
