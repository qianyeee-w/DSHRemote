package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelOption
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPreset
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.ui.chat.ChatHandoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 新任务的三步：创建会话、设置模型和审批方式（和默认不同时）、发出第一条消息；每一步失败时怎么处理（落地方案 A5）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewTaskFlowTest {
    private val project = ProjectPick.Existing(Workspace("w1", "DSHRemote", "/code/dshremote"))
    private val deepseek = ModelRef("deepseek", "deepseek-chat")
    private val reasoner = ModelRef("deepseek", "deepseek-reasoner")
    private val backend = FakeBackend()
    private val saved = mutableListOf<String>()
    private val flow = NewTaskFlow(CoroutineScope(UnconfinedTestDispatcher()), backend) { saved += it }

    @Test
    fun allStepsSucceedWithDefaults() {
        flow.loadOptions()
        assertTrue(flow.start("给通知加按钮", project))
        assertEquals(listOf<String?>("w1"), backend.created)
        // 和默认相同：不设置
        assertTrue(backend.modelChanges.isEmpty())
        assertTrue(backend.presetChanges.isEmpty())
        val (sessionId, message) = backend.prompts.single()
        assertEquals("s_new", sessionId)
        assertEquals("给通知加按钮", message.text)
        assertEquals(PromptMode.QUEUE, message.mode)
        assertEquals(StartedTask("s_new", null), flow.state.value.started)
        assertFalse(flow.state.value.starting)
        // 记下这次用的项目，下次默认用它
        assertEquals(listOf("w1"), saved)
    }

    @Test
    fun modelAndPresetAreSetOnlyWhenTheyDifferFromTheDefault() {
        flow.loadOptions()
        flow.chooseModel(reasoner)
        flow.choosePreset("workspace-write")
        flow.start("任务", ProjectPick.None)
        assertEquals(listOf<String?>(null), backend.created)
        assertEquals(listOf("s_new" to reasoner), backend.modelChanges)
        assertEquals(emptyList<Pair<String, String>>(), backend.presetChanges) // 和默认相同
        assertEquals(1, backend.prompts.size)
        assertEquals(listOf(NO_PROJECT), saved)

        val other = NewTaskFlow(CoroutineScope(UnconfinedTestDispatcher()), backend)
        other.loadOptions()
        other.choosePreset("danger-full-access")
        other.start("任务", project)
        assertEquals("s_new" to "danger-full-access", backend.presetChanges.single())
    }

    /** 第 1 步失败：留在首页，没有会话，提示原因；可以再点发送。 */
    @Test
    fun createFailureStaysOnHomeAndCanBeRetried() {
        backend.createError = OfflineException()
        assertTrue(flow.start("任务", project))
        assertEquals(CreateError.OFFLINE, flow.state.value.error)
        assertNull(flow.state.value.started)
        assertFalse(flow.state.value.starting)
        assertTrue(backend.prompts.isEmpty())

        backend.createError = ApiException.Http(503, ErrorCode.DSH_UNAVAILABLE, null)
        flow.start("任务", project)
        assertEquals(CreateError.DSH_UNAVAILABLE, flow.state.value.error)

        backend.createError = null
        flow.start("任务", project)
        assertEquals(StartedTask("s_new", null), flow.state.value.started)
        assertNull(flow.state.value.error)
    }

    /** 第 2 步失败：会话已经有了，不再重复创建，也不发消息；进入会话，文字放回输入框。 */
    @Test
    fun setupFailureEntersTheSessionWithTheTextNotSent() {
        flow.loadOptions()
        flow.chooseModel(reasoner)
        backend.setupError = ApiException.Network(IOException("x"))
        flow.start("任务", project)
        assertEquals(StartedTask("s_new", ChatHandoff.NotSent("任务")), flow.state.value.started)
        assertEquals(1, backend.created.size)
        assertTrue(backend.prompts.isEmpty())
    }

    /** 第 3 步失败：进入会话，显示成发送失败的气泡，重试用同一条消息（同一个 requestId）。 */
    @Test
    fun promptFailureHandsTheSameMessageToTheSession() {
        backend.promptError = ApiException.Network(IOException("x"))
        flow.start("任务", project)
        val handoff =
            flow.state.value.started
                ?.handoff as ChatHandoff.FailedFirstMessage
        assertEquals(backend.prompts.single().second, handoff.message)
        assertEquals(1, backend.created.size)
        // 请求写出后断开：不确定是否送达，不自动重发（落地方案 B3）
        assertEquals(OutgoingStatus.UNCERTAIN, handoff.status)
    }

    /** 连不上：确定没送到，会话里是「发送失败」。 */
    @Test
    fun promptThatNeverLeftIsAPlainFailure() {
        backend.promptError = ApiException.Network(IOException("refused"), maybeDelivered = false)
        flow.start("任务", project)
        val handoff =
            flow.state.value.started
                ?.handoff as ChatHandoff.FailedFirstMessage
        assertEquals(OutgoingStatus.FAILED, handoff.status)
        assertEquals(1, backend.prompts.size)
    }

    /** 开始中再点不会创建第二个会话；没写内容或者太长不开始。 */
    @Test
    fun cannotStartTwiceOrWithoutText() {
        backend.gate = CompletableDeferred()
        assertTrue(flow.start("任务", project))
        assertTrue(flow.state.value.starting)
        assertFalse(flow.start("任务", project))
        backend.gate!!.complete(Unit)
        assertEquals(1, backend.created.size)
        assertFalse(flow.start("   ", project))
        assertFalse(flow.start("x".repeat(OutgoingMessage.MAX_TEXT + 1), project))
    }

    @Test
    fun consumingClearsTheChoicesButKeepsTheLoadedLists() {
        flow.loadOptions()
        flow.chooseModel(reasoner)
        flow.chooseProject(ProjectPick.None)
        flow.start("任务", ProjectPick.None)
        flow.consumeStarted()
        val state = flow.state.value
        assertNull(state.started)
        assertNull(state.model)
        assertNull(state.project)
        assertEquals(deepseek, state.models?.default)
    }

    /** 默认是上一次用的项目；还没用过时用第一个项目；一个项目都没有时不指定。 */
    @Test
    fun defaultProjectIsTheLastUsedOne() {
        val a = Workspace("a", "A", "/a")
        val b = Workspace("b", "B", "/b")
        assertEquals(ProjectPick.Existing(b), defaultProject(listOf(a, b), "b"))
        assertEquals(ProjectPick.None, defaultProject(listOf(a, b), NO_PROJECT))
        assertEquals(ProjectPick.Existing(a), defaultProject(listOf(a, b), null))
        assertEquals(ProjectPick.Existing(a), defaultProject(listOf(a, b), "removed"))
        assertEquals(ProjectPick.None, defaultProject(emptyList(), null))
    }

    private inner class FakeBackend : SessionCreator {
        val created = mutableListOf<String?>()
        val modelChanges = mutableListOf<Pair<String, ModelRef>>()
        val presetChanges = mutableListOf<Pair<String, String>>()
        val prompts = mutableListOf<Pair<String, OutgoingMessage>>()
        var createError: Exception? = null
        var setupError: Exception? = null
        var promptError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun workspaces() = emptyList<Workspace>()

        override suspend fun create(workspaceId: String?): String {
            created += workspaceId
            gate?.await()
            createError?.let { throw it }
            return "s_new"
        }

        override suspend fun models() =
            ModelList(
                default = deepseek,
                models =
                    listOf(
                        ModelOption("deepseek", "DeepSeek", "deepseek-chat", "Chat"),
                        ModelOption("deepseek", "DeepSeek", "deepseek-reasoner", "Reasoner"),
                    ),
            )

        override suspend fun presets() =
            PermissionPresetList(
                default = "workspace-write",
                presets =
                    listOf(
                        PermissionPreset("workspace-write", "workspace-write"),
                        PermissionPreset("danger-full-access", "danger-full-access"),
                    ),
            )

        override suspend fun setModel(
            sessionId: String,
            model: ModelRef,
        ) {
            setupError?.let { throw it }
            modelChanges += sessionId to model
        }

        override suspend fun setPreset(
            sessionId: String,
            preset: String,
        ) {
            setupError?.let { throw it }
            presetChanges += sessionId to preset
        }

        override suspend fun prompt(
            sessionId: String,
            message: OutgoingMessage,
        ) {
            prompts += sessionId to message
            promptError?.let { throw it }
        }
    }
}
