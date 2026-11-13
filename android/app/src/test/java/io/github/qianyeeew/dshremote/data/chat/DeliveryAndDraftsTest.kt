package io.github.qianyeeew.dshremote.data.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.live.OfflineException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException

/** 落地方案 B3：发送失败怎么归类、草稿怎么存。 */
class DeliveryAndDraftsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyAnUnansweredRequestIsUncertain() {
        assertEquals(OutgoingStatus.UNCERTAIN, deliveryStatus(ApiException.Network(IOException("timeout"), maybeDelivered = true)))
        assertEquals(OutgoingStatus.UNCERTAIN, deliveryStatus(ApiException.BadResponse("Accepted")))
        // 连不上、插件明确拒绝、指纹不对、根本没有连接：肯定没送到
        assertEquals(OutgoingStatus.FAILED, deliveryStatus(ApiException.Network(IOException("refused"), maybeDelivered = false)))
        assertEquals(OutgoingStatus.FAILED, deliveryStatus(ApiException.Http(503, ErrorCode.of("DSH_UNAVAILABLE"), null)))
        assertEquals(OutgoingStatus.FAILED, deliveryStatus(ApiException.FingerprintMismatch(SSLPeerUnverifiedException("x"))))
        assertEquals(OutgoingStatus.FAILED, deliveryStatus(OfflineException()))
    }

    @Test
    fun draftsArePerSessionBlankDeletesAndPruneKeepsOnlyExistingSessions() =
        runTest {
            val drafts =
                DraftStore(
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                    ) { folder.newFile("drafts.preferences_pb").also { it.delete() } },
                )
            assertEquals(emptyMap<String, String>(), drafts.drafts("h1").first())
            drafts.save("h1", "s1", "第一句")
            drafts.save("h1", "s2", "第二句")
            drafts.save("h2", "s1", "另一台电脑")
            assertEquals(mapOf("s1" to "第一句", "s2" to "第二句"), drafts.drafts("h1").first())
            drafts.save("h1", "s1", "   ")
            assertEquals(mapOf("s2" to "第二句"), drafts.drafts("h1").first())
            drafts.prune("h1", setOf("s1"))
            assertEquals(emptyMap<String, String>(), drafts.drafts("h1").first())
            assertEquals(mapOf("s1" to "另一台电脑"), drafts.drafts("h2").first())
        }
}
