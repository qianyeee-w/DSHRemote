package io.github.qianyeeew.dshremote.data.api

import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.TlsTestSupport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * PluginApi 对 protocol.md 第 2、4、5、7 节的实现。插件的响应用手写的 JSON 固定数据模拟，
 * 服务器是带自签证书的 MockWebServer，客户端来自 ClientFactory（和正式使用时一样经过指纹校验）。
 */
class PluginApiTest {
    private val cert = TlsTestSupport.serverCertificate()
    private val server = TlsTestSupport.httpsServer(cert, responses = 0)
    private val tokenText = "tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120"
    private val token = DeviceToken.parse(tokenText)!!
    private val client = ClientFactory().forPin(Fingerprint.of(cert.certificate))
    private val address = HostAddress("127.0.0.1", server.port)
    private val api = PluginApi(client, address, token)

    @After
    fun stop() = server.close()

    // ---------------------------------------------------------------- 请求头

    @Test
    fun authenticatedRequestsSendBearerTokenAndNoOrigin() =
        runBlocking<Unit> {
            respond("""{"deviceId":"d_1","name":"Pixel","platform":"android","createdAt":1794107669094}""")
            assertEquals(DeviceInfo("d_1", "Pixel", "android", 1794107669094), api.me())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/me", request.url.encodedPath)
            assertEquals("Bearer $tokenText", request.headers["Authorization"])
            assertNull("不能发送 Origin", request.headers["Origin"])
            assertEquals("application/json", request.headers["Accept"])
        }

    @Test
    fun infoNeedsNoTokenAndSendsNone() =
        runBlocking<Unit> {
            respond("""{"protocol":1,"pluginVersion":"0.1.0","dsh":{"baseline":"0.2.2-rc.1","version":null,"compatible":null},"extra":1}""")
            val info = PluginApi(client, address, token = null).info()
            // 没有 Date 头时不知道时钟差
            assertEquals(PluginInfo(1, "0.1.0", DshInfo("0.2.2-rc.1", null, null), clockOffsetMillis = null), info)
            val request = server.takeRequest()
            assertEquals("/v1/info", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
        }

    @Test
    fun infoEstimatesClockOffsetFromDateHeader() =
        runBlocking<Unit> {
            // 电脑的时钟比手机快 8 小时
            val eightHours = 8 * 3_600_000L
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader(
                        "Date",
                        io.github.qianyeeew.dshremote.data.live.FakePlugin
                            .httpDate(System.currentTimeMillis() + eightHours),
                    ).body("""{"protocol":1,"pluginVersion":"0.1.0","dsh":{"baseline":"0.2.2-rc.1"}}""")
                    .build(),
            )
            val offset = PluginApi(client, address, token = null).info().clockOffsetMillis!!
            // Date 头只精确到秒
            assertTrue("offset=$offset", offset in eightHours - 2_000..eightHours + 1_000)
        }

    @Test
    fun authenticatedCallWithoutTokenFailsBeforeSending() =
        runBlocking<Unit> {
            val error = runCatching { PluginApi(client, address, token = null).sessions() }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(0, server.requestCount)
        }

    // ---------------------------------------------------------------- 配对

