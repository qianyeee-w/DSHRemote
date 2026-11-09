package io.github.qianyeeew.dshremote.devserver

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.AttentionAnswer
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.MessageRole
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.SessionStatus
import io.github.qianyeeew.dshremote.data.api.TextPart
import io.github.qianyeeew.dshremote.data.api.isTokenInvalid
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 对插件的 dev-server 跑的集成测试（m2-android.md T5）：真实的 `dsh web` + 插件 + 假模型。实时连接的集成测试见 [LiveDevServerTest]。
 *
 * 只由 `./gradlew devServerTest` 运行（普通的 `test` 不跑，CI 也不跑）。运行前先在 plugin/ 下启动 dev-server：
 * `npm run dev-server`。测试通过 dev-server 的命令行生成配对码，配对一台测试设备，结束时用 `DELETE /v1/me` 取消配对。
 */
class PluginDevServerTest {
    @Test
    fun probeSeesSameFingerprintAsPlugin() =
        runBlocking<Unit> {
            // 端到端的跨实现检查：App 握手时算出的指纹 = 插件自己算出的指纹
            val result = PairingProbe().probe(address.host, address.port)
            assertEquals(PairingProbe.Result.Fingerprinted(pin), result)
        }

    @Test
    fun infoReportsCompatibleDsh() =
        runBlocking<Unit> {
            val info = PluginApi(client, address, token = null).info()
            assertEquals(1, info.protocol)
            assertEquals(true, info.dsh.compatible)
            assertNotNull(info.dsh.version)
        }

    @Test
    fun usedPairingCodeCannotBeReused() =
        runBlocking<Unit> {
            val error = expect<ApiException.Http> { PluginApi(client, address, null).pair(usedCode, "DevServerTest") }
            assertEquals(ErrorCode.PAIRING_CODE_INVALID, error.code)
        }

    @Test
    fun readOnlyEndpoints() =
        runBlocking<Unit> {
            assertEquals(deviceId, api.me().deviceId)
            assertEquals("android", api.me().platform)
            api.workspaces()
            api.sessions()
            val models = api.models()
            assertTrue("dev-server 至少有一个模型", models.models.isNotEmpty())
            val presets = api.permissionPresets()
            assertTrue(presets.presets.isNotEmpty())
        }

    @Test
    fun sessionPromptIsDeliveredOnceWhenRetriedWithSameRequestId() =
        runBlocking<Unit> {
            val sessionId = api.createSession()
            assertTrue(api.sessions().any { it.sessionId == sessionId })

            val text = "devServerTest ${System.nanoTime()}"
            val message = OutgoingMessage(text, PromptMode.QUEUE)
            api.prompt(sessionId, message)
            // 模拟网络重试：同一个 requestId 再发一次，插件只发出一次
            api.prompt(sessionId, message)

            // 如果第二次也被发出了，它会排在队列里，等这一轮结束后才出现在历史中。所以要等会话真正空闲后再数。
            // 先等到模型的回复（这时会话一定已经开始运行），再等空闲，避免在开始运行之前就误判为空闲
            waitFor("模型回复") { api.messages(sessionId).messages.firstOrNull { it.role == MessageRole.ASSISTANT } }
            waitFor("会话变为空闲", attempts = 300) {
                api.sessions().firstOrNull { it.sessionId == sessionId && it.status == SessionStatus.IDLE }
            }
            val page = api.messages(sessionId)
            assertTrue("应有模型的回复", page.messages.any { it.role == MessageRole.ASSISTANT })
            val sent = page.messages.filter { m -> m.role == MessageRole.USER && m.parts.any { it == TextPart(text) } }
            assertEquals("同一个 requestId 应只发出一次", 1, sent.size)

            // 停止空闲的会话也返回成功
            api.cancel(sessionId)

            // 切换模型和权限模式（切换成列表中的第一项）
            val model = api.models().models.first()
            assertEquals(model.model, api.setModel(sessionId, ModelRef(model.provider, model.model)).model)
            val preset =
                api
                    .permissionPresets()
                    .presets
                    .first()
                    .id
            assertEquals(preset, api.setPermissionPreset(sessionId, preset))
        }

    @Test
    fun unknownAttentionIsNotFound() =
        runBlocking<Unit> {
            val error = expect<ApiException.Http> { api.answer("no-such-attention", AttentionAnswer.Decision(allow = false)) }
            assertEquals(404, error.status)
        }

    @Test
    fun wrongPinIsRejected() =
        runBlocking<Unit> {
            val wrongPin = Fingerprint.parse("E9JMy_oGlrnfgc22ShLnbW3szeR8nFwHFvPZ7TCjzPw")!!
            expect<ApiException.FingerprintMismatch> { PluginApi(ClientFactory().forPin(wrongPin), address, token).sessions() }
        }

    companion object {
        private lateinit var address: HostAddress
        private lateinit var pin: Fingerprint
        private val client by lazy { ClientFactory().forPin(pin) }
        private lateinit var api: PluginApi
        private lateinit var deviceId: String
        private lateinit var usedCode: String
        private var token: DeviceToken? = null

        @BeforeClass
        @JvmStatic
        fun pair() =
            runBlocking {
                val status = DevServer.status()
                address = status.address
                pin = status.pin

                // 先输错一次（不会让配对码作废），再用正确的配对码配对
                usedCode = DevServer.newPairingCode()
                val unpaired = PluginApi(client, address, token = null)
                val wrong = expect<ApiException.Http> { unpaired.pair("AAAAAAAA", "DevServerTest") }
                assertEquals(ErrorCode.PAIRING_CODE_INVALID, wrong.code)

                val result = unpaired.pair(usedCode, "DevServerTest")
                token = result.token
                deviceId = result.deviceId
                api = PluginApi(client, address, result.token)
            }

        @AfterClass
        @JvmStatic
        fun unpair() =
            runBlocking {
                if (token == null) return@runBlocking
                api.deleteMe()
                // 取消配对后 token 立即失效
                val error = expect<ApiException.Http> { api.sessions() }
                assertTrue(error.isTokenInvalid)
            }
    }
}
