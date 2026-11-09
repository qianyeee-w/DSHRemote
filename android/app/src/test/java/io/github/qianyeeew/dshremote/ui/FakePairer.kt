package io.github.qianyeeew.dshremote.ui

import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import io.github.qianyeeew.dshremote.data.pairing.Pairer
import io.github.qianyeeew.dshremote.data.pairing.PairingResult
import io.github.qianyeeew.dshremote.data.pairing.PairingTarget
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/** ViewModel 测试用的配对操作：记录调用，返回预先设好的结果。 */
internal class FakePairer : Pairer {
    var probeResult: PairingProbe.Result = PairingProbe.Result.Fingerprinted(FINGERPRINT)
    var pairResult: PairingResult = PairingResult.Paired(host(), replaced = false)
    var unpairResult = true

    /** 不为 null 时，pair 等它完成后才返回（用来观察「正在配对」的状态）。 */
    var pairGate: CompletableDeferred<Unit>? = null

    val probes = CopyOnWriteArrayList<HostAddress>()
    val pairs = CopyOnWriteArrayList<Pair<PairingTarget, String?>>()
    val unpaired = CopyOnWriteArrayList<Host>()

    override suspend fun probe(address: HostAddress): PairingProbe.Result {
        probes += address
        return probeResult
    }

    override suspend fun pair(
        target: PairingTarget,
        replaceHostId: String?,
    ): PairingResult {
        pairs += target to replaceHostId
        pairGate?.await()
        return pairResult
    }

    override suspend fun unpair(host: Host): Boolean {
        unpaired += host
        return unpairResult
    }

    companion object {
        val FINGERPRINT: Fingerprint = Fingerprint.parse("Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M")!!
        val OTHER_FINGERPRINT: Fingerprint = Fingerprint.parse("E9JMy_oGlrnfgc22ShLnbW3szeR8nFwHFvPZ7TCjzPw")!!
        val TOKEN: DeviceToken = DeviceToken.parse("tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120")!!

        fun host(
            hostId: String = "h1",
            name: String = "MY-PC",
            addresses: List<HostAddress> = listOf(HostAddress("192.168.1.5", 3190)),
            credentials: Credentials? = Credentials(FINGERPRINT, TOKEN),
        ) = Host(hostId, name, addresses, addresses.first(), "d_1", credentials)
    }
}
