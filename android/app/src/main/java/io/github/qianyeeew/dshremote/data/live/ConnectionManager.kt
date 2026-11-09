package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 所有主机的实时连接（Application 作用域，m2-android.md 3.3）。
 *
 * - 跟随 [hosts]（来自 HostRepository）：新增主机时建立连接，删除时关闭；地址、凭据或设备 id 变化时
 *   先关闭旧连接再建新连接（每台主机始终只有一条连接）。lastGoodAddress 和名称的变化不重建连接。
 * - 只有存在 [hold] 时才连接：界面可见时和前台服务运行时各持有一个（T7、T11）。最后一个释放后断开所有连接。
 * - 网络变化时调用 [onNetworkAvailable]，正在退避等待的连接立即重试。
 */
class ConnectionManager(
    hosts: Flow<List<Host>>,
    private val scope: CoroutineScope,
    /** 每台主机的 OkHttpClient，必须来自 ClientFactory.forPin（证书指纹校验）。 */
    private val clientFor: (Fingerprint) -> OkHttpClient,
    private val markGoodAddress: suspend (hostId: String, address: HostAddress) -> Unit,
    /** 日志输出。传进来的内容不含 token、配对码和消息内容。 */
    private val log: (String) -> Unit,
    private val timing: LiveTiming = LiveTiming(),
    private val newBackoff: () -> Backoff = { Backoff() },
) {
    private val lock = Any()
    private val connections = LinkedHashMap<String, Entry>()
    private var holds = 0

    private val _states = MutableStateFlow<Map<String, HostLiveState>>(emptyMap())

    /** 每台主机的实时状态，key 是 hostId。 */
    val states: StateFlow<Map<String, HostLiveState>> = _states.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** 所有主机的通知类事件（任务完成、新的待处理事项、待处理事项已处理），供前台服务发通知（T11）。 */
    val events: SharedFlow<LiveEvent> = _events.asSharedFlow()

    init {
        scope.launch { hosts.collect { sync(it) } }
    }

    fun connection(hostId: String): HostConnection? = synchronized(lock) { connections[hostId]?.connection }

    /** 需要保持连接时持有，不再需要时调用 [Hold.release]（可以重复调用，只生效一次）。 */
    fun hold(): Hold {
        val first =
            synchronized(lock) {
                holds++
                holds == 1
            }
        if (first) forEach { it.start() }
        return Hold()
    }

    /** 网络变化（有新的可用网络）：所有连接立即重试。 */
    fun onNetworkAvailable() = forEach { it.retryNow(afterNetworkChange = true) }

    /** 用户手动重试一台主机（包括不再自动重连的状态）。 */
    fun retry(hostId: String) = connection(hostId)?.retryNow()

    inner class Hold internal constructor() {
        private var released = false

        fun release() {
            val last =
                synchronized(lock) {
                    if (released) return
                    released = true
                    holds--
                    holds == 0
                }
            if (last) forEach { it.stop() }
        }
    }

    private suspend fun sync(list: List<Host>) {
        val byId = list.associateBy { it.hostId }
        val toClose = mutableListOf<Entry>()
        val toStart = mutableListOf<HostConnection>()
        synchronized(lock) {
            val iterator = connections.entries.iterator()
            while (iterator.hasNext()) {
                val (hostId, entry) = iterator.next()
                val host = byId[hostId]
                if (host == null || host.connectionKey() != entry.key) {
                    toClose += entry
                    iterator.remove()
                } else {
                    entry.connection.updateHost(host)
                }
            }
            for (host in list) {
                if (host.hostId in connections) continue
                val connection =
                    HostConnection(
                        host = host,
                        scope = scope,
                        clientFor = clientFor,
                        markGoodAddress = { address -> markGoodAddress(host.hostId, address) },
                        liveEvents = _events,
                        log = log,
                        timing = timing,
                        backoff = newBackoff(),
                    )
                val watcher = scope.launch { connection.state.collect { s -> _states.update { it + (host.hostId to s) } } }
                connections[host.hostId] = Entry(host.connectionKey(), connection, watcher)
                if (holds > 0) toStart += connection
            }
        }
        // 先关闭旧连接，再启动新连接
        for (entry in toClose) {
            entry.watcher.cancel()
            entry.connection.close()
            val hostId = entry.connection.hostId
            if (connection(hostId) == null) _states.update { it - hostId }
        }
        toStart.forEach { it.start() }
    }

    private fun forEach(action: (HostConnection) -> Unit) {
        val all = synchronized(lock) { connections.values.map { it.connection } }
        all.forEach(action)
    }

    private class Entry(
        val key: ConnectionKey,
        val connection: HostConnection,
        val watcher: Job,
    )

    /** 决定连接的字段：这些变化时要重建连接。 */
    private data class ConnectionKey(
        val addresses: List<HostAddress>,
        val deviceId: String,
        val credentials: Credentials?,
    )

    private fun Host.connectionKey() = ConnectionKey(addresses, deviceId, credentials)

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
