package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.Approval
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.AttentionRef
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.data.live.PairingReason
import io.github.qianyeeew.dshremote.ui.FakePairer.Companion.host
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 会话列表：排序、状态、新建会话的流程和错误。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {
    private val address = HostAddress("192.168.1.5", 3190)
    private val hosts = MutableStateFlow(listOf(host("h1", "MY-PC")))
    private val states = MutableStateFlow<Map<String, HostLiveState>>(emptyMap())
    private val creator = FakeCreator()

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun sessionsAreSortedWaitingRunningIdleThenNewestFirst() {
        val sorted =
            sortSessions(
                listOf(
                    session("idle-old", SessionStatus.IDLE, 1),
                    session("running", SessionStatus.RUNNING, 5),
                    session("unknown", SessionStatus.UNKNOWN, 100),
                    session("idle-new", SessionStatus.IDLE, 9),
                    session("waiting-old", SessionStatus.WAITING, 2),
                    session("waiting-new", SessionStatus.WAITING, 3),
                ),
            )
        assertEquals(listOf("waiting-new", "waiting-old", "running", "idle-new", "idle-old", "unknown"), sorted.map { it.sessionId })
    }

    @Test
    fun stateFollowsHostAndLiveSessions() =
        runBlocking<Unit> {
            val vm = viewModel()
            states.value =
                mapOf(
                    "h1" to
                        HostLiveState(
                            LinkStatus.Online(address),
                            sessions = mapOf("a" to session("a", SessionStatus.IDLE, 1), "b" to session("b", SessionStatus.WAITING, 0)),
                        ),
                )
            var state = withTimeout(5_000) { vm.state.first { it?.sessions?.size == 2 } }!!
            assertEquals("MY-PC", state.hostName)
            assertEquals(listOf("b", "a"), state.sessions.map { it.sessionId })

            // 断开后保留最后的会话，状态变为离线
            states.value = mapOf("h1" to states.value.getValue("h1").copy(status = LinkStatus.Offline(OfflineReason.DISCONNECTED, 1_000)))
            state = withTimeout(5_000) { vm.state.first { it?.status is LinkStatus.Offline } }!!
            assertEquals(2, state.sessions.size)

            // 凭据丢失时显示需要重新配对
            hosts.value = listOf(host("h1", "MY-PC", credentials = null))
            state = withTimeout(5_000) { vm.state.first { it?.status is LinkStatus.NeedsPairing } }!!
            assertEquals(LinkStatus.NeedsPairing(PairingReason.CREDENTIALS_LOST), state.status)

            // 主机被删除
            hosts.value = emptyList()
            assertNull(withTimeout(5_000) { vm.state.first { it == null } })
        }

    @Test
    fun attentionOutsideAnyListedSessionIsShownSeparately() {
        val approval =
            io.github.qianyeeew.dshremote.data.api
                .Approval("bash")

        fun item(
            id: String,
            sessionId: String,
        ) = io.github.qianyeeew.dshremote.data.api
            .Attention(id, sessionId, AttentionKind.APPROVAL, approval = approval, createdAt = 0)
        val state =
            HostLiveState(
                LinkStatus.Online(address),
                sessions = mapOf("s1" to session("s1", SessionStatus.WAITING, 0)),
                attention =
                    listOf(
                        // 属于 s1
                        item("a-s1", "s1"),
                        // 子代理的，s1 的摘要里列出了它（session() 给 waiting 的会话加上 a-<id>）
                        item("a-x", "sub-1").copy(attentionId = "a-s1-sub"),
                        // 不属于任何会话
                        item("a-orphan", "sub-2"),
                    ).associateBy { it.attentionId },
            )
        val listed =
            state.copy(
                sessions =
                    mapOf(
                        "s1" to state.sessions.getValue("s1").copy(attention = listOf(AttentionRef("a-s1-sub", AttentionKind.APPROVAL))),
                    ),
            )
        assertEquals(listOf("a-orphan"), otherAttention(listed).map { it.attentionId })
        assertEquals(emptyList<String>(), otherAttention(null).map { it.attentionId })
    }

    /** 卡片里直接处理：拿到会话最早的事项；在这台手机上允许之后，这个事项马上从卡片里去掉，不等插件推送。 */
    @Test
    fun cardAttentionIsAnsweredInPlaceAndDroppedOnceDone() =
        runBlocking<Unit> {
            val answers = mutableListOf<String>()
            val vm =
                SessionListViewModel(
                    "h1",
                    hosts,
                    states,
                    creator,
                    PluginProjectBackend { null },
                    responder = { id, _ -> answers += id },
                )
            val shell = Attention("a1", "w", AttentionKind.APPROVAL, approval = Approval("bash", "r"), createdAt = 0)
            states.value =
                mapOf(
                    "h1" to
                        HostLiveState(
                            LinkStatus.Online(address),
                            sessions = mapOf("w" to session("w", SessionStatus.WAITING, 1)),
                            attention = mapOf("a1" to shell),
                        ),
                )
            val state = withTimeout(5_000) { vm.state.first { it?.sections?.active?.isNotEmpty() == true } }!!
            assertEquals(ActiveItem.Waiting(session("w", SessionStatus.WAITING, 1), shell), state.sections.active.single())
            vm.attention.decide(shell, allow = true)
            val after =
                withTimeout(
                    5_000,
                ) { vm.state.first { (it?.sections?.active?.single() as? ActiveItem.Waiting)?.attention == null } }!!
            assertEquals(listOf("a1"), answers)
            assertEquals("h1", after.hostId)
        }

    private fun viewModel() = SessionListViewModel("h1", hosts, states, creator, PluginProjectBackend { null })

    private fun session(
        id: String,
        status: SessionStatus,
        updatedAt: Long,
    ) = SessionSummary(
        sessionId = id,
        status = status,
        updatedAt = updatedAt,
        attention = if (status == SessionStatus.WAITING) listOf(AttentionRef("a-$id", AttentionKind.APPROVAL)) else emptyList(),
    )

    private class FakeCreator : SessionCreator {
        var workspaces = emptyList<Workspace>()
        var workspacesError: Exception? = null
        var createError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val created = mutableListOf<String?>()

        override suspend fun workspaces(): List<Workspace> {
            workspacesError?.let { throw it }
            return workspaces
        }

        override suspend fun create(workspaceId: String?): String {
            created += workspaceId
            gate?.await()
            createError?.let { throw it }
            return "s_new"
        }

        override suspend fun models() = ModelList(default = null, models = emptyList())

        override suspend fun presets() = PermissionPresetList(default = null, presets = emptyList())

        override suspend fun setModel(
            sessionId: String,
            model: ModelRef,
        ) = Unit

        override suspend fun setPreset(
            sessionId: String,
            preset: String,
        ) = Unit

        override suspend fun prompt(
            sessionId: String,
            message: OutgoingMessage,
        ) = Unit
    }
}
