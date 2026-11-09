package io.github.qianyeeew.dshremote.data.pairing

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.host.HostNames
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.data.host.PairUri
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * 一次配对要用的信息：来自扫到的二维码，或者手动输入、并且用户已经核对过指纹（protocol.md 2.1、2.3）。
 *
 * [fingerprint] 是信任的依据：配对请求只会发给证书指纹等于它的服务器，配对码也只在这样的连接上发出。
 * [toString] 不输出配对码（红线第 5 条）。
 */
class PairingTarget(
    val address: HostAddress,
    val fingerprint: Fingerprint,
    val code: String,
    /** 二维码中的电脑名，只在插件没有返回名称时使用。 */
    val suggestedName: String? = null,
) {
    init {
        require(PairUri.isValidCode(code)) { "配对码格式不对" }
    }

    override fun toString(): String = "PairingTarget(address=$address, fingerprint=$fingerprint, code=***)"

    companion object {
        fun of(uri: PairUri) = PairingTarget(uri.address, uri.fingerprint, uri.code, uri.name)
    }
}

sealed interface PairingResult {
    /** 配对成功并已保存。[replaced] 为 true 表示更新了已有的主机（重新配对，或者同一台电脑配对了第二次）。 */
    data class Paired(
        val host: Host,
        val replaced: Boolean,
    ) : PairingResult

    /** `400 PAIRING_CODE_INVALID`：配对码不对、已过期或已经用过（插件不区分这几种情况）。 */
    data object CodeInvalid : PairingResult

    /** `429 PAIRING_LOCKED`：输错太多次，配对接口暂时锁定。 */
    data class Locked(
        val retryAfterSeconds: Long?,
    ) : PairingResult

    /** `409 PAIRING_DEVICE_LIMIT`：电脑上已配对的设备达到上限。 */
    data object DeviceLimit : PairingResult

    /** 服务器证书的指纹和二维码或核对过的不一致。直接拒绝，没有「仍然继续」（红线第 2 条）。 */
    data object FingerprintMismatch : PairingResult

    /** 连不上电脑。 */
    data object Unreachable : PairingResult

    /** 插件的协议版本这个 App 不认识，需要升级 App。 */
    data class AppTooOld(
        val protocol: Int,
    ) : PairingResult

    /** 其他错误（插件返回了意外的错误或无法解析的内容，或者保存失败）。 */
    data object Failed : PairingResult
}

/** 界面层用到的配对操作（ViewModel 测试中替换成假的实现）。 */
interface Pairer {
    /** 手动配对的第一步：取得这个地址上服务器证书的指纹，给用户核对（不发送任何请求）。 */
    suspend fun probe(address: HostAddress): PairingProbe.Result

    /**
     * 用配对码换取 token 并保存。[replaceHostId] 不为 null 时是重新配对这台主机；
     * 否则如果已经有指纹相同的主机，也更新那一台，不重复添加。
     */
    suspend fun pair(
        target: PairingTarget,
        replaceHostId: String? = null,
    ): PairingResult

    /**
     * 取消配对：先尽量调用 `DELETE /v1/me` 让电脑上的 token 失效，再删除本地记录（m2-android.md 3.2）。
     * 返回电脑上的 token 是否确认已经失效；为 false 时界面提示用户到配对页上撤销。
     */
    suspend fun unpair(host: Host): Boolean
}

/**
 * 配对、重新配对和取消配对（m2-android.md 3.2、T7）。
 *
 * 配对的顺序：用二维码里（或核对过）的指纹建立固定指纹的连接 → `/v1/info` 检查协议版本 → `/v1/pair` 发送配对码 →
 * 把 token 和指纹加密保存。指纹不对时在 TLS 握手阶段就失败，配对码不会发出。
 */
