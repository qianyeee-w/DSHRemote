package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.TlsTestSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 测试用的假插件：带自签证书的 MockWebServer，提供 `/v1/info` 和 `/v1/stream`（WebSocket）。
 * 测试通过 [nextConnection] 拿到服务器端的连接，自己发送 hello 和各种事件、按指定的关闭码关闭。
 */
internal class FakePlugin(
    val cert: HeldCertificate = TlsTestSupport.serverCertificate(),
) : Closeable {
    /** `/v1/info` 的响应体。 */
    @Volatile
    var info: String = info()

    /** 每次 WebSocket 握手调用一次：返回 null 表示升级为 WebSocket，否则返回这个错误响应。 */
    @Volatile
    var handshake: () -> MockResponse? = { null }

    /** 其他接口（例如 `/v1/pair`、`/v1/me`）的响应：返回 null 时按 404 处理。 */
    @Volatile
    var routes: (RecordedRequest) -> MockResponse? = { null }

    /** 收到的所有 HTTP 请求（包括 WebSocket 握手）。 */
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val connections = Channel<ServerSide>(Channel.UNLIMITED)

    private val server =
        MockWebServer().apply {
            useHttps(TlsTestSupport.serverHandshake(cert).sslSocketFactory())
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests += request
                        return when (request.url.encodedPath) {
                            "/v1/info" -> json(info)
                            "/v1/stream" ->
                                handshake() ?: ServerSide(request).let { side ->
                                    connections.trySend(side)
                                    MockResponse.Builder().webSocketUpgrade(side).build()
                                }
                            else -> routes(request) ?: MockResponse.Builder().code(404).build()
                        }
                    }
                }
            start(TlsTestSupport.LOOPBACK, 0)
        }

    val address: HostAddress get() = HostAddress("127.0.0.1", server.port)

    val streamRequests: List<RecordedRequest> get() = requests.filter { it.url.encodedPath == "/v1/stream" }

    suspend fun nextConnection(timeoutMillis: Long = 5_000): ServerSide = withTimeout(timeoutMillis) { connections.receive() }

    /** 没有新的连接（在 [millis] 内）。 */
    suspend fun assertNoNewConnection(millis: Long = 300) {
        val side = kotlinx.coroutines.withTimeoutOrNull(millis) { connections.receive() }
        check(side == null) { "不应该再建立连接" }
    }

    override fun close() = server.close()

    /** 服务器端的一条 WebSocket 连接。 */
    class ServerSide(
        /** 握手请求（检查请求头）。 */
        val request: RecordedRequest,
    ) : WebSocketListener() {
        private val socket = CompletableDeferred<WebSocket>()

        /** App 发来的文本消息。 */
        val received = Channel<String>(Channel.UNLIMITED)

        /** App 关闭连接时的关闭码。 */
        val closedByApp = CompletableDeferred<Int>()

        /** 连接已经断开（无论哪一方）。 */
        val gone = CompletableDeferred<Unit>()

        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            socket.complete(webSocket)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            received.trySend(text)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            closedByApp.complete(code)
            webSocket.close(code, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            gone.complete(Unit)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            gone.complete(Unit)
        }

        suspend fun send(text: String) {
            check(withTimeout(5_000) { socket.await() }.send(text))
        }

        suspend fun hello(
            sessions: List<String> = emptyList(),
            attention: List<String> = emptyList(),
            protocol: Int = 1,
        ) = send(
            event(
                "hello",
                """"protocol":$protocol,"sessions":${sessions.joinToString(
                    ",",
                    "[",
                    "]",
                )},"attention":${attention.joinToString(",", "[", "]")}""",
            ),
        )

        suspend fun close(code: Int) {
            withTimeout(5_000) { socket.await() }.close(code, null)
        }

        /**
         * 不发关闭帧，直接断开 TCP 连接（模拟插件崩溃或网络中断）。服务器端的 WebSocket 不支持 cancel()，
         * 只能用 OkHttp 的内部方法 failWebSocket 关掉底层连接（只在测试中这样用）。
         */
        suspend fun kill() {
            val webSocket = withTimeout(5_000) { socket.await() } as okhttp3.internal.ws.RealWebSocket
            webSocket.failWebSocket(java.io.IOException("测试：断开连接"), null, false)
        }

        suspend fun nextReceived(): String = withTimeout(5_000) { received.receive() }
    }

    companion object {
        fun info(
            protocol: Int = 1,
            compatible: Boolean? = true,
            version: String? = "0.2.2-rc.1",
        ): String =
            """{"protocol":$protocol,"pluginVersion":"0.1.0","dsh":{"baseline":"0.2.2-rc.1","version":${version?.let {
                "\"$it\""
            }},"compatible":$compatible}}"""

        fun event(
            type: String,
            body: String,
            eventId: String = UUID.randomUUID().toString(),
        ): String = """{"type":"$type","eventId":"$eventId"${if (body.isEmpty()) "" else ",$body"}}"""

        fun session(
            id: String,
            status: String = "idle",
            title: String? = "标题 $id",
        ): String =
            """{"sessionId":"$id","title":${title?.let {
                "\"$it\""
            }},"workspaceId":null,"cwd":"/w","status":"$status","attention":[],"model":null,"permissionPreset":"default","updatedAt":1794107669094}"""

        fun approval(
            id: String,
            sessionId: String = "s1",
        ): String =
            """{"attentionId":"$id","sessionId":"$sessionId","kind":"approval","approval":{"toolName":"bash","reason":"r"},"createdAt":1794107669094}"""

        fun message(
            id: String,
            text: String,
        ): String = """{"id":"$id","role":"assistant","parts":[{"type":"text","text":"$text"}],"createdAt":1794107669094}"""

        fun json(body: String) =
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build()

        fun error(
            status: Int,
            code: String,
        ): MockResponse =
            MockResponse
                .Builder()
                .code(status)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"code":"$code","message":"m"}}""")
                .build()
    }
}
