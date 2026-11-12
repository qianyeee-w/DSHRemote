package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.PluginInfo
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.isFingerprintMismatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** 实时连接用到的时间参数。测试里会调小。 */
data class LiveTiming(
    /** 从发起 WebSocket 握手到收到 `hello` 的最长时间。 */
    val helloTimeoutMillis: Long = 20_000,
    /**
     * App 主动发 ping 的间隔。插件每 20 秒也会 ping 一次，但只有 App 自己发 ping，
     * 才能在网络已经断了、却没有收到任何错误时（例如切换 Wi-Fi）发现连接已死。
     */
    val pingIntervalMillis: Long = 30_000,
)

/**
 * 一台主机的 `/v1/stream` 连接（m2-android.md 3.3）。由 [ConnectionManager] 创建，界面和前台服务共用，
 * **每台主机只有一条连接**。
 *
 * 连接过程：
 * 1. 用无需认证的 `/v1/info` 选地址：先试 [Host.lastGoodAddress]，失败后同时试其他地址，
 *    第一个通过指纹校验并返回正确内容的地址获胜。token 只发给通过了指纹校验的地址。
 * 2. 检查协议版本和 DSH 兼容性，再用 token 打开 WebSocket，收到 `hello` 后变为 [LinkStatus.Online]。
 * 3. 断开后按 [Backoff] 重连；[retryNow]（网络变化）跳过等待。
 *
 * 不再重连的情况（[LinkStatus.isTerminal]）：握手 `401`、关闭码 `4401`、指纹不匹配、协议版本不认识、凭据无法解密。
 * 日志中只有主机 id、关闭码和错误码，没有 token 和消息内容（红线第 5 条）。
 */
