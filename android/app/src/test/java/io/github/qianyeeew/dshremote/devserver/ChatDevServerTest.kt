package io.github.qianyeeew.dshremote.devserver

import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.StepStatus
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.TurnResult
import io.github.qianyeeew.dshremote.data.chat.OutgoingStatus
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.live.Backoff
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.ui.attention.LiveAttentionResponder
import io.github.qianyeeew.dshremote.ui.chat.ChatBlock
import io.github.qianyeeew.dshremote.ui.chat.ChatItem
import io.github.qianyeeew.dshremote.ui.chat.ChatState
import io.github.qianyeeew.dshremote.ui.chat.ChatViewModel
import io.github.qianyeeew.dshremote.ui.chat.LiveChatBackend
import io.github.qianyeeew.dshremote.ui.chat.Picker
import io.github.qianyeeew.dshremote.ui.chat.chatBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话详情对真实 dev-server 的集成测试（m2-android.md T9）：用真实的 ChatViewModel 和实时连接，
 * 走一遍流式输出、Markdown、插话、停止、切换模型和权限模式。只由 `./gradlew devServerTest` 运行。
 * 需要 dev-server 的假模型支持 MARKDOWN、SLOW、FAIL 关键词（改了 fake-llm.mjs 后要重启 dev-server）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDevServerTest {
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
            withContext(main) { vm.onHidden() }
            Dispatchers.resetMain()
            main.close()
        }

    @Test
    fun streamedMarkdownEndsUpEqualToTheHistory() =
        runBlocking<Unit> {
            val keysSeen = CopyOnWriteArrayList<String>()
            assertTrue(withContext(main) { vm.send("MARKDOWN 请输出") })

            // 本地气泡在日志里出现用户消息后消失；草稿出现，最后被正式消息替换，并且沿用草稿的列表项
            var sawDraft = false
            val final =
                withTimeout(60_000) {
                    vm.state.first { state ->
                        state.entries().forEach { if (it.message.draft) sawDraft = true }
                        keysSeen += state.entries().map { it.key }
                        state.entries().any { !it.message.draft && it.message.role == MessageRole.ASSISTANT && it.text().isNotEmpty() } &&
                            state.items.none { it is ChatItem.Local }
                    }
                }
            assertTrue("应该看到流式草稿", sawDraft)
            val answer = final.entries().last { it.message.role == MessageRole.ASSISTANT }
            assertTrue("正式消息沿用草稿的 key：${answer.key}", answer.key.startsWith("draft:"))
            assertTrue(answer.text().contains("| 编号 | 名称 |"))
            assertTrue(answer.text().contains("```kotlin"))

            // 和重新读取的历史一致（顺序、id、内容）
            val history = api.messages(sessionId).messages
            assertEquals(history.map { it.id }, final.entries().map { it.message.id })
            assertEquals(history.map { it.parts }, final.entries().map { it.message.parts })

            // 轮次（落地方案 B1）：推送来的这一轮正常结束，消息都归到这一轮；会话摘要带上结果和最后一句回复
            val ended = await("本轮结束") { state -> state.turns.values.any { it.result == TurnResult.COMPLETED && it.startedAt != null } }
            val number =
                ended.turns.values
                    .single { it.result == TurnResult.COMPLETED }
                    .turn
            assertTrue(ended.entries().all { it.message.turn == number })
            assertTrue(chatBlocks(ended.items, ended.turns).last() is ChatBlock.TurnEnd)
            val summary = await("摘要里的最近一轮") { it.session?.lastTurn?.result == TurnResult.COMPLETED }.session!!
            assertTrue("最后一句回复：${summary.lastReply}", summary.lastReply.orEmpty().isNotBlank())
        }

    /** 落地方案 B5：模型写了任务清单，会话页通过推送（plan.updated）拿到，和 GET /plan 一致。 */
    @Test
    fun planArrivesByPushWhenTheModelWritesItsTodoList() =
        runBlocking<Unit> {
            assertTrue(withContext(main) { vm.send("TODO 请列个清单") })
            val state = await("计划", timeoutMillis = 60_000) { s -> s.plan?.steps?.size == 3 }
            val plan = state.plan!!
            assertEquals(listOf(StepStatus.COMPLETED, StepStatus.IN_PROGRESS, StepStatus.PENDING), plan.steps!!.map { it.status })
            assertEquals(1, plan.currentIndex)
            assertEquals(plan, api.plan(sessionId))
        }

    /**
     * 落地方案 B4：真实插件给出的本轮用量（推送的 turn.updated 和历史里都有），一轮结束后会话页自动读到用量与上下文。
     * 假模型每次请求报告输入 10，不报告缓存。
     */
    @Test
    fun turnUsageAndSessionUsageReachTheApp() =
        runBlocking<Unit> {
            val before = await("打开会话时读到用量") { it.usage != null }.usage!!
            assertNull("新会话还没有上下文占用", before.context?.tokens)
            assertTrue(withContext(main) { vm.send("你好") })
            val state =
                await("本轮用量和会话用量", timeoutMillis = 60_000) { s ->
                    s.turns.values.any { it.result == TurnResult.COMPLETED && it.usage != null } &&
                        (s.usage?.total?.uncachedInputTokens ?: 0) > 0
                }
            val usage =
                state.turns.values
                    .single { it.usage != null }
                    .usage!!
            assertEquals(10L, usage.uncachedInputTokens)
            assertEquals(usage.uncachedInputTokens + usage.outputTokens, usage.totalTokens)
            assertNull("假模型没有报告缓存，不能显示缓存命中", usage.cacheHit)
            val session = state.usage!!
            assertTrue("上下文：${session.context}", (session.context?.tokens ?: 0) > 0 && (session.context?.window ?: 0) > 0)
            assertEquals(usage.uncachedInputTokens, session.total?.uncachedInputTokens)
            // 历史里同一轮也有同样的用量
            assertEquals(
                usage,
                api
                    .messages(sessionId)
                    .turns
                    .single { it.usage != null }
                    .usage,
            )
        }

    @Test
    fun failedTurnEndsWithTheError() =
        runBlocking<Unit> {
            // 假模型对 FAIL 关键词返回 400（不重试），这一轮失败
            assertTrue(withContext(main) { vm.send("FAIL 请失败") })
            val state = await("本轮失败", timeoutMillis = 60_000) { s -> s.turns.values.any { it.result == TurnResult.FAILED } }
            val failed = state.turns.values.single { it.result == TurnResult.FAILED }
            assertTrue("错误说明：${failed.error}", failed.error.orEmpty().contains(FAIL_MESSAGE))
            val end = chatBlocks(state.items, state.turns).filterIsInstance<ChatBlock.TurnEnd>().single()
            assertEquals(failed.turn, end.turn.turn)
            val summary = await("摘要里的失败") { it.session?.lastTurn?.result == TurnResult.FAILED }.session!!
            assertTrue(
                summary.lastTurn
                    ?.error
                    .orEmpty()
                    .contains(FAIL_MESSAGE),
            )
        }

    @Test
    fun steerWhileRunningThenStop() =
        runBlocking<Unit> {
            assertTrue(withContext(main) { vm.send("SLOW 慢慢说") })
            await("开始运行", timeoutMillis = 30_000) { it.running }
            assertTrue(withContext(main) { vm.send("插话：说快一点") })
            await("插话已发送") { state -> state.items.filterIsInstance<ChatItem.Local>().none { it.local.status == OutgoingStatus.SENDING } }
            assertTrue(
                "插话没有失败",
                vm.state.value.items
                    .filterIsInstance<ChatItem.Local>()
                    .none { it.local.status == OutgoingStatus.FAILED },
            )

            withContext(main) { vm.cancelRun() }
            await("停止后空闲", timeoutMillis = 30_000) { it.session?.status == SessionStatus.IDLE }
            val stopped = await("本轮已停止") { s -> s.turns.values.any { it.result == TurnResult.STOPPED } }
            assertEquals(
                "user",
                stopped.turns.values
                    .single { it.result == TurnResult.STOPPED }
                    .stoppedBy,
            )
        }

    @Test
    fun switchPermissionPresetAndModel() =
        runBlocking<Unit> {
            withContext(main) { vm.openPresets() }
            val presets = withTimeout(10_000) { vm.picker.first { it is Picker.Presets } } as Picker.Presets
            val original =
                vm.state.value.session
                    ?.permissionPreset
            val target =
                presets.list.presets
                    .first { it.id != original }
                    .id
            withContext(main) { vm.choosePreset(target) }
            await("权限模式变为 $target") { it.session?.permissionPreset == target }

            withContext(main) { vm.openModels() }
            val models = withTimeout(10_000) { vm.picker.first { it is Picker.Models } } as Picker.Models
            assertTrue("至少有一个模型", models.list.models.isNotEmpty())
            val option = models.list.models.first()
            withContext(main) {
                vm.chooseModel(
                    io.github.qianyeeew.dshremote.data.api
                        .ModelRef(option.provider, option.model),
                )
            }
            await("模型变为 ${option.model}") { it.session?.model?.model == option.model }
        }

    private suspend fun await(
        what: String,
        timeoutMillis: Long = 20_000,
        check: (ChatState) -> Boolean,
    ): ChatState =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { vm.state.first(check) }
            ?: throw AssertionError(
                "等待超时：$what（当前：${vm.state.value.session?.status}，${vm.state.value.items.size} 项，轮次 ${vm.state.value.turns.values}）",
            )

    private fun ChatState.entries() = items.filterIsInstance<ChatItem.Entry>()

    private fun ChatItem.Entry.text() = message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }

    companion object {
        private const val HOST_ID = "dev"

        /** 和 plugin/test/contract/fake-llm.mjs 的 FAIL_MESSAGE 一致。 */
        private const val FAIL_MESSAGE = "fake failure for DSHRemote tests"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private lateinit var manager: ConnectionManager
        private lateinit var api: PluginApi

        @BeforeClass
        @JvmStatic
        fun connect() =
            runBlocking<Unit> {
                val status = DevServer.status()
                val paired = DevServer.pair(status, "ChatDevServerTest")
                val factory = ClientFactory()
                api = PluginApi(factory.forPin(status.pin), status.address, paired.token)
                val host = Host(HOST_ID, "dev-server", listOf(status.address), null, paired.deviceId, Credentials(status.pin, paired.token))
                manager =
                    ConnectionManager(
                        hosts = MutableStateFlow(listOf(host)),
                        scope = scope,
                        clientFor = factory::forPin,
                        markGoodAddress = { _, _ -> },
                        log = { println("[chat] $it") },
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
            }
    }
}