    @Test
    fun directoryCreationIsNotAutomaticallyRetriedByHttpClient() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(503)
                    .addHeader("Retry-After", "0")
                    .body("""{"error":{"code":"DSH_UNAVAILABLE"}}""")
                    .build(),
            )
            respond("""{"path":"/work/new"}""")
            val error = runCatching { api.createDirectory("/work", "new") }.exceptionOrNull()
            assertTrue(error is ApiException.Http)
            assertEquals(503, (error as ApiException.Http).status)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun projectEndpointsEncodePathsAndUseAuthenticatedRequests() =
        runBlocking<Unit> {
            val path = "E:/中文 项目/#a&b"
            respond("""{"path":"$path","home":"E:/","parent":"E:/中文 项目","entries":[],"truncated":false}""")
            assertEquals(path, api.directories(path).path)
            val listing = server.takeRequest()
            assertEquals("/v1/directories", listing.url.encodedPath)
            assertEquals(path, listing.url.queryParameter("path"))
            assertEquals("Bearer $tokenText", listing.headers["Authorization"])

            respond("""{"path":"$path/new"}""")
            assertEquals("$path/new", api.createDirectory(path, "new"))
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/v1/directories", create.url.encodedPath)
            assertEquals("Bearer $tokenText", create.headers["Authorization"])
            val body = Json.parseToJsonElement(create.body!!.utf8()).jsonObject
            assertEquals(path, body.getValue("path").jsonPrimitive.content)
            assertEquals("new", body.getValue("name").jsonPrimitive.content)

            respond("""{"workspace":{"workspaceId":"w1","title":"new","path":"$path/new"},"created":false}""")
            val registration = api.registerWorkspace("$path/new")
            assertFalse(registration.created)
            assertEquals("w1", registration.workspace.workspaceId)
            val register = server.takeRequest()
            assertEquals("/v1/workspaces", register.url.encodedPath)
            assertEquals("Bearer $tokenText", register.headers["Authorization"])
        }

    @Test
    fun pairSendsRequestAndParsesToken() =
        runBlocking<Unit> {
            respond("""{"deviceId":"d_abc","token":"$tokenText","host":{"name":"MY-PC","pluginVersion":"0.1.0"}}""")
            val result = PluginApi(client, address, null).pair("k7m2-q9xp", "Pixel\n8", appVersion = "0.1.0")
            assertEquals(PairResult("d_abc", token, "MY-PC", "0.1.0"), result)
            assertFalse(result.toString().contains(tokenText))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/pair", request.url.encodedPath)
            assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
            assertNull(request.headers["Authorization"])
            val body = bodyOf(request)
            // 配对码原样发送（插件忽略大小写、空格和连字符），设备名去掉控制字符
            assertEquals("k7m2-q9xp", body.string("code"))
            assertEquals("Pixel8", body.string("deviceName"))
            assertEquals("android", body.string("platform"))
            assertEquals("0.1.0", body.string("appVersion"))
        }

    @Test
    fun pairRejectsMalformedToken() =
        runBlocking<Unit> {
            respond("""{"deviceId":"d_abc","token":"short","host":{"name":"MY-PC","pluginVersion":"0.1.0"}}""")
            assertApiError<ApiException.BadResponse> { PluginApi(client, address, null).pair("K7M2Q9XP", "Pixel") }
        }

    @Test
    fun pairingLockedCarriesRetryAfter() =
        runBlocking<Unit> {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "42")
                    .body("""{"error":{"code":"PAIRING_LOCKED","message":"输错次数过多"}}""")
                    .build(),
            )
            val error = assertApiError<ApiException.Http> { PluginApi(client, address, null).pair("K7M2Q9XP", "Pixel") }
            assertEquals(429, error.status)
            assertEquals(ErrorCode.PAIRING_LOCKED, error.code)
            assertEquals(42L, error.retryAfterSeconds)
            assertEquals("输错次数过多", error.serverMessage)
        }

    // ---------------------------------------------------------------- 会话和消息

    @Test
    fun parsesSessionsTolerantly() =
        runBlocking<Unit> {
            respond(
                """
                {"sessions":[
                  {"sessionId":"s1","title":null,"workspaceId":null,"cwd":"/p","status":"waiting",
                   "attention":[{"attentionId":"a1","kind":"approval"},{"attentionId":"a2","kind":"future-kind"}],
                   "model":{"provider":"deepseek-official","model":"deepseek-flash"},"permissionPreset":"workspace-write",
                   "updatedAt":1794107669094,"newField":{"x":1},
                   "lastTurn":{"result":"failed","endedAt":5,"error":"boom"},"lastReply":"改好了"},
                  {"sessionId":"s2","title":"T","workspaceId":"w1","cwd":null,"status":"paused",
                   "attention":[],"model":null,"permissionPreset":null,"updatedAt":1,
                   "lastTurn":{"result":"future-result","endedAt":6}}
                ]}
                """.trimIndent(),
            )
            val sessions = api.sessions()
            assertEquals(2, sessions.size)
            val first = sessions[0]
            assertNull(first.title)
            assertEquals(SessionStatus.WAITING, first.status)
            assertEquals(listOf(AttentionKind.APPROVAL, AttentionKind.UNKNOWN), first.attention.map { it.kind })
            assertEquals(ModelRef("deepseek-official", "deepseek-flash"), first.model)
            // 不认识的状态不会让整页解析失败
            assertEquals(SessionStatus.UNKNOWN, sessions[1].status)
            assertNull(sessions[1].model)
            // 会话摘要里的最近一轮：不认识的结果按 UNKNOWN，旧插件没有这两个字段
            assertEquals(LastTurn(TurnResult.FAILED, 5, "boom"), first.lastTurn)
            assertEquals("改好了", first.lastReply)
            assertEquals(TurnResult.UNKNOWN, sessions[1].lastTurn?.result)
            assertNull(sessions[1].lastReply)
        }

    @Test
    fun parsesMessagePageAndSendsCursor() =
        runBlocking<Unit> {
            respond(
                """
                {"messages":[
                  {"id":"m1","role":"user","parts":[{"type":"text","text":"你好"}],"createdAt":1},
                  {"id":"m2","role":"assistant","createdAt":2,"turn":3,"parts":[
                    {"type":"thinking","text":"想一想"},
                    {"type":"tool","callId":"c1","name":"bash","summary":"运行命令：npm test","status":"done",
                     "input":{"command":"npm test"},"output":"a\nz\n","truncated":true,"omitted":{"offset":2,"chars":40,"lines":20},"outputSaved":true},
                    {"type":"image","url":"x"},
                    {"type":"text","text":"完成"}
                  ]}
                ],"hasMore":true,"cursor":"opaque-cursor/==",
                "turns":[{"turn":2,"endedAt":9,"result":"stopped","stoppedBy":"user"},{"turn":3,"startedAt":10}]}
                """.trimIndent(),
            )
            val page = api.messages("s1", before = "prev cursor+/=", limit = 50)
            assertTrue(page.hasMore)
            assertEquals("opaque-cursor/==", page.cursor)
            val parts = page.messages[1].parts
            assertEquals(ThinkingPart("想一想"), parts[0])
            val tool = parts[1] as ToolPart
            assertEquals(ToolStatus.DONE, tool.status)
            assertEquals("npm test", tool.input["command"]!!.jsonPrimitive.content)
            assertTrue(tool.truncated)
            // 落地方案 B6：命令输出去掉了中间
            assertEquals(OutputOmission(offset = 2, chars = 40, lines = 20), tool.omitted)
            assertTrue(tool.outputSaved)
            assertEquals(UnknownPart, parts[2])
            assertEquals(TextPart("完成"), parts[3])
            assertEquals(MessageRole.USER, page.messages[0].role)
            assertEquals(
                listOf(Turn(2, endedAt = 9, result = TurnResult.STOPPED, stoppedBy = "user"), Turn(3, startedAt = 10)),
                page.turns,
            )
            assertTrue(page.turns[1].running)
            assertEquals(3, page.messages[1].turn)
            assertNull(page.messages[0].turn)

            val request = server.takeRequest()
            assertEquals("/v1/sessions/s1/messages", request.url.encodedPath)
            // cursor 是不透明的字符串，原样作为 before 发回
            assertEquals("prev cursor+/=", request.url.queryParameter("before"))
            assertEquals("50", request.url.queryParameter("limit"))
        }

    @Test
    fun lastPageHasNullCursor() =
        runBlocking<Unit> {
            respond("""{"messages":[],"hasMore":false,"cursor":null}""")
            val page = api.messages("s1")
            assertFalse(page.hasMore)
            assertNull(page.cursor)
            assertNull(server.takeRequest().url.query)
        }

    @Test
    fun createsSession() =
        runBlocking<Unit> {
            respond("""{"sessionId":"s9"}""")
            respond("""{"sessionId":"s10"}""")
            assertEquals("s9", api.createSession())
            assertEquals("{}", server.takeRequest().body!!.utf8())
            assertEquals("s10", api.createSession("w1"))
            assertEquals("w1", bodyOf(server.takeRequest()).string("workspaceId"))
        }

    // ---------------------------------------------------------------- 发消息：requestId

    @Test
    fun retryingSameMessageReusesRequestId() =
        runBlocking<Unit> {
            repeat(3) { respond("""{"accepted":true}""") }
            val message = OutgoingMessage("运行测试", PromptMode.STEER)
            api.prompt("s1", message)
            api.prompt("s1", message) // 网络重试：同一个对象
            api.prompt("s1", OutgoingMessage("运行测试", PromptMode.QUEUE)) // 新消息
            val bodies = (1..3).map { bodyOf(server.takeRequest()) }
            assertEquals(bodies[0].string("requestId"), bodies[1].string("requestId"))
            assertNotEquals(bodies[0].string("requestId"), bodies[2].string("requestId"))
            assertEquals("steer", bodies[0].string("mode"))
            assertEquals("queue", bodies[2].string("mode"))
            assertEquals("运行测试", bodies[0].string("text"))
        }

    @Test
    fun outgoingMessageValidatesAndHidesText() {
        val message = OutgoingMessage("私密内容", PromptMode.QUEUE)
        assertFalse(message.toString().contains("私密内容"))
        for (bad in listOf("", "  \n", "x".repeat(OutgoingMessage.MAX_TEXT + 1))) {
            assertTrue(runCatching { OutgoingMessage(bad, PromptMode.QUEUE) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { OutgoingMessage("x", PromptMode.QUEUE, requestId = "a/b") }.exceptionOrNull() is IllegalArgumentException)
        OutgoingMessage("x".repeat(OutgoingMessage.MAX_TEXT), PromptMode.QUEUE)
    }

    @Test
    fun cancelPostsEmptyBody() =
        runBlocking<Unit> {
            respond("""{"accepted":true}""")
            api.cancel("s1")
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/sessions/s1/cancel", request.url.encodedPath)
            assertEquals(0L, request.bodySize)
        }

    // ---------------------------------------------------------------- 模型、权限模式、待处理事项

    @Test
    fun modelsAndPresets() =
        runBlocking<Unit> {
            respond(
                """{"default":{"provider":"p","model":"m"},"models":[{"provider":"p","providerName":"P","model":"m","name":"M"}]}""",
            )
            respond("""{"model":{"provider":"p","model":"m2"}}""")
            respond("""{"default":null,"presets":[{"id":"read-only","name":"只读"}]}""")
            respond("""{"permissionPreset":"read-only"}""")

            val models = api.models()
            assertEquals(ModelRef("p", "m"), models.default)
            assertNull(models.models.single().description)
            assertEquals(ModelRef("p", "m2"), api.setModel("s1", ModelRef("p", "m2")))
            assertNull(api.permissionPresets().default)
            assertEquals("read-only", api.setPermissionPreset("s1", "read-only"))

            server.takeRequest()
            val setModel = server.takeRequest()
            assertEquals("/v1/sessions/s1/model", setModel.url.encodedPath)
            assertEquals("m2", bodyOf(setModel).string("model"))
            assertEquals("/v1/permission-presets", server.takeRequest().url.encodedPath)
            assertEquals("read-only", bodyOf(server.takeRequest()).string("preset"))
        }

    @Test
    fun answersApprovalAndQuestion() =
        runBlocking<Unit> {
            respond("""{"attentionId":"a1"}""")
            respond("""{"attentionId":"a2"}""")
            api.answer("a1", AttentionAnswer.Decision(allow = false))
            api.answer("a2", AttentionAnswer.Answers(listOf(QuestionAnswer("q1", listOf("方案A")), QuestionAnswer("q2", custom = "自由文本"))))
            val approval = server.takeRequest()
            assertEquals("/v1/attention/a1/answer", approval.url.encodedPath)
            assertEquals("""{"decision":"reject"}""", approval.body!!.utf8())
            val question = bodyOf(server.takeRequest())
            val answers = Json.parseToJsonElement(question["answers"].toString())
            assertEquals(
                Json.parseToJsonElement("""[{"id":"q1","selected":["方案A"]},{"id":"q2","selected":[],"custom":"自由文本"}]"""),
                answers,
            )
        }

    @Test
    fun parsesAttention() {
        val approval =
            ApiJson.decodeFromString(
                Attention.serializer(),
                """{"attentionId":"a1","sessionId":"s1","kind":"approval","approval":{"toolName":"bash","reason":"原因"},"createdAt":1}""",
            )
        assertEquals(AttentionKind.APPROVAL, approval.kind)
        assertNull(approval.approval!!.summary)
        val question =
            ApiJson.decodeFromString(
                Attention.serializer(),
                """
                {"attentionId":"a2","sessionId":"sub-1","kind":"question","createdAt":2,"question":{"questions":[
                  {"id":"q1","question":"用哪种方案？","header":"选择","options":[{"label":"方案A","description":"…"}],"multiSelect":false}
                ]}}
                """.trimIndent(),
            )
        assertEquals(
            "方案A",
            question.question!!
                .questions
                .single()
                .options
                .single()
                .label,
        )
    }

    // ---------------------------------------------------------------- 错误

    @Test
    fun mapsErrorCodes() =
        runBlocking<Unit> {
            error(401, """{"error":{"code":"TOKEN_INVALID","message":"token 无效"}}""")
            val unauthorized = assertApiError<ApiException.Http> { api.sessions() }
            assertEquals(ErrorCode.TOKEN_INVALID, unauthorized.code)
            assertTrue(unauthorized.isTokenInvalid)

            error(409, """{"error":{"code":"ATTENTION_RESOLVED","message":"已在别处处理"}}""")
            val resolved = assertApiError<ApiException.Http> { api.answer("a1", AttentionAnswer.Decision(true)) }
            assertEquals(ErrorCode.ATTENTION_RESOLVED, resolved.code)
            assertFalse(resolved.isTokenInvalid)

            error(503, """{"error":{"code":"SOMETHING_NEW","message":"x"}}""")
            assertEquals(ErrorCode.UNKNOWN, assertApiError<ApiException.Http> { api.sessions() }.code)

            error(502, "<html>bad gateway</html>")
            val notJson = assertApiError<ApiException.Http> { api.sessions() }
            assertEquals(502, notJson.status)
            assertEquals(ErrorCode.UNKNOWN, notJson.code)
            assertNull(notJson.serverMessage)
        }

    @Test
    fun malformedSuccessBodyIsBadResponse() =
        runBlocking<Unit> {
            respond("""{"sessions":"not a list"}""")
            assertApiError<ApiException.BadResponse> { api.sessions() }
            respond("not json")
            assertApiError<ApiException.BadResponse> { api.sessions() }
        }

    @Test
    fun wrongFingerprintIsReportedAndNothingIsSent() =
        runBlocking<Unit> {
            val otherPin = Fingerprint.of(TlsTestSupport.serverCertificate().certificate)
            val pinned = PluginApi(ClientFactory().forPin(otherPin), address, token)
            assertApiError<ApiException.FingerprintMismatch> { pinned.sessions() }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun connectionFailureIsNetworkError() =
        runBlocking<Unit> {
            val closedPort = ServerSocket(0).use { it.localPort }
            assertApiError<ApiException.Network> { PluginApi(client, HostAddress("127.0.0.1", closedPort), token).info() }
        }

    /** 落地方案 C1：另开对话的请求和结果。 */
    @Test
    fun forkSendsRequestIdAndBeforeTurn() =
        runBlocking<Unit> {
            respond("""{"sessionId":"session-child","workspaceAttached":false}""")
            assertEquals(ForkResult("session-child", workspaceAttached = false), api.fork("s1", "req-1", beforeTurn = 3))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/sessions/s1/fork", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("req-1", body["requestId"]!!.jsonPrimitive.content)
            assertEquals("3", body["beforeTurn"]!!.jsonPrimitive.content)
        }

    /** 落地方案 B5：计划；旧插件 404。 */
    @Test
    fun planParsesGoalAndSteps() =
        runBlocking<Unit> {
            respond(
                """{"goal":{"objective":"确认重连","status":"blocked",""" +
                    """"blocked":{"code":"round-limit","message":"Goal reached its configured limit of 1 rounds."}},""" +
                    """"steps":[{"text":"跑测试","status":"in_progress"}]}""",
            )
            assertEquals(
                SessionPlan(
                    PlanGoal("确认重连", GoalStatus.BLOCKED, GoalBlock("round-limit", "Goal reached its configured limit of 1 rounds.")),
                    listOf(PlanStep("跑测试", StepStatus.IN_PROGRESS)),
                ),
                api.plan("s1"),
            )
            assertEquals("/v1/sessions/s1/plan", server.takeRequest().url.encodedPath)
            error(404, """{"error":{"code":"NOT_FOUND","message":"接口不存在"}}""")
            assertApiError<ApiException.Http> { api.plan("s1") }
        }

    /** 落地方案 B4：用量与上下文；拿不到的字段是 null，不是 0；旧插件 404。 */
    @Test
    fun usageParsesWhatThePluginGivesAndLeavesTheRestNull() =
        runBlocking<Unit> {
            respond(
                """{"context":{"tokens":59210,"window":131072},"total":{"uncachedInputTokens":41000,"outputTokens":12000,"cacheReadTokens":138000,"cacheWriteTokens":3000},"extra":1}""",
            )
            assertEquals(
                SessionUsage(ContextUsage(59_210, 131_072), TotalUsage(41_000, 12_000, 138_000, 3_000)),
                api.usage("s1"),
            )
            assertEquals("/v1/sessions/s1/usage", server.takeRequest().url.encodedPath)
            respond("""{"context":{"window":131072},"total":null}""")
            assertEquals(SessionUsage(ContextUsage(tokens = null, window = 131_072), null), api.usage("s1"))
            server.takeRequest()
            error(404, """{"error":{"code":"NOT_FOUND","message":"接口不存在"}}""")
            assertApiError<ApiException.Http> { api.usage("s1") }
        }

    /** 落地方案 B3：请求写出之后连接断开，插件可能已经收到；连不上时肯定没收到。 */
    @Test
    fun promptKnowsWhetherTheMessageMayHaveArrived() =
        runBlocking<Unit> {
            // 收到请求后、回应之前断开（OkHttp 可能自己在新连接上再试一次，多准备几个）
            repeat(3) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }
            val lost = assertApiError<ApiException.Network> { api.prompt("s1", OutgoingMessage("你好", PromptMode.QUEUE)) }
            assertTrue("请求已经写出，可能已送到", lost.maybeDelivered)
            assertTrue(server.requestCount >= 1)

            val closedPort = ServerSocket(0).use { it.localPort }
            val refused =
                assertApiError<ApiException.Network> {
                    PluginApi(client, HostAddress("127.0.0.1", closedPort), token).prompt("s1", OutgoingMessage("你好", PromptMode.QUEUE))
                }
            assertFalse("连不上，肯定没送到", refused.maybeDelivered)
        }

    @Test
    fun errorsNeverContainSecrets() =
        runBlocking<Unit> {
            val errors = mutableListOf<Throwable>()
            error(401, """{"error":{"code":"TOKEN_INVALID","message":"token 无效"}}""")
            errors += runCatching { api.sessions() }.exceptionOrNull()!!
            respond("""{"deviceId":"d","token":"bad","host":{"name":"n","pluginVersion":"v"}}""")
            errors += runCatching { PluginApi(client, address, null).pair("K7M2Q9XP", "x") }.exceptionOrNull()!!
            respond("""{"broken":""")
            errors += runCatching { api.prompt("s1", OutgoingMessage("秘密消息", PromptMode.QUEUE)) }.exceptionOrNull()!!
            val closedPort = ServerSocket(0).use { it.localPort }
            errors += runCatching { PluginApi(client, HostAddress("127.0.0.1", closedPort), token).me() }.exceptionOrNull()!!
            for (error in errors) {
                val text = generateSequence(error) { it.cause }.joinToString("\n") { "$it" }
                for (secret in listOf(tokenText, "K7M2Q9XP", "秘密消息")) {
                    assertFalse("异常中出现了秘密：$text", text.contains(secret))
                }
            }
        }

    @Test
    fun rejectsIdsThatCouldChangeThePath() =
        runBlocking<Unit> {
            for (id in listOf("..", ".", "a/b", "a?b", "", "x".repeat(129), "会话")) {
                val error = runCatching { api.messages(id) }.exceptionOrNull()
                assertTrue("应拒绝 id：$id", error is IllegalArgumentException)
            }
            assertTrue(runCatching { api.answer("../me", AttentionAnswer.Decision(true)) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { api.messages("s1", limit = 0) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun deleteMeAccepts204() =
        runBlocking<Unit> {
            server.enqueue(MockResponse.Builder().code(204).build())
            api.deleteMe()
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/me", request.url.encodedPath)
        }

    // ---------------------------------------------------------------- 工具

    private fun respond(json: String) {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .body(json)
                .build(),
        )
    }

    private fun error(
        code: Int,
        body: String,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .body(body)
                .build(),
        )
    }

    private fun bodyOf(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private suspend inline fun <reified T : ApiException> assertApiError(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: ApiException) {
            if (e is T) return e
            throw AssertionError("期望 ${T::class.java.simpleName}，实际是 $e", e)
        }
        fail("期望 ${T::class.java.simpleName}，但没有抛出异常")
        throw IllegalStateException()
    }
}
