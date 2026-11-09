package io.github.qianyeeew.dshremote.ui.pairing

import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import io.github.qianyeeew.dshremote.data.pairing.PairingResult
import io.github.qianyeeew.dshremote.ui.FakePairer
import io.github.qianyeeew.dshremote.ui.FakePairer.Companion.FINGERPRINT
import io.github.qianyeeew.dshremote.ui.FakePairer.Companion.OTHER_FINGERPRINT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 配对流程：扫码、手动输入、指纹核对、各种失败后重新开始。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val pairer = FakePairer()
    private val address = HostAddress("192.168.1.5", 3190)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun scannedQrPairsWithItsFingerprintAndCode() {
        val vm = PairingViewModel(manual = false, replaceHostId = null, pairer = pairer)
        assertEquals(PairingStep.Scan(), vm.step.value)
        vm.onQrScanned(qr())
        val (target, replace) = pairer.pairs.single()
        assertEquals(address, target.address)
        assertEquals(FINGERPRINT, target.fingerprint)
        assertEquals(CODE, target.code)
        assertNull(replace)
        assertEquals(PairingStep.Done("MY-PC", replaced = false), vm.step.value)
        // 扫码时不需要探测指纹（二维码里就有）
        assertTrue(pairer.probes.isEmpty())
    }

    @Test
    fun invalidQrIsRejectedAndScanningContinues() {
        val vm = PairingViewModel(manual = false, replaceHostId = null, pairer = pairer)
        vm.onQrScanned("https://example.com/")
        assertEquals(PairingStep.Scan(invalidQr = true), vm.step.value)
        vm.onQrScanned(qr().replace("v=1", "v=2"))
        assertEquals(PairingStep.Scan(invalidQr = true), vm.step.value)
        assertTrue(pairer.pairs.isEmpty())
        vm.onQrScanned(qr())
        assertEquals(1, pairer.pairs.size)
    }

    @Test
    fun repeatedFramesPairOnlyOnce() {
        pairer.pairGate = CompletableDeferred()
        val vm = PairingViewModel(manual = false, replaceHostId = "h1", pairer = pairer)
        vm.onQrScanned(qr())
        assertEquals(PairingStep.Pairing, vm.step.value)
        repeat(5) { vm.onQrScanned(qr()) }
        pairer.pairGate!!.complete(Unit)
        assertEquals(1, pairer.pairs.size)
        assertEquals("h1", pairer.pairs.single().second)
    }

    @Test
    fun manualInputIsValidatedBeforeProbing() {
        val vm = PairingViewModel(manual = true, replaceHostId = null, pairer = pairer)
        assertEquals("3190", (vm.step.value as PairingStep.Manual).form.port)

        vm.updateForm("not a host", "3190", "123")
        vm.submitManual()
        var form = (vm.step.value as PairingStep.Manual).form
        assertTrue(form.hostInvalid)
        assertFalse(form.portInvalid)
        assertTrue(form.codeInvalid)

        vm.updateForm("192.168.1.5", "99999", CODE)
        vm.submitManual()
        form = (vm.step.value as PairingStep.Manual).form
        assertFalse(form.hostInvalid)
        assertTrue(form.portInvalid)
        assertFalse(form.codeInvalid)

        assertTrue(pairer.probes.isEmpty())
    }

    @Test
    fun manualPairingShowsFingerprintAndSendsCodeOnlyAfterConfirmation() {
        val vm = PairingViewModel(manual = true, replaceHostId = null, pairer = pairer)
        vm.updateForm(" 192.168.1.5 ", "3190", "k7m2-q9xp")
        vm.submitManual()
        assertEquals(listOf(address), pairer.probes)
        assertEquals(PairingStep.ConfirmFingerprint(address, FINGERPRINT), vm.step.value)
        assertTrue("核对之前不能发送配对码", pairer.pairs.isEmpty())

        vm.confirmFingerprint()
        val target = pairer.pairs.single().first
        assertEquals(FINGERPRINT, target.fingerprint)
        assertEquals(CODE, target.code)
        assertEquals(address, target.address)
        assertEquals(PairingStep.Done("MY-PC", replaced = false), vm.step.value)
    }

    @Test
    fun rejectedFingerprintNeverSendsCode() {
        pairer.probeResult = PairingProbe.Result.Fingerprinted(OTHER_FINGERPRINT)
        val vm = PairingViewModel(manual = true, replaceHostId = null, pairer = pairer)
        vm.updateForm("192.168.1.5", "3190", CODE)
        vm.submitManual()
        vm.rejectFingerprint()
        assertEquals(PairingStep.FingerprintRejected, vm.step.value)
        // 之后再「确认」也没有用：配对码已经丢弃
        vm.confirmFingerprint()
        assertTrue(pairer.pairs.isEmpty())

        vm.startOver()
        val form = (vm.step.value as PairingStep.Manual).form
        assertEquals("192.168.1.5", form.host)
        assertEquals("", form.code)
    }

    @Test
    fun probeFailureReturnsToFormWithReason() {
        for (result in listOf(PairingProbe.Result.Unreachable, PairingProbe.Result.Timeout, PairingProbe.Result.NotTls)) {
            pairer.probeResult = result
            val vm = PairingViewModel(manual = true, replaceHostId = null, pairer = pairer)
            vm.updateForm("192.168.1.5", "3190", CODE)
            vm.submitManual()
            val form = (vm.step.value as PairingStep.Manual).form
            assertEquals(result, form.probeError)
            // 改了地址后，旧的错误提示消失
            vm.updateForm("192.168.1.6", "3190", CODE)
            assertNull((vm.step.value as PairingStep.Manual).form.probeError)
        }
        assertTrue(pairer.pairs.isEmpty())
    }

    @Test
    fun failuresAreShownAndStartOverReturnsToTheSameMode() {
        pairer.pairResult = PairingResult.FingerprintMismatch
        val scan = PairingViewModel(manual = false, replaceHostId = null, pairer = pairer)
        scan.onQrScanned(qr())
        assertEquals(PairingStep.Failed(PairingResult.FingerprintMismatch), scan.step.value)
        scan.startOver()
        assertEquals(PairingStep.Scan(), scan.step.value)

        pairer.pairResult = PairingResult.CodeInvalid
        val manual = PairingViewModel(manual = true, replaceHostId = null, pairer = pairer)
        manual.updateForm("192.168.1.5", "3190", CODE)
        manual.submitManual()
        manual.confirmFingerprint()
        assertEquals(PairingStep.Failed(PairingResult.CodeInvalid), manual.step.value)
        manual.startOver()
        val form = (manual.step.value as PairingStep.Manual).form
        assertEquals("192.168.1.5", form.host)
        assertEquals("配对码要重新输入", "", form.code)
    }

    @Test
    fun switchingToManualStaysManual() {
        pairer.pairResult = PairingResult.Unreachable
        val vm = PairingViewModel(manual = false, replaceHostId = null, pairer = pairer)
        vm.switchToManual()
        assertTrue(vm.step.value is PairingStep.Manual)
        vm.updateForm("192.168.1.5", "3190", CODE)
        vm.submitManual()
        vm.confirmFingerprint()
        vm.startOver()
        assertTrue(vm.step.value is PairingStep.Manual)
        // 不在扫码步骤时，相机的回调被忽略
        vm.onQrScanned(qr())
        assertEquals(1, pairer.pairs.size)
    }

    @Test
    fun formDoesNotPrintCode() {
        assertFalse(ManualForm(code = CODE).toString().contains(CODE))
    }

    private fun qr() = "dshremote://pair?v=1&h=192.168.1.5&p=3190&fp=${FINGERPRINT.value}&c=$CODE&n=MY-PC"

    private companion object {
        const val CODE = "K7M2Q9XP"
    }
}
