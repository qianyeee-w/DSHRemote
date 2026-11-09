package io.github.qianyeeew.dshremote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.host.PairUri
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import io.github.qianyeeew.dshremote.data.pairing.Pairer
import io.github.qianyeeew.dshremote.data.pairing.PairingResult
import io.github.qianyeeew.dshremote.data.pairing.PairingTarget
import io.github.qianyeeew.dshremote.data.pairing.normalizePairingCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 手动输入的表单。[toString] 不输出配对码。 */
data class ManualForm(
    val host: String = "",
    val port: String = DEFAULT_PORT,
    val code: String = "",
    val hostInvalid: Boolean = false,
    val portInvalid: Boolean = false,
    val codeInvalid: Boolean = false,
    /** 上一次探测失败的原因。 */
    val probeError: PairingProbe.Result? = null,
) {
    override fun toString(): String = "ManualForm(host=$host, port=$port, code=***)"

    companion object {
        /** 插件局域网端口的默认值（protocol.md 第 1 节）。 */
        const val DEFAULT_PORT = "3190"
    }
}

/** 配对流程的各个步骤（m2-android.md T7）。 */
sealed interface PairingStep {
    /** 扫码中。[invalidQr] 为 true 时提示扫到的不是配对二维码。 */
    data class Scan(
        val invalidQr: Boolean = false,
    ) : PairingStep

    data class Manual(
        val form: ManualForm,
    ) : PairingStep

    /** 手动配对：正在取得服务器证书的指纹。 */
    data class Probing(
        val address: HostAddress,
    ) : PairingStep

    /** 手动配对：请用户把指纹的前 8 位和电脑配对页上的核对（protocol.md 2.3）。 */
    data class ConfirmFingerprint(
        val address: HostAddress,
        val fingerprint: Fingerprint,
    ) : PairingStep

    /** 用户说指纹不一致：配对码不会发出。没有「仍然继续」（红线第 2 条）。 */
    data object FingerprintRejected : PairingStep

    data object Pairing : PairingStep

    data class Done(
        val hostName: String,
        val replaced: Boolean,
    ) : PairingStep

    data class Failed(
        val result: PairingResult,
    ) : PairingStep
}

/**
 * 扫码或手动配对。[replaceHostId] 不为 null 时是重新配对这台主机。
 *
 * 配对码只保存在这个 ViewModel 的内存中（不放进导航参数和 SavedState），配对结束或用户取消后清掉。
 */
class PairingViewModel(
    manual: Boolean,
    private val replaceHostId: String?,
    private val pairer: Pairer,
) : ViewModel() {
    private var scanMode = !manual
    private var lastForm = ManualForm()

    /** 手动配对：已经探测到指纹、等待用户核对时的配对码。 */
    private var pendingCode: String? = null

    private val _step = MutableStateFlow(initialStep())
    val step: StateFlow<PairingStep> = _step.asStateFlow()

    /** 扫到二维码。不在扫码步骤时忽略（相机会连续回调很多帧）。 */
    fun onQrScanned(text: String) {
        if (_step.value !is PairingStep.Scan) return
        val uri = PairUri.parse(text)
        if (uri == null) {
            _step.value = PairingStep.Scan(invalidQr = true)
            return
        }
        pair(PairingTarget.of(uri))
    }

    /** 从扫码切换到手动输入（例如没有相机权限、二维码扫不出来）。 */
    fun switchToManual() {
        scanMode = false
        _step.value = PairingStep.Manual(lastForm)
    }

    fun updateForm(
        host: String,
        port: String,
        code: String,
    ) {
        val current = (_step.value as? PairingStep.Manual)?.form ?: return
        val form =
            current.copy(
                host = host,
                port = port,
                code = code,
                hostInvalid = current.hostInvalid && host == current.host,
                portInvalid = current.portInvalid && port == current.port,
                codeInvalid = current.codeInvalid && code == current.code,
                probeError = current.probeError.takeIf { host == current.host && port == current.port },
            )
        lastForm = form
        _step.value = PairingStep.Manual(form)
    }

    /** 手动配对的「下一步」：检查输入，然后取得服务器证书的指纹给用户核对。 */
    fun submitManual() {
        val form = (_step.value as? PairingStep.Manual)?.form ?: return
        val host = form.host.trim()
        val port = form.port.trim()
        val address = HostAddress.of(host, port)
        val code = normalizePairingCode(form.code)
        val hostInvalid = address == null && HostAddress.of(host, ManualForm.DEFAULT_PORT) == null
        val checked =
            form.copy(
                hostInvalid = hostInvalid,
                portInvalid = address == null && !hostInvalid,
                codeInvalid = code == null,
                probeError = null,
            )
        lastForm = checked
        if (address == null || code == null) {
            _step.value = PairingStep.Manual(checked)
            return
        }
        _step.value = PairingStep.Probing(address)
        viewModelScope.launch {
            when (val result = pairer.probe(address)) {
                is PairingProbe.Result.Fingerprinted -> {
                    pendingCode = code
                    _step.value = PairingStep.ConfirmFingerprint(address, result.fingerprint)
                }
                else -> _step.value = PairingStep.Manual(checked.copy(probeError = result))
            }
        }
    }

    /** 用户确认指纹一致：用这个指纹建立连接，发送配对码。 */
    fun confirmFingerprint() {
        val step = _step.value as? PairingStep.ConfirmFingerprint ?: return
        val code = pendingCode ?: return
        pendingCode = null
        pair(PairingTarget(step.address, step.fingerprint, code))
    }

    /** 用户说指纹不一致：丢弃配对码，不发出任何请求。 */
    fun rejectFingerprint() {
        if (_step.value !is PairingStep.ConfirmFingerprint) return
        pendingCode = null
        lastForm = lastForm.copy(code = "")
        _step.value = PairingStep.FingerprintRejected
    }

    /** 失败或取消后重新开始：回到扫码，或回到手动输入（保留地址和端口，配对码要重新输入）。 */
    fun startOver() {
        pendingCode = null
        lastForm = lastForm.copy(code = "", codeInvalid = false, probeError = null)
        _step.value = if (scanMode) PairingStep.Scan() else PairingStep.Manual(lastForm)
    }

    private fun pair(target: PairingTarget) {
        _step.value = PairingStep.Pairing
        viewModelScope.launch {
            _step.value =
                when (val result = pairer.pair(target, replaceHostId)) {
                    is PairingResult.Paired -> PairingStep.Done(result.host.name, result.replaced)
                    else -> PairingStep.Failed(result)
                }
            lastForm = lastForm.copy(code = "")
        }
    }

    private fun initialStep(): PairingStep = if (scanMode) PairingStep.Scan() else PairingStep.Manual(lastForm)
}
