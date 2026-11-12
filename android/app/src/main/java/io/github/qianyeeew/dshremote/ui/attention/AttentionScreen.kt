package io.github.qianyeeew.dshremote.ui.attention

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.LiveEvent
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.DesignBackBar
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.hosts.needsAttention
import io.github.qianyeeew.dshremote.ui.hosts.statusDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 独立的处理页面的内容。 */
data class AttentionPageState(
    val link: LinkStatus = LinkStatus.Stopped,
    /** 这个事项（还在待处理时是最新的内容，已经结束时是最后一次看到的内容）。没见过时为 null。 */
    val attention: Attention? = null,
    /** 还在待处理。 */
    val pending: Boolean = false,
    /** 事项所属的会话在会话列表中（可以打开）。子代理发起的事项不在。 */
    val sessionKnown: Boolean = false,
    val ui: AttentionUi = AttentionUi(),
)

/**
 * 独立的处理页面（m2-android.md 3.5）：通过 `attentionId` 打开，用于子代理发起的事项、
 * `sessionId` 不在会话列表中的事项，以及从通知直接进入（T11）。
 */
class AttentionViewModel(
    hostId: String,
    private val attentionId: String,
    states: Flow<Map<String, HostLiveState>>,
    responder: AttentionResponder,
    resolvedEvents: Flow<LiveEvent.AttentionResolved>,
) : ViewModel() {
    val handler = AttentionHandler(viewModelScope, responder, resolvedEvents).also { it.shown(listOf(attentionId)) }

    /** 最后一次看到的内容（combine 的回调按顺序执行，不会并发）。 */
    private var lastSeen: Attention? = null

    val state: StateFlow<AttentionPageState> =
        combine(states, handler.ui) { live, ui ->
            val host = live[hostId]
            val current = host?.attention?.get(attentionId)
            if (current != null) lastSeen = current
            val attention = current ?: lastSeen
            AttentionPageState(
                link = host?.status ?: LinkStatus.Stopped,
                attention = attention,
                pending = current != null && ui[attentionId]?.resolution == null,
                sessionKnown = attention != null && host?.sessions?.containsKey(attention.sessionId) == true,
                ui = ui[attentionId] ?: AttentionUi(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AttentionPageState())
}

@Composable
fun AttentionScreen(
    viewModel: AttentionViewModel,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.handler.notice.collectAsStateWithLifecycle()
    // 在这个页面上看到了结果，不需要再弹提示
    LaunchedEffect(notice) { if (notice != null) viewModel.handler.dismissNotice() }
    AttentionPageContent(state, AttentionCardActions.of(viewModel.handler), onBack, onOpenSession)
}

/** 独立的处理页（设计规格第 9 节）：只有返回键，内容是 7.4、7.5 的同一个面板。 */
@Composable
fun AttentionPageContent(
    state: AttentionPageState,
    actions: AttentionCardActions,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
) {
    Scaffold(topBar = { DesignBackBar(onBack) }, containerColor = DesignTheme.colors.page) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val attention = state.attention
            val resolution = state.ui.resolution
            when {
                resolution != null -> Message(resolutionText(resolution))
                state.pending && attention != null -> AttentionCard(attention, state.ui, actions)
                // 看到过，但已经不在待处理列表中了（例如重连后的 hello 里没有它）
                attention != null -> Message(stringResource(R.string.attention_done_elsewhere))
                state.link is LinkStatus.Online -> Message(stringResource(R.string.attention_not_found))
                else -> {
                    Text(
                        statusDetail(state.link),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.link.needsAttention) DesignTheme.colors.danger else DesignTheme.colors.textSecondary,
                    )
                    Message(stringResource(R.string.attention_waiting_connection))
                }
            }
            if (attention != null && state.sessionKnown) {
                PillButton(stringResource(R.string.attention_open_session), { onOpenSession(attention.sessionId) })
            }
        }
    }
}

@Composable
private fun resolutionText(resolution: Resolution): String =
    stringResource(
        when (resolution) {
            Resolution.ANSWERED_HERE -> R.string.attention_done_here
            Resolution.ELSEWHERE -> R.string.attention_done_elsewhere
            Resolution.GONE -> R.string.attention_done_gone
        },
    )

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = DesignTheme.colors.text, modifier = Modifier.padding(horizontal = 4.dp))
}