class PairingService(
    private val repository: HostRepository,
    private val clientFactory: ClientFactory,
    private val prober: PairingProbe = PairingProbe(),
    /** 这台手机的名字，显示在电脑的配对页上。 */
    private val deviceName: () -> String,
    /** 日志输出。传进来的内容不含 token 和配对码。 */
    private val log: (String) -> Unit,
    private val revokeTimeoutMillis: Long = REVOKE_TIMEOUT_MILLIS,
) : Pairer {
    override suspend fun probe(address: HostAddress): PairingProbe.Result = prober.probe(address.host, address.port)

    override suspend fun pair(
        target: PairingTarget,
        replaceHostId: String?,
    ): PairingResult {
        val api = PluginApi(clientFactory.forPin(target.fingerprint), target.address, token = null)
        val paired =
            try {
                val info = api.info()
                if (info.protocol != PluginApi.PROTOCOL) return PairingResult.AppTooOld(info.protocol)
                api.pair(target.code, deviceName())
            } catch (e: ApiException) {
                return failure(e)
            }

        val credentials = Credentials(target.fingerprint, paired.token)
        return try {
            save(target, replaceHostId, paired.deviceId, paired.hostName, credentials)
        } catch (e: IOException) {
            discard(target, credentials, "保存失败（${e.javaClass.simpleName}）")
        } catch (e: IllegalArgumentException) {
            // 例如重新配对的主机在配对过程中被删除了
            discard(target, credentials, "保存失败（${e.message}）")
        }
    }

    override suspend fun unpair(host: Host): Boolean {
        val revoked = host.credentials?.let { revoke(host.addresses.preferring(host.lastGoodAddress), it) } ?: false
        repository.remove(host.hostId)
        log("已删除主机 ${host.hostId}，电脑上的设备${if (revoked) "已撤销" else "没有确认撤销"}")
        return revoked
    }

    private suspend fun save(
        target: PairingTarget,
        replaceHostId: String?,
        deviceId: String,
        hostName: String,
        credentials: Credentials,
    ): PairingResult.Paired {
        val existing =
            if (replaceHostId != null) {
                requireNotNull(repository.get(replaceHostId)) { "要重新配对的主机已经不存在" }
            } else {
                repository.hosts.first().firstOrNull { it.credentials?.fingerprint == target.fingerprint }
            }
        if (existing == null) {
            val name = HostNames.clean(hostName).ifEmpty { target.suggestedName ?: target.address.host }
            val host = repository.add(name, target.address, deviceId, credentials)
            log("配对成功，新增主机 ${host.hostId}")
            return PairingResult.Paired(host, replaced = false)
        }

        repository.replaceCredentials(existing.hostId, deviceId, credentials)
        if (target.address !in existing.addresses) {
            // 新地址放在最前面；地址太多时去掉最后一个
            repository.setAddresses(existing.hostId, (listOf(target.address) + existing.addresses).take(HostRepository.MAX_ADDRESSES))
        }
        repository.markGoodAddress(existing.hostId, target.address)
        log("重新配对成功，更新主机 ${existing.hostId}")

        // 同一台电脑上旧的设备记录不再使用，顺便撤销，免得占用设备数量的上限
        val old = existing.credentials
        if (old != null && old.fingerprint == target.fingerprint && old.token != credentials.token) {
            revoke(listOf(target.address), old)
        }
        return PairingResult.Paired(checkNotNull(repository.get(existing.hostId)), replaced = true)
    }

    /** 已经在电脑上配对成功，但本地保存失败：撤销刚拿到的 token，不留下用不了的设备记录。 */
    private suspend fun discard(
        target: PairingTarget,
        credentials: Credentials,
        why: String,
    ): PairingResult {
        log("配对后$why，撤销刚配对的设备")
        revoke(listOf(target.address), credentials)
        return PairingResult.Failed
    }

    /**
     * 用这个 token 调用 `DELETE /v1/me`，依次试每个地址，总时间不超过 [revokeTimeoutMillis]。
     * 返回 true 表示 token 在电脑上已经失效（包括本来就已经被撤销，`401`）。
     */
    private suspend fun revoke(
        addresses: List<HostAddress>,
        credentials: Credentials,
    ): Boolean =
        withTimeoutOrNull(revokeTimeoutMillis) {
            val client = clientFactory.forPin(credentials.fingerprint)
            for (address in addresses) {
                if (revokeAt(PluginApi(client, address, credentials.token))) return@withTimeoutOrNull true
            }
            false
        } ?: false

    private suspend fun revokeAt(api: PluginApi): Boolean =
        try {
            api.deleteMe()
            true
        } catch (e: ApiException) {
            e is ApiException.Http && (e.status == HTTP_UNAUTHORIZED || e.code == ErrorCode.TOKEN_INVALID)
        }

    private fun failure(e: ApiException): PairingResult {
        val result =
            when (e) {
                is ApiException.FingerprintMismatch -> PairingResult.FingerprintMismatch
                is ApiException.Network -> PairingResult.Unreachable
                is ApiException.BadResponse -> PairingResult.Failed
                is ApiException.Http ->
                    when (e.code) {
                        ErrorCode.PAIRING_CODE_INVALID -> PairingResult.CodeInvalid
                        ErrorCode.PAIRING_LOCKED -> PairingResult.Locked(e.retryAfterSeconds)
                        ErrorCode.PAIRING_DEVICE_LIMIT -> PairingResult.DeviceLimit
                        else -> PairingResult.Failed
                    }
            }
        // ApiException 的信息不含配对码和 token
        log("配对失败：${e.message}")
        return result
    }

    private companion object {
        const val REVOKE_TIMEOUT_MILLIS = 8_000L
        const val HTTP_UNAUTHORIZED = 401
    }
}

/** 把 [first] 排到最前面（如果在列表中）。 */
private fun List<HostAddress>.preferring(first: HostAddress?): List<HostAddress> =
    if (first != null && first in this) listOf(first) + (this - first) else this

/** 用户输入的配对码：去掉空格和连字符，转成大写（插件同样忽略这些，protocol.md 2.2）。格式不对时返回 null。 */
fun normalizePairingCode(input: String): String? =
    input
        .filterNot { it.isWhitespace() || it == '-' }
        .uppercase()
        .takeIf(PairUri::isValidCode)
