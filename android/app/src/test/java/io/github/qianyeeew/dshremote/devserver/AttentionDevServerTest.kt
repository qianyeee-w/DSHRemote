package io.github.qianyeeew.dshremote.devserver

import io.github.qianyeeew.dshremote.data.api.AttentionAnswer
import io.github.qianyeeew.dshremote.data.api.AttentionKind
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.ToolPart
import io.github.qianyeeew.dshremote.data.api.ToolStatus
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.live.Backoff
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.ui.attention.AttentionHandler
import io.github.qianyeeew.dshremote.ui.attention.LiveAttentionResponder
import io.github.qianyeeew.dshremote.ui.attention.Resolution
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import io.github.qianyeeew.dshremote.ui.chat.ChatViewModel
import io.github.qianyeeew.dshremote.ui.chat.LiveChatBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * 审批和提问对真实 dev-server 的集成测试（m2-android.md T10）：用真实的 ChatViewModel 和实时连接，
 * SHELL 审批、ASK 提问，以及另一台设备（相当于 Desktop）先处理后手机自动收起。只由 `./gradlew devServerTest` 运行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionDevServerTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var sessionId: String
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() =
        runBlocking<Unit> {
            main = newSingleThreadContext("main")
            Dispatchers.setMain(main)
            withTimeout(20_000) { manager.states.first { it[HOST_ID]?.status is LinkStatus.Online } }
            sessionId = api.createSession()
            vm =
                withContext(main) {
                    ChatViewModel(
                        HOST_ID,
                        sessionId,
                        manager.states,
                        LiveChatBackend(manager, HOST_ID, sessionId),
                        LiveAttentionResponder(manager, HOST_ID),
                        manager.resolvedEvents(HOST_ID),
                    )
                }
            withContext(main) { vm.onVisible() }
            await("读到历史") { it.loaded }
        }

    @After
    fun tearDown() =
        runBlocking<Unit> {
            // 万一测试中途失败，停掉还在等待的任务，免得留下待处理事项
            runCatching { api.cancel(sessionId) }
            withContext(main) { vm.onHidden() }
            Dispatchers.resetMain()
            main.close()
        }

    @Test
    fun approvingShellRunsTheToolAndCollapsesTheCard() =
        runBlocking<Unit> {
            assertTrue(withContext(main) { vm.send("SHELL 运行一下", steer = true) })
            val waiting = await("出现审批", timeoutMillis = 30_000) { state -> state.attention.any { it.kind == AttentionKind.APPROVAL } }
            val approval = waiting.attention.single()
            assertTrue("插件补上了要执行的命令：${approval.approval}", approval.approval?.summary?.contains("echo") == true)

            withContext(main) { vm.attention.decide(approval, allow = true) }
            await("卡片收起") { it.attention.isEmpty() }
            assertEquals(
                Resolution.ANSWERED_HERE,
                vm.attention.ui.value[approval.attentionId]
                    ?.resolution,
            )
            val done =
                await("工具执行完", timeoutMillis = 30_000) { state ->
                    state.session?.status == SessionStatus.IDLE && state.tools().any { it.status == ToolStatus.DONE }
                }
            assertTrue(done.tools().isNotEmpty())
            assertEquals(null, vm.attention.notice.value)
        }

    @Test
    fun answeringAQuestion() =
        runBlocking<Unit> {
            assertTrue(withContext(main) { vm.send("ASK 问我", steer = true) })
            val question =
                await("出现提问", timeoutMillis = 30_000) { state ->
                    state.attention.any {
                        it.kind == AttentionKind.QUESTION
                    }
                }.attention.single()
            val q = question.question!!.questions.single()
            assertEquals(listOf("方案A", "方案B"), q.options.map { it.label })

            withContext(main) {
                // 什么都没选时本地就拦下
                vm.attention.submitAnswers(question)
                vm.attention.toggle(question, q.id, "方案A")
                vm.attention.submitAnswers(question)
            }
            await("卡片收起") { it.attention.isEmpty() }
            assertEquals(
                Resolution.ANSWERED_HERE,
                vm.attention.ui.value[question.attentionId]
                    ?.resolution,
            )
            await("回答后继续运行并结束", timeoutMillis = 30_000) { it.session?.status == SessionStatus.IDLE }
        }

    @Test
    fun handledOnAnotherDeviceCollapsesWithNoticeAndAnsweringAgainIsAConflict() =
        runBlocking<Unit> {
            assertTrue(withContext(main) { vm.send("SHELL 运行一下", steer = true) })
            val approval =
                await("出现审批", timeoutMillis = 30_000) { state -> state.attention.isNotEmpty() }
                    .attention
                    .single()

            // 另一台设备（相当于在 Desktop 上点了拒绝）先处理
            desktop.answer(approval.attentionId, AttentionAnswer.Decision(allow = false))
            await("手机上收起") { it.attention.isEmpty() }
            val notice = withTimeoutOrNull(10_000) { vm.attention.notice.first { it != null } }
            assertEquals("提示「已在其他地方处理」", Resolution.ELSEWHERE, notice)

            // 晚到的回答得到 409，同样当作「已在其他地方处理」
            val late = AttentionHandler(CoroutineScope(main), LiveAttentionResponder(manager, HOST_ID), emptyFlow())
            withContext(main) { late.decide(approval, allow = true) }
            val resolution =
                withTimeout(
                    10_000,
                ) { late.ui.first { it[approval.attentionId]?.resolution != null } }[approval.attentionId]?.resolution
            assertEquals(Resolution.ELSEWHERE, resolution)
        }

    private suspend fun await(
        what: String,
        timeoutMillis: Long = 20_000,
        check: (ChatState) -> Boolean,
    ): ChatState =
        withTimeoutOrNull(timeoutMillis) { vm.state.first(check) }
            ?: throw AssertionError("等待超时：$what（当前：${vm.state.value.session?.status}，待处理 ${vm.state.value.attention.size} 项）")

    private fun ChatState.tools() =
        items
            .filterIsInstance<ChatItem.Entry>()
            .filter { it.message.role == MessageRole.ASSISTANT }
            .flatMap { it.message.parts.filterIsInstance<ToolPart>() }

    companion object {
        private const val HOST_ID = "dev"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private lateinit var manager: ConnectionManager
        private lateinit var api: PluginApi
        private lateinit var desktop: PluginApi

        @BeforeClass
        @JvmStatic
        fun connect() =
            runBlocking<Unit> {
                val status = DevServer.status()
                val factory = ClientFactory()
                val paired = DevServer.pair(status, "AttentionDevServerTest")
                api = PluginApi(factory.forPin(status.pin), status.address, paired.token)
                desktop = PluginApi(factory.forPin(status.pin), status.address, DevServer.pair(status, "AttentionDevServerTest-另一台").token)
                val host = Host(HOST_ID, "dev-server", listOf(status.address), null, paired.deviceId, Credentials(status.pin, paired.token))
                manager =
                    ConnectionManager(
                        hosts = MutableStateFlow(listOf(host)),
                        scope = scope,
                        clientFor = factory::forPin,
                        markGoodAddress = { _, _ -> },
                        log = { println("[attention] $it") },
                        newBackoff = { Backoff(initialMillis = 200, maxMillis = 2_000) },
                    )
                manager.hold()
            }

        @AfterClass
        @JvmStatic
        fun disconnect() =
            runBlocking<Unit> {
                scope.cancel()
                if (::api.isInitialized) api.deleteMe()
                if (::desktop.isInitialized) desktop.deleteMe()
            }
    }
}
