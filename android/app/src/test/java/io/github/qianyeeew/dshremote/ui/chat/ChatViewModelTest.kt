package io.github.qianyeeew.dshremote.ui.chat

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.MessagePage
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.data.live.SessionEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** 会话详情：订阅和重新读取、分页、发消息（插话 / 排队、失败重试）、停止、切换模型和权限模式。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val address = HostAddress("192.168.1.5", 3190)
    private val states = MutableStateFlow(live(SessionStatus.IDLE))
    private val backend = FakeBackend()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun subscribesOnlyWhileVisibleAndLoadsLatestPageOnResync() {
        val vm = viewModel()
        assertEquals(0, backend.subscriptions)
        vm.onVisible()
        vm.onVisible()
        assertEquals(1, backend.subscriptions)

        backend.pages[null] = MessagePage(listOf(msg("m1"), msg("m2")), hasMore = true, cursor = "c1")
        backend.push(SessionEvent.Resync(SESSION))
        assertEquals(listOf("m1", "m2"), vm.keys())
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.hasMore)

        backend.push(upsert(msg("m3"), 1))
        assertEquals(listOf("m1", "m2", "m3"), vm.keys())

        vm.onHidden()
        assertEquals(0, backend.active)
    }

    @Test
    fun failedLoadCanBeRetried() {
        val vm = viewModel().also { it.onVisible() }
        backend.failNext = true
        backend.push(SessionEvent.Resync(SESSION))
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.loaded)

        backend.pages[null] = MessagePage(listOf(msg("m1")), hasMore = false, cursor = null)
        vm.retryLoad()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf("m1"), vm.keys())
    }

    @Test
    fun olderPagesArePrepended() {
        val vm = visible(MessagePage(listOf(msg("m3")), hasMore = true, cursor = "c1"))
        backend.pages["c1"] = MessagePage(listOf(msg("m1"), msg("m2")), hasMore = false, cursor = null)
        vm.loadOlder()
        assertEquals(listOf("m1", "m2", "m3"), vm.keys())
        assertFalse(vm.state.value.hasMore)
        // 没有更多时不再请求
        val requests = backend.pageRequests.size
        vm.loadOlder()
        assertEquals(requests, backend.pageRequests.size)
    }

    @Test
    fun sendWhileRunningAlwaysSteersAndStartsImmediatelyWhenIdle() {
        val vm = visible()
        assertTrue(vm.send("空闲时"))
        assertEquals(PromptMode.QUEUE, backend.prompts.last().mode)

        states.value = live(SessionStatus.RUNNING)
        assertTrue(vm.state.value.running)
        assertTrue(vm.send("插话"))
        assertEquals(PromptMode.STEER, backend.prompts.last().mode)

        assertFalse(vm.send("   "))
        assertFalse(vm.send("x".repeat(OutgoingMessage.MAX_TEXT + 1)))
    }

    @Test
    fun projectNameComesFromTheRegisteredWorkspaceOrTheDirectory() {
        val session = SessionSummary("s", "标题", workspaceId = "w1", cwd = "/home/u/code/dshremote", updatedAt = 0)
        assertEquals("DSHRemote", projectName(session, listOf(Workspace("w1", "DSHRemote", "/home/u/code/dshremote"))))
        assertEquals("dshremote", projectName(session, emptyList()))
        assertEquals(null, projectName(session.copy(cwd = null), emptyList()))
        assertEquals(null, projectName(null, emptyList()))
    }

    @Test
    fun cannotSendBeforeHistoryIsLoadedOrWhileOffline() {
        val vm = viewModel()
        vm.onVisible()
        assertFalse("还没读到历史", vm.send("hi"))
        // 重新读取时要有一页历史；没有时假后端会抛出测试框架之外的异常，算到后面的测试头上
        backend.pages[null] = MessagePage(emptyList(), hasMore = false, cursor = null)
        backend.push(SessionEvent.Resync(SESSION))
        states.value = mapOf("h1" to HostLiveState(LinkStatus.Connecting))
        assertFalse("不在线", vm.send("hi"))
        assertTrue(backend.prompts.isEmpty())
    }

    @Test
    fun localMessageShowsStatusAndDisappearsWhenItAppearsInTheLog() {
        val vm = visible(MessagePage(listOf(msg("u0", "你好", MessageRole.USER)), hasMore = false, cursor = null))
        backend.promptGate = CompletableDeferred()
        vm.send("你好")
        assertEquals(OutgoingStatus.SENDING, vm.locals().single().status)
        backend.promptGate!!.complete(Unit)
        assertEquals(OutgoingStatus.SENT, vm.locals().single().status)

        // 历史里原来就有一条「你好」，不算；新出现的「你好」才对应这条本地消息
        backend.push(upsert(msg("u1", "你好", MessageRole.USER), 1))
        assertTrue(vm.locals().isEmpty())
        assertEquals(listOf("u0", "u1"), vm.keys())
    }

    @Test
    fun failedSendIsRetriedWithTheSameRequestId() {
        val vm = visible()
        backend.promptError = ApiException.Network(IOException("x"))
        vm.send("重要的消息")
        val failed = vm.locals().single()
        assertEquals(OutgoingStatus.FAILED, failed.status)

        backend.promptError = null
        vm.retrySend(failed.outgoing.requestId)
        assertEquals(OutgoingStatus.SENT, vm.locals().single().status)
        assertEquals(2, backend.prompts.size)
        assertEquals(backend.prompts[0].requestId, backend.prompts[1].requestId)
    }

    @Test
    fun sameTextSentTwiceMatchesInOrder() {
        val vm = visible()
        vm.send("继续")
        vm.send("继续")
        val first = vm.locals()[0].outgoing.requestId
        backend.push(upsert(msg("u1", "继续", MessageRole.USER), 1))
        assertEquals(1, vm.locals().size)
        assertTrue(
            vm
                .locals()
                .single()
                .outgoing.requestId != first,
        )
    }

    @Test
    fun cancelAndPickerErrorsBecomeNotices() {
        val vm = visible()
        vm.cancelRun()
        assertEquals(1, backend.cancels)
        assertNull(vm.notice.value)

        backend.offline = true
        vm.cancelRun()
        assertEquals(ChatNotice.OFFLINE, vm.notice.value)
        vm.dismissNotice()
        vm.openModels()
        assertEquals(Picker.Closed, vm.picker.value)
        assertEquals(ChatNotice.OFFLINE, vm.notice.value)
    }

    @Test
    fun modelAndPresetPickers() {
        val vm = visible()
        vm.openModels()
        assertEquals(Picker.Models(backend.models), vm.picker.value)
        vm.chooseModel(ModelRef("p", "m2"))
        assertEquals(Picker.Closed, vm.picker.value)
        assertEquals(listOf(ModelRef("p", "m2")), backend.modelChanges)

        vm.openPresets()
        assertEquals(Picker.Presets(backend.presets), vm.picker.value)
        vm.choosePreset("read-only")
        assertEquals(listOf("read-only"), backend.presetChanges)
    }

    @Test
    fun itemKeysAreUniqueEvenIfADraftIdIsReused() {
        val vm = visible()
        backend.push(upsert(msg("draft:1", "a", draft = true), 1))
        backend.push(SessionEvent.Upsert(SESSION, 1, msg("m1", "a"), replaces = "draft:1"))
        backend.push(upsert(msg("draft:1", "b", draft = true), 1))
        val keys = vm.keys()
        assertEquals(keys.toSet().size, keys.size)
        assertEquals("draft:1", keys.first())
    }

    @Test
    fun pendingAttentionBelongsToThisSessionOrIsListedInItsSummary() {
        val own = attention("a1", SESSION, createdAt = 2)
        val subagent = attention("a2", "subagent-1", createdAt = 1)
        val other = attention("a3", "s9", createdAt = 0)
        val summary =
            SessionSummary(
                sessionId = SESSION,
                status = SessionStatus.WAITING,
                updatedAt = 0,
                attention =
                    listOf(
                        io.github.qianyeeew.dshremote.data.api
                            .AttentionRef("a2", io.github.qianyeeew.dshremote.data.api.AttentionKind.APPROVAL),
                    ),
            )
        val host =
            HostLiveState(
                LinkStatus.Online(address),
                mapOf(SESSION to summary),
                listOf(own, subagent, other).associateBy { it.attentionId },
            )
        assertEquals(listOf("a2", "a1"), pendingAttention(host, summary, SESSION).map { it.attentionId })

        states.value = mapOf("h1" to host)
        val vm = visible()
        assertEquals(
            listOf("a2", "a1"),
            vm.state.value.attention
                .map { it.attentionId },
        )
        // 本地已经确认结束的事项（例如回答成功、409）立即收起，不等实时流
        vm.attention.decide(own, allow = true)
        assertEquals(
            listOf("a2"),
            vm.state.value.attention
                .map { it.attentionId },
        )
    }

    @Test
    fun attentionHandledElsewhereIsNoticedOnlyIfItWasOnThisPage() {
        val own = attention("a1", SESSION, createdAt = 0)
        states.value =
            mapOf(
                "h1" to HostLiveState(LinkStatus.Online(address), live(SessionStatus.WAITING).getValue("h1").sessions, mapOf("a1" to own)),
            )
        val vm = visible()
        assertEquals(
            listOf("a1"),
            vm.state.value.attention
                .map { it.attentionId },
        )

        // 别的会话的事项被处理：不提示
        resolved.tryEmit(
            io.github.qianyeeew.dshremote.data.live.LiveEvent.AttentionResolved(
                "h1",
                "a9",
                "s9",
                io.github.qianyeeew.dshremote.data.live.ResolvedBy.OTHER,
            ),
        )
        assertNull(vm.attention.notice.value)

        // 这个页面上的事项在 Desktop 上被处理（实时连接先从状态里删掉，再发事件）
        states.value = mapOf("h1" to states.value.getValue("h1").copy(attention = emptyMap()))
        resolved.tryEmit(
            io.github.qianyeeew.dshremote.data.live.LiveEvent.AttentionResolved(
                "h1",
                "a1",
                SESSION,
                io.github.qianyeeew.dshremote.data.live.ResolvedBy.OTHER,
            ),
        )
        assertTrue(
            vm.state.value.attention
                .isEmpty(),
        )
        assertEquals(io.github.qianyeeew.dshremote.ui.attention.Resolution.ELSEWHERE, vm.attention.notice.value)
    }

    // ---- 工具 ----

    private fun attention(
        id: String,
        sessionId: String,
        createdAt: Long,
    ) = io.github.qianyeeew.dshremote.data.api.Attention(
        id,
        sessionId,
        io.github.qianyeeew.dshremote.data.api.AttentionKind.APPROVAL,
        approval =
            io.github.qianyeeew.dshremote.data.api
                .Approval("bash"),
        createdAt = createdAt,
    )

    private val resolved =
        kotlinx.coroutines.flow.MutableSharedFlow<io.github.qianyeeew.dshremote.data.live.LiveEvent.AttentionResolved>(
            extraBufferCapacity = 8,
        )

    private fun viewModel() = ChatViewModel("h1", SESSION, states, backend, { _, _ -> }, resolved)

    private fun visible(page: MessagePage = MessagePage(emptyList(), hasMore = false, cursor = null)): ChatViewModel {
        backend.pages[null] = page
        return viewModel().also {
            it.onVisible()
            backend.push(SessionEvent.Resync(SESSION))
        }
    }

    private fun live(status: SessionStatus) =
        mapOf(
            "h1" to
                HostLiveState(
                    LinkStatus.Online(address),
                    sessions = mapOf(SESSION to SessionSummary(sessionId = SESSION, status = status, updatedAt = 0)),
                ),
        )

    private fun ChatViewModel.keys() =
        state.value.items
            .filterIsInstance<ChatItem.Entry>()
            .map { it.key }

    private fun ChatViewModel.locals() =
        state.value.items
            .filterIsInstance<ChatItem.Local>()
            .map { it.local }

    private fun msg(
        id: String,
        text: String = id,
        role: MessageRole = MessageRole.ASSISTANT,
        draft: Boolean = false,
    ) = Message(id = id, role = role, parts = listOf(TextPart(text)), createdAt = 0, draft = draft)

    private fun upsert(
        message: Message,
        rev: Long,
    ) = SessionEvent.Upsert(SESSION, rev, message, replaces = null)

    private class FakeBackend : ChatBackend {
        private val events = Channel<SessionEvent>(Channel.UNLIMITED)
        var subscriptions = 0
        var active = 0
        val pages = mutableMapOf<String?, MessagePage>()
        val pageRequests = mutableListOf<String?>()
        var failNext = false
        var offline = false
        val prompts = mutableListOf<OutgoingMessage>()
        var promptError: Exception? = null
        var promptGate: CompletableDeferred<Unit>? = null
        var cancels = 0
        val models = ModelList(default = null, models = emptyList())
        val presets = PermissionPresetList(default = null, presets = emptyList())
        val modelChanges = mutableListOf<ModelRef>()
        val presetChanges = mutableListOf<String>()

        fun push(event: SessionEvent) {
            events.trySend(event)
        }

        override fun events(): Flow<SessionEvent> {
            subscriptions++
            active++
            return events.receiveAsFlow().onCompletion { active-- }
        }

        override suspend fun messages(before: String?): MessagePage {
            check()
            pageRequests += before
            if (failNext) {
                failNext = false
                throw ApiException.Network(IOException("x"))
            }
            return pages.getValue(before)
        }

        override suspend fun prompt(message: OutgoingMessage) {
            check()
            prompts += message
            promptGate?.await()
            promptError?.let { throw it }
        }

        override suspend fun cancel() {
            check()
            cancels++
        }

        override suspend fun models(): ModelList {
            check()
            return models
        }

        override suspend fun setModel(model: ModelRef) {
            check()
            modelChanges += model
        }

        override suspend fun permissionPresets(): PermissionPresetList {
            check()
            return presets
        }

        override suspend fun setPermissionPreset(preset: String) {
            check()
            presetChanges += preset
        }

        override suspend fun workspaces(): List<Workspace> {
            check()
            return emptyList()
        }

        private fun check() {
            if (offline) throw OfflineException()
        }
    }

    private companion object {
        const val SESSION = "s1"
    }
}