class HostConnection internal constructor(
    host: Host,
    private val scope: CoroutineScope,
    private val clientFor: (Fingerprint) -> OkHttpClient,
    private val markGoodAddress: suspend (HostAddress) -> Unit,
    /** 通知类事件发到这里，[ConnectionManager.events] 汇总所有主机的事件。 */
    private val liveEvents: MutableSharedFlow<LiveEvent>,
    private val log: (String) -> Unit,
    private val timing: LiveTiming = LiveTiming(),
    private val backoff: Backoff = Backoff(),
    /** 初始状态：重建连接（例如改了地址）时沿用上一条连接最后的会话和待处理事项，收到 `hello` 后整体覆盖。 */
    initial: HostLiveState = HostLiveState(),
) {
    val hostId: String = host.hostId

    @Volatile
    private var host: Host = host

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<HostLiveState> = _state.asStateFlow()

    private val sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = EVENT_BUFFER)

    private val lock = Any()
    private var job: Job? = null
    private var started = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** 每个会话的订阅计数，以及收到 `hello` 之后的当前连接（之前为 null）。都由 [lock] 保护。 */
    private val subscriptions = HashMap<String, Int>()
    private var ready: WebSocket? = null

    /** 最近一次 `/v1/info` 估算的时钟差，收到 `hello` 时写进状态。只在连接协程中访问。 */
    private var clockOffsetMillis = 0L

    /** 只在连接协程中访问。 */
    private val recentEvents = RecentIds()

    // ---------------------------------------------------------------- 控制

    /** 开始连接（已经在连接、或者处于不再重连的状态时什么都不做）。 */
    fun start() {
        synchronized(lock) {
            started = true
            if (job?.isActive == true || _state.value.status.isTerminal) return
            launchLoop()
        }
    }

    /** 断开并停止重连。不再重连的状态（例如需要重新配对）保留，其他状态变为 [LinkStatus.Stopped]。 */
    fun stop() {
        val running =
            synchronized(lock) {
                started = false
                job.also { job = null }
            }
        // 等连接协程真正结束后再标记，避免它在被取消前最后一次写入的状态（例如 Online）覆盖掉 Stopped
        if (running == null) markStopped() else running.invokeOnCompletion { markStopped() }
        running?.cancel()
    }

    private fun markStopped() {
        synchronized(lock) {
            // 期间又调用了 start()：新的连接协程负责状态
            if (job != null) return
            _state.update { if (it.status.isTerminal) it else it.copy(status = LinkStatus.Stopped) }
        }
    }

    /** 关闭连接并等它真正断开（主机被删除或者地址、凭据改变时，[ConnectionManager] 先关旧连接再建新连接）。 */
    internal suspend fun close() {
        val running =
            synchronized(lock) {
                started = false
                job.also { job = null }
            }
        running?.cancelAndJoin()
    }

    /**
     * 立即重试：在退避等待中时跳过等待。[afterNetworkChange] 为 true 时，指纹不匹配的主机也重新尝试
     * （换了网络后，同一个地址可能已经指向另一台设备）；用户手动重试时所有不再重连的状态都重新尝试。
     */
    fun retryNow(afterNetworkChange: Boolean = false) {
        synchronized(lock) {
            if (!started) return
            val status = _state.value.status
            if (job?.isActive == true) {
                wake.trySend(Unit)
            } else if (!afterNetworkChange || status == LinkStatus.FingerprintMismatch) {
                launchLoop()
            }
        }
    }

    /** 主机记录中和连接无关的字段变了（例如 lastGoodAddress、名称）。地址或凭据变化时由 [ConnectionManager] 重建连接。 */
    internal fun updateHost(newHost: Host) {
        require(newHost.hostId == hostId)
        host = newHost
    }

    /**
     * 调用 REST 接口（新建会话、读取历史、发消息等）用的客户端。只在在线时可用：地址是这条连接正在用的、
     * 已经通过指纹校验的地址，客户端同样固定这台主机的指纹。不在线时返回 null。
     */
    fun api(): PluginApi? {
        val address = (_state.value.status as? LinkStatus.Online)?.address ?: return null
        val credentials = host.credentials ?: return null
        return PluginApi(clientFor(credentials.fingerprint), address, credentials.token)
    }

    // ---------------------------------------------------------------- 订阅

    /**
     * 订阅一个会话的消息（会话详情页打开期间收集这个 Flow）。按引用计数管理：第一个收集者开始时发送 `subscribe`，
     * 最后一个结束时发送 `unsubscribe`。每次订阅在连接上生效（包括重连后）都会先收到 [SessionEvent.Resync]。
     */
    fun subscribe(sessionId: String): Flow<SessionEvent> {
        require(SESSION_ID.matches(sessionId)) { "sessionId 格式不对" }
        return flow {
            sessionEvents
                .onSubscription { if (acquire(sessionId)) emit(SessionEvent.Resync(sessionId)) }
                .onCompletion { release(sessionId) }
                .filter { it.sessionId == sessionId }
                .collect { emit(it) }
        }
    }

    /** 返回订阅是否已经在当前连接上生效。 */
    private fun acquire(sessionId: String): Boolean =
        synchronized(lock) {
            val count = (subscriptions[sessionId] ?: 0) + 1
            subscriptions[sessionId] = count
            if (count == 1) ready?.send(StreamCommands.subscribe(sessionId))
            ready != null
        }

    private fun release(sessionId: String) {
        synchronized(lock) {
            val count = (subscriptions[sessionId] ?: return) - 1
            if (count > 0) {
                subscriptions[sessionId] = count
            } else {
                subscriptions.remove(sessionId)
                ready?.send(StreamCommands.unsubscribe(sessionId))
            }
        }
    }

    // ---------------------------------------------------------------- 连接循环

    private fun launchLoop() {
        job?.cancel()
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        backoff.reset()
        while (true) {
            val current = host
            val credentials = current.credentials
            if (credentials == null) {
                setStatus(LinkStatus.NeedsPairing(PairingReason.CREDENTIALS_LOST))
                return
            }
            setStatus(LinkStatus.Connecting)
            when (val outcome = connectOnce(current, credentials)) {
                is Outcome.Stop -> {
                    setStatus(outcome.status)
                    return
                }
                is Outcome.Retry -> {
                    val wait = if (outcome.slow) backoff.maxMillis else backoff.next()
                    setStatus(outcome.dshIncompatible ?: LinkStatus.Offline(outcome.reason, wait))
                    withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        }
    }

    private suspend fun connectOnce(
        current: Host,
        credentials: Credentials,
    ): Outcome {
        val client = clientFor(credentials.fingerprint)
        val (address, info) =
            when (val picked = pickAddress(client, current)) {
                is Probe.Ok -> picked.address to picked.info
                Probe.Mismatch -> return Outcome.Stop(LinkStatus.FingerprintMismatch)
                is Probe.Failed -> return Outcome.Retry(picked.reason)
            }
        if (info.protocol != PluginApi.PROTOCOL) return Outcome.Stop(LinkStatus.AppTooOld(info.protocol))
        clockOffsetMillis = info.clockOffsetMillis ?: 0
        if (info.dsh.compatible == false) {
            return Outcome.Retry(OfflineReason.DSH_UNAVAILABLE, dshIncompatible = LinkStatus.DshIncompatible(info.dsh.version))
        }
        return stream(client, address, credentials.token)
    }

    // ---------------------------------------------------------------- 选地址

    private suspend fun pickAddress(
        client: OkHttpClient,
        current: Host,
    ): Probe {
        val preferred = current.lastGoodAddress?.takeIf { it in current.addresses }
        val failures = mutableListOf<Probe>()
        if (preferred != null) {
            val result = probe(client, preferred)
            if (result is Probe.Ok) return result
            failures += result
        }
        val others = current.addresses.filter { it != preferred }
        if (others.isNotEmpty()) {
            val result = race(client, others)
            if (result is Probe.Ok) return result
            failures += result
        }
        return combine(failures)
    }

    /** 同时探测多个地址，第一个成功的获胜，其余的取消。 */
    private suspend fun race(
        client: OkHttpClient,
        addresses: List<HostAddress>,
    ): Probe =
        coroutineScope {
            val results = Channel<Probe>(addresses.size)
            val probes = addresses.map { address -> launch { results.send(probe(client, address)) } }
            val failures = mutableListOf<Probe>()
            repeat(addresses.size) {
                val result = results.receive()
                if (result is Probe.Ok) {
                    probes.forEach { it.cancel() }
                    return@coroutineScope result
                }
                failures += result
            }
            combine(failures)
        }

    private suspend fun probe(
        client: OkHttpClient,
        address: HostAddress,
    ): Probe =
        try {
            Probe.Ok(address, PluginApi(client, address, token = null).info())
        } catch (_: ApiException.FingerprintMismatch) {
            Probe.Mismatch
        } catch (_: ApiException.Network) {
            Probe.Failed(OfflineReason.UNREACHABLE)
        } catch (e: ApiException.Http) {
            Probe.Failed(if (e.code == ErrorCode.DSH_UNAVAILABLE) OfflineReason.DSH_UNAVAILABLE else OfflineReason.PROTOCOL_ERROR)
        } catch (_: ApiException.BadResponse) {
            Probe.Failed(OfflineReason.PROTOCOL_ERROR)
        }

    /** 所有地址都失败时的结论：只要有一个地址指纹不匹配，就报告指纹不匹配；其次是能连上但内容不对的地址。 */
    private fun combine(failures: List<Probe>): Probe =
        failures.firstOrNull { it == Probe.Mismatch }
            ?: failures.firstOrNull { it is Probe.Failed && it.reason != OfflineReason.UNREACHABLE }
            ?: Probe.Failed(OfflineReason.UNREACHABLE)

    // ---------------------------------------------------------------- WebSocket

    private suspend fun stream(
        client: OkHttpClient,
        address: HostAddress,
        token: DeviceToken,
    ): Outcome {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val request =
            Request
                .Builder()
                .url(streamUrl(address))
                .header("Authorization", "Bearer ${token.value}")
                .build()
        val socket =
            client
                .newBuilder()
                .pingInterval(timing.pingIntervalMillis, TimeUnit.MILLISECONDS)
                .build()
                .newWebSocket(request, SignalListener(signals))
        var greeted = false
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timing.helloTimeoutMillis)
            while (true) {
                val signal =
                    if (greeted) {
                        signals.receive()
                    } else {
                        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        withTimeoutOrNull(remaining.coerceAtLeast(1)) { signals.receive() }
                            ?: run {
                                log("主机 $hostId：等待 hello 超时")
                                return Outcome.Retry(OfflineReason.PROTOCOL_ERROR)
                            }
                    }
                when (signal) {
                    Signal.Open -> Unit
                    is Signal.Text -> {
                        val event = StreamEvent.parse(signal.text)
                        if (event == null) {
                            log("主机 $hostId：无法解析的消息，已忽略")
                            continue
                        }
                        // hello 之前插件只会发 error（例如 DSH 不可用，随后以 1013 关闭），其他消息不应出现，忽略
                        if (!greeted && event !is StreamEvent.Hello && event !is StreamEvent.Error) continue
                        if (!recentEvents.add(event.eventId)) continue
                        if (event is StreamEvent.Hello) {
                            if (event.protocol != PluginApi.PROTOCOL) return Outcome.Stop(LinkStatus.AppTooOld(event.protocol))
                            greeted = true
                            onHello(event, address, socket)
                        } else {
                            handle(event)
                        }
                    }
                    is Signal.Closing -> return closed(signal.code)
                    is Signal.Failure -> return failed(signal, greeted)
                }
            }
        } finally {
            synchronized(lock) { if (ready === socket) ready = null }
            if (!socket.close(NORMAL_CLOSURE, null)) socket.cancel()
        }
    }

    private suspend fun onHello(
        hello: StreamEvent.Hello,
        address: HostAddress,
        socket: WebSocket,
    ) {
        backoff.reset()
        wake.tryReceive()
        _state.value =
            HostLiveState(
                status = LinkStatus.Online(address),
                sessions = hello.sessions.associateBy { it.sessionId },
                attention = hello.attention.associateBy { it.attentionId },
                clockOffsetMillis = clockOffsetMillis,
            )
        // 重新订阅所有正在收集的会话，并让它们重新读取最新一页历史
        val resubscribed =
            synchronized(lock) {
                ready = socket
                subscriptions.keys.toList().onEach { socket.send(StreamCommands.subscribe(it)) }
            }
        resubscribed.forEach { sessionEvents.emit(SessionEvent.Resync(it)) }
        if (host.lastGoodAddress != address) {
            try {
                markGoodAddress(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例如主机刚好被删除了（连接马上会被 ConnectionManager 关闭），或者写文件失败。只影响下次先试哪个地址
                log("主机 $hostId：记录地址失败（${e.javaClass.simpleName}）")
            }
        }
    }

    private suspend fun handle(event: StreamEvent) {
        when (event) {
            is StreamEvent.SessionUpdated ->
                _state.update { it.copy(sessions = it.sessions + (event.session.sessionId to event.session)) }
            is StreamEvent.SessionRemoved -> _state.update { it.copy(sessions = it.sessions - event.sessionId) }
            is StreamEvent.SessionIdle -> liveEvents.emit(LiveEvent.SessionIdle(hostId, event.sessionId, event.title, event.result))
            is StreamEvent.AttentionRequested -> {
                _state.update { it.copy(attention = it.attention + (event.attention.attentionId to event.attention)) }
                liveEvents.emit(LiveEvent.AttentionRequested(hostId, event.attention))
            }
            is StreamEvent.AttentionResolved -> {
                _state.update { it.copy(attention = it.attention - event.attentionId) }
                liveEvents.emit(LiveEvent.AttentionResolved(hostId, event.attentionId, event.sessionId, event.by))
            }
            is StreamEvent.MessageUpsert ->
                sessionEvents.emit(SessionEvent.Upsert(event.sessionId, event.rev, event.message, event.replaces))
            is StreamEvent.MessageRemoved -> sessionEvents.emit(SessionEvent.Removed(event.sessionId, event.messageId))
            is StreamEvent.TurnUpdated -> sessionEvents.emit(SessionEvent.TurnUpdated(event.sessionId, event.turn))
            is StreamEvent.Error -> log("主机 $hostId：插件报告错误 ${event.code.name}")
            is StreamEvent.Unknown, is StreamEvent.Hello -> Unit
        }
    }

    private fun closed(code: Int): Outcome =
        when (code) {
            CLOSE_REVOKED -> Outcome.Stop(LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED))
            CLOSE_TRY_AGAIN -> Outcome.Retry(OfflineReason.DSH_UNAVAILABLE)
            CLOSE_POLICY -> {
                log("主机 $hostId：插件以 1008 关闭了连接，App 违反了协议（App 的 bug）")
                Outcome.Retry(OfflineReason.PROTOCOL_ERROR)
            }
            else -> Outcome.Retry(OfflineReason.DISCONNECTED)
        }

    private fun failed(
        failure: Signal.Failure,
        greeted: Boolean,
    ): Outcome =
        when {
            failure.fingerprintMismatch -> Outcome.Stop(LinkStatus.FingerprintMismatch)
            failure.status == HTTP_UNAUTHORIZED -> Outcome.Stop(LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED))
            failure.status == HTTP_TOO_MANY_REQUESTS -> Outcome.Retry(OfflineReason.TOO_MANY_CONNECTIONS)
            failure.status == HTTP_UNAVAILABLE -> Outcome.Retry(OfflineReason.DSH_UNAVAILABLE)
            failure.status != null -> {
                log("主机 $hostId：WebSocket 握手失败，HTTP ${failure.status}")
                Outcome.Retry(OfflineReason.PROTOCOL_ERROR)
            }
            greeted -> Outcome.Retry(OfflineReason.DISCONNECTED)
            else -> Outcome.Retry(OfflineReason.UNREACHABLE)
        }

    private fun setStatus(status: LinkStatus) = _state.update { it.copy(status = status) }

    private fun streamUrl(address: HostAddress): HttpUrl =
        HttpUrl
            .Builder()
            .scheme("https")
            .host(address.host)
            .port(address.port)
            .addPathSegments("v1/stream")
            .build()

    // ---------------------------------------------------------------- 内部类型

    private sealed interface Outcome {
        data class Stop(
            val status: LinkStatus,
        ) : Outcome

        /** [slow] 为 true 时按最长的退避间隔等待（DSH 版本不兼容时不需要频繁探测）。 */
        data class Retry(
            val reason: OfflineReason,
            val dshIncompatible: LinkStatus.DshIncompatible? = null,
        ) : Outcome {
            val slow: Boolean get() = dshIncompatible != null
        }
    }

    private sealed interface Probe {
        data class Ok(
            val address: HostAddress,
            val info: PluginInfo,
        ) : Probe

        data object Mismatch : Probe

        data class Failed(
            val reason: OfflineReason,
        ) : Probe
    }

    private sealed interface Signal {
        data object Open : Signal

        class Text(
            val text: String,
        ) : Signal {
            override fun toString(): String = "Text(length=${text.length})"
        }

        data class Closing(
            val code: Int,
        ) : Signal

        data class Failure(
            /** 握手时插件返回的 HTTP 状态码（没有收到响应时为 null）。 */
            val status: Int?,
            val fingerprintMismatch: Boolean,
        ) : Signal
    }

    /** 把 OkHttp 线程上的回调转成 [Signal]，由连接协程按顺序处理。 */
    private class SignalListener(
        private val signals: Channel<Signal>,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            signals.trySend(Signal.Open)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            signals.trySend(Signal.Text(text))
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
            signals.trySend(Signal.Closing(code))
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            signals.trySend(Signal.Failure(response?.code, t.isFingerprintMismatch()))
        }
    }

    internal companion object {
        /** protocol.md 6.0 的关闭码。 */
        const val CLOSE_REVOKED = 4401
        const val CLOSE_TRY_AGAIN = 1013
        const val CLOSE_POLICY = 1008
        private const val NORMAL_CLOSURE = 1000
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_UNAVAILABLE = 503
        private const val EVENT_BUFFER = 256
        private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    }
}
