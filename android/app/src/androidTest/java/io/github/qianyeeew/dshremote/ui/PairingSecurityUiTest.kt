package io.github.qianyeeew.dshremote.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.host.Credentials
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.data.live.PairingReason
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import io.github.qianyeeew.dshremote.data.pairing.PairingResult
import io.github.qianyeeew.dshremote.ui.hosts.HostDetail
import io.github.qianyeeew.dshremote.ui.hosts.HostDetailContent
import io.github.qianyeeew.dshremote.ui.pairing.PairingActions
import io.github.qianyeeew.dshremote.ui.pairing.PairingContent
import io.github.qianyeeew.dshremote.ui.pairing.PairingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面上的安全要求（m2-android.md 第 4 节）：证书指纹不一致时，界面上**没有**「继续」之类的选项（红线第 2 条），
 * 能点的只有重新开始、重新配对和返回。手动配对时用大号字体显示指纹的前 8 位（protocol.md 2.3）。
 */
@RunWith(AndroidJUnit4::class)
class PairingSecurityUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fingerprint = Fingerprint.parse("Oaj69GfnJH_P-CmHX0B6Vxe-YBlVHoveBj7rSEae14M")!!
    private val address = HostAddress("192.168.1.5", 3190)

    @Test
    fun hostWithMismatchedFingerprintOffersOnlyRepairing() {
        showHost(LinkStatus.FingerprintMismatch)
        assertNoBypassWording()
        // 状态卡片上唯一能点的是「重新配对」，没有「立即重试」
        assertEquals(listOf(text(R.string.host_repair)), clickableLabels(hasAnyAncestor(hasTestTag("status-card"))))
        compose.onNodeWithText(text(R.string.status_fingerprint_mismatch)).assertExists()
    }

    @Test
    fun otherStatusesOfferTheRightAction() {
        showHost(LinkStatus.NeedsPairing(PairingReason.TOKEN_REVOKED))
        assertEquals(listOf(text(R.string.host_repair)), clickableLabels(hasAnyAncestor(hasTestTag("status-card"))))
    }

    @Test
    fun offlineHostOffersRetry() {
        showHost(LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000))
        assertEquals(listOf(text(R.string.host_retry)), clickableLabels(hasAnyAncestor(hasTestTag("status-card"))))
    }

    @Test
    fun pairingRejectedByFingerprintOffersOnlyStartingOver() {
        showPairing(PairingStep.Failed(PairingResult.FingerprintMismatch))
        assertNoBypassWording()
        assertEquals(
            setOf(text(R.string.pair_start_over), text(R.string.action_back)),
            clickableLabels().toSet(),
        )
    }

    @Test
    fun userRejectedFingerprintOffersOnlyReenteringOrLeaving() {
        showPairing(PairingStep.FingerprintRejected)
        assertNoBypassWording()
        assertEquals(setOf(text(R.string.pair_reenter), text(R.string.action_back)), clickableLabels().toSet())
    }

    @Test
    fun confirmationShowsFirstEightCharactersAndBothChoicesWork() {
        var confirmed = 0
        var rejected = 0
        showPairing(
            PairingStep.ConfirmFingerprint(address, fingerprint),
            PairingActions(onConfirmFingerprint = { confirmed++ }, onRejectFingerprint = { rejected++ }),
        )
        compose.onNodeWithTag("fingerprint-prefix").assertExists()
        val prefix =
            compose
                .onNodeWithTag("fingerprint-prefix")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString()
        assertEquals("Oaj69Gfn", prefix)

        compose.onNodeWithText(text(R.string.pair_confirm_mismatch)).performClick()
        compose.onNodeWithText(text(R.string.pair_confirm_match)).performClick()
        assertEquals(1, rejected)
        assertEquals(1, confirmed)
    }

    private fun showHost(status: LinkStatus) {
        val host =
            Host(
                hostId = "h1",
                name = "MY-PC",
                addresses = listOf(address),
                lastGoodAddress = address,
                deviceId = "d_1",
                credentials = Credentials(fingerprint, DeviceToken.parse("tok3n_SECRET-abcdefghijklmnopqrstuvwxyz0120")!!),
            )
        compose.setContent {
            DshRemoteTheme {
                HostDetailContent(
                    detail = HostDetail(host, status),
                    removing = false,
                    onBack = {},
                    onRename = { true },
                    onSaveAddress = { _, _ -> null },
                    onRemoveAddress = {},
                    onRetry = {},
                    onRepair = {},
                    onRemove = {},
                )
            }
        }
    }

    private fun showPairing(
        step: PairingStep,
        actions: PairingActions = PairingActions(),
    ) {
        compose.setContent { DshRemoteTheme { PairingContent(step, replacing = false, actions = actions) } }
    }

    /** 整个界面上没有任何「继续」「仍然」「忽略」「信任」之类的字样。 */
    private fun assertNoBypassWording() {
        val all =
            compose
                .onAllNodes(SemanticsMatcher("任何节点") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text } +
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }
        assertTrue("界面上没有任何文字，测试本身有问题", all.isNotEmpty())
        for (word in listOf("继续", "仍然", "忽略", "信任")) {
            assertTrue("界面上出现了「$word」：$all", all.none { word in it })
        }
    }

    /** 能点击的元素的文字（按钮的文字或图标的说明）。 */
    private fun clickableLabels(scope: SemanticsMatcher? = null): List<String> {
        val matcher = if (scope == null) hasClickAction() else hasClickAction() and scope
        return compose.onAllNodes(matcher).fetchSemanticsNodes().map { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString { it.text }
                ?: node.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .joinToString()
        }
    }

    private fun text(id: Int): String = context.getString(id)
}
