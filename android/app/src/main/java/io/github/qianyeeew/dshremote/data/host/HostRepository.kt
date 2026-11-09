package io.github.qianyeeew.dshremote.data.host

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/**
 * 已配对主机的列表，保存在 DataStore 中（文件 `hosts.json`，不进入备份）。
 *
 * - 指纹和 token 合在一起，用 [SecretBox]（Android Keystore 中的 AES-GCM 密钥）加密后保存，`hostId` 作为附加认证数据；
 *   其他字段（名称、地址、deviceId）明文保存（m2-android.md 3.2）。
 * - 凭据无法解密时（例如密钥已经不存在、文件被改动），这台主机的 [Host.credentials] 为 null，需要重新配对。
 * - 文件本身损坏、无法解析时，整个列表被清空，所有主机需要重新配对。
 *
 * 每个进程只能有一个实例（DataStore 的要求），由 Application 统一创建（T7 起）。
 */
class HostRepository internal constructor(
    private val store: DataStore<HostsFile>,
    private val secretBox: SecretBox,
    private val newHostId: () -> String = { UUID.randomUUID().toString() },
) {
    val hosts: Flow<List<Host>> =
        store.data
            .map { file -> file.hosts.map(::toHost) }
            .flowOn(Dispatchers.IO)

    suspend fun get(hostId: String): Host? = hosts.first().firstOrNull { it.hostId == hostId }

    /** 保存一台新配对的电脑，返回它的记录。 */
    suspend fun add(
        name: String,
        address: HostAddress,
        deviceId: String,
        credentials: Credentials,
    ): Host {
        val hostId = newHostId()
        val stored =
            StoredHost(
                hostId = hostId,
                name = requireName(name),
                addresses = listOf(StoredAddress.of(address)),
                lastGoodAddress = StoredAddress.of(address),
                deviceId = requireDeviceId(deviceId),
                secret = seal(hostId, credentials),
            )
        store.updateData { file -> file.copy(hosts = file.hosts + stored) }
        return toHost(stored)
    }

    /** 重新配对：换成新的设备 id、token 和指纹，其他信息（名称、地址）保留。 */
    suspend fun replaceCredentials(
        hostId: String,
        deviceId: String,
        credentials: Credentials,
    ) {
        val validDeviceId = requireDeviceId(deviceId)
        val secret = seal(hostId, credentials)
        update(hostId) { it.copy(deviceId = validDeviceId, secret = secret) }
    }

    /** 重命名。名称按 [HostNames.clean] 清理，清理后为空时抛出 IllegalArgumentException。 */
    suspend fun rename(
        hostId: String,
        name: String,
    ) {
        val cleaned = requireName(name)
        update(hostId) { it.copy(name = cleaned) }
    }

    /**
     * 替换地址列表（用户编辑地址）。地址不是信任的依据，修改后不需要重新配对。
     * 去掉重复项，至少保留一个、最多 [MAX_ADDRESSES] 个。[Host.lastGoodAddress] 不在新列表中时清空。
     */
    suspend fun setAddresses(
        hostId: String,
        addresses: List<HostAddress>,
    ) {
        val unique = addresses.distinct()
        require(unique.isNotEmpty()) { "至少需要一个地址" }
        require(unique.size <= MAX_ADDRESSES) { "地址太多" }
        val stored = unique.map(StoredAddress::of)
        update(hostId) { host ->
            host.copy(addresses = stored, lastGoodAddress = host.lastGoodAddress?.takeIf { it in stored })
        }
    }

    /** 记下最近一次连接成功的地址（必须是这台主机的地址之一，否则忽略）。 */
    suspend fun markGoodAddress(
        hostId: String,
        address: HostAddress,
    ) {
        val stored = StoredAddress.of(address)
        update(hostId) { host ->
            if (stored in host.addresses && host.lastGoodAddress != stored) host.copy(lastGoodAddress = stored) else host
        }
    }

    /** 删除本地记录（调用 `DELETE /v1/me` 由界面层负责，见 m2-android.md 3.2）。 */
    suspend fun remove(hostId: String) {
        store.updateData { file -> file.copy(hosts = file.hosts.filterNot { it.hostId == hostId }) }
    }

    private suspend fun update(
        hostId: String,
        change: (StoredHost) -> StoredHost,
    ) {
        store.updateData { file ->
            require(file.hosts.any { it.hostId == hostId }) { "主机不存在" }
            file.copy(hosts = file.hosts.map { if (it.hostId == hostId) change(it) else it })
        }
    }

    private fun seal(
        hostId: String,
        credentials: Credentials,
    ): String {
        val payload = SecretPayload(fp = credentials.fingerprint.value, token = credentials.token.value)
        val sealed =
            secretBox.seal(
                JSON.encodeToString(SecretPayload.serializer(), payload).encodeToByteArray(),
                hostId.encodeToByteArray(),
            )
        return Base64.getEncoder().encodeToString(sealed)
    }

    private fun toHost(stored: StoredHost): Host {
        val addresses = stored.addresses.mapNotNull { it.toAddressOrNull() }
        return Host(
            hostId = stored.hostId,
            name = stored.name,
            addresses = addresses,
            lastGoodAddress = stored.lastGoodAddress?.toAddressOrNull()?.takeIf { it in addresses },
            deviceId = stored.deviceId,
            credentials = open(stored),
        )
    }

    /** 解密失败或内容不对时返回 null（需要重新配对），不抛出异常，也不记录任何内容。 */
    private fun open(stored: StoredHost): Credentials? =
        try {
            val sealed = Base64.getDecoder().decode(stored.secret)
            val plain = secretBox.open(sealed, stored.hostId.encodeToByteArray()).decodeToString()
            val payload = JSON.decodeFromString(SecretPayload.serializer(), plain)
            val fingerprint = Fingerprint.parse(payload.fp)
            val token = DeviceToken.parse(payload.token)
            if (fingerprint != null && token != null) Credentials(fingerprint, token) else null
        } catch (_: SecretBoxException) {
            null
        } catch (_: IllegalArgumentException) {
            // Base64 格式不对，或 JSON 解析失败（SerializationException 是 IllegalArgumentException 的子类）
            null
        }

    companion object {
        const val MAX_ADDRESSES = 8
        private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** 每个进程只调用一次。 */
        fun create(context: Context): HostRepository =
            HostRepository(
                store =
                    DataStoreFactory.create(
                        serializer = HostsFileSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler { HostsFile() },
                        produceFile = { context.applicationContext.dataStoreFile("hosts.json") },
                    ),
                secretBox = KeystoreKeys.hostsSecretBox(),
            )

        private fun requireName(name: String): String = HostNames.clean(name).also { require(it.isNotEmpty()) { "名称不能为空" } }

        private fun requireDeviceId(deviceId: String): String = deviceId.also { require(DEVICE_ID.matches(it)) { "deviceId 格式不对" } }
    }
}

