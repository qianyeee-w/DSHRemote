package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.AttentionRef
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
    fun newSessionLoadsWorkspacesThenCreatesInTheChosenOne() {
        val vm = viewModel()
        creator.workspaces = listOf(Workspace("w1", "app", "/home/me/app"))
        vm.startNewSession()
        assertEquals(NewSession.Choose(creator.workspaces), vm.newSession.value)

        creator.gate = CompletableDeferred()
        vm.create("w1")
        assertEquals(NewSession.Creating, vm.newSession.value)
        // 创建中：再点、关闭对话框都不会重复创建
        vm.create("w1")
        vm.startNewSession()
        vm.dismissNewSession()
        assertEquals(NewSession.Creating, vm.newSession.value)
        creator.gate!!.complete(Unit)

        assertEquals(NewSession.Created("s_new"), vm.newSession.value)
        assertEquals(listOf<String?>("w1"), creator.created)
        vm.dismissNewSession()
        assertEquals(NewSession.Closed, vm.newSession.value)
    }

    @Test
    fun newSessionWithoutWorkspace() {
        val vm = viewModel()
        vm.startNewSession()
        vm.create(null)
        assertEquals(listOf<String?>(null), creator.created)
    }

    @Test
    fun errorsAreMappedAndCanBeRetried() {
        val cases =
            listOf(
                OfflineException() to CreateError.OFFLINE,
                ApiException.Network(IOException("x")) to CreateError.OFFLINE,
                ApiException.Http(401, ErrorCode.TOKEN_INVALID, null) to CreateError.NEEDS_PAIRING,
                ApiException.Http(503, ErrorCode.DSH_UNAVAILABLE, null) to CreateError.DSH_UNAVAILABLE,
                ApiException.Http(502, ErrorCode.DSH_ERROR, null) to CreateError.FAILED,
                ApiException.BadResponse("x") to CreateError.FAILED,
            )
        for ((error, expected) in cases) {
            val vm = viewModel()
            creator.workspacesError = error
            vm.startNewSession()
            assertEquals(NewSession.Failed(expected), vm.newSession.value)

            // 读取工作区成功、创建失败
            creator.workspacesError = null
            vm.startNewSession()
            assertTrue(vm.newSession.value is NewSession.Choose)
            creator.createError = error
            vm.create(null)
            assertEquals(NewSession.Failed(expected), vm.newSession.value)
            creator.createError = null
        }
    }

    @Test
    fun pathsAreShortenedToLastTwoLevels() {
        assertEquals("work/app", shortPath("/home/me/work/app/"))
        assertEquals("Projects/app", shortPath("C:\\Users\\me\\Projects\\app"))
        assertEquals("/srv", shortPath("/srv"))
        assertEquals("/home/me", shortPath("/home/me"))
    }

    private fun viewModel() = SessionListViewModel("h1", hosts, states, creator)

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
    }
}