private val JSON = Json { ignoreUnknownKeys = true }

/** 存储文件的格式。字段改名或删除时要提高 [version]，并处理旧格式。 */
@Serializable
internal data class HostsFile(
    val version: Int = CURRENT_VERSION,
    val hosts: List<StoredHost> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class StoredHost(
    val hostId: String,
    val name: String,
    val addresses: List<StoredAddress>,
    val lastGoodAddress: StoredAddress? = null,
    val deviceId: String,
    /** [SecretPayload] 的 JSON 经 [SecretBox] 加密后的 base64。 */
    val secret: String,
)

@Serializable
internal data class StoredAddress(
    val host: String,
    val port: Int,
) {
    fun toAddressOrNull(): HostAddress? = HostAddress.of(host, port.toString())

    companion object {
        fun of(address: HostAddress) = StoredAddress(address.host, address.port)
    }
}

/** 加密前的明文。[toString] 不输出 token。 */
@Serializable
private class SecretPayload(
    val fp: String,
    val token: String,
) {
    override fun toString(): String = "SecretPayload(***)"
}

internal object HostsFileSerializer : Serializer<HostsFile> {
    override val defaultValue: HostsFile = HostsFile()

    override suspend fun readFrom(input: InputStream): HostsFile {
        val file =
            try {
                JSON.decodeFromString(HostsFile.serializer(), input.readBytes().decodeToString())
            } catch (e: SerializationException) {
                throw CorruptionException("主机列表无法解析", e)
            } catch (e: IllegalArgumentException) {
                throw CorruptionException("主机列表无法解析", e)
            }
        if (file.version != HostsFile.CURRENT_VERSION) throw CorruptionException("主机列表的版本不支持")
        return file
    }

    override suspend fun writeTo(
        t: HostsFile,
        output: OutputStream,
    ) {
        output.write(JSON.encodeToString(HostsFile.serializer(), t).encodeToByteArray())
    }
}
