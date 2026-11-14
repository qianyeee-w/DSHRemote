package io.github.qianyeeew.dshremote.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.BlockButton
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.designPanel

// ---------------------------------------------------------------------------
// 对话分支（设计规格 7.13，落地方案 C1）：长按自己发的一条消息，底部面板里「复制」和「另开对话」；
// 选「另开对话」后这条消息加描边、其余内容变淡，底部是确认面板。不用贴着消息的浮动菜单。
// ---------------------------------------------------------------------------

/**
 * 长按一条消息后的底部面板：「复制」，能另开时还有「另开对话」。不在线时「另开对话」不能点并说明原因；
 * 还没进会话的本地消息、推断不出轮次的消息只能复制。
 */
@Composable
fun MessageActionsPanel(
    canFork: Boolean,
    online: Boolean,
    onCopy: () -> Unit,
    onFork: () -> Unit,
) {
    val colors = DesignTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .designPanel(RoundedCornerShape(22.dp))
            .padding(vertical = 6.dp)
            .testTag("message-actions"),
    ) {
        ActionRow(stringResource(R.string.chat_action_copy), enabled = true, tag = "message-copy", onClick = onCopy)
        if (canFork) {
            DesignDivider(Modifier.padding(horizontal = 16.dp))
            ActionRow(
                stringResource(R.string.fork_action),
                enabled = online,
                tag = "message-fork",
                note = if (online) null else stringResource(R.string.fork_offline),
                onClick = onFork,
            )
        }
    }
}

@Composable
private fun ActionRow(
    text: String,
    enabled: Boolean,
    tag: String,
    note: String? = null,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            color = if (enabled) colors.text else colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
    }
}

/**
 * 确认面板：标题、一段说明、一句「电脑上的文件不会变，也不会新建 Git 分支」、取消和「另开对话」。
 * 进行中按钮不能点；失败时说明，按钮变成「重试」（沿用同一个 requestId，不会分出两个）。
 */
@Composable
fun ForkConfirmPanel(
    state: ForkState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = DesignTheme.colors
    val running = state is ForkState.Running
    val failed = state is ForkState.Failed
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .designPanel(RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("fork-confirm"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.fork_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
        Text(stringResource(R.string.fork_body), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Text(stringResource(R.string.fork_files), style = MaterialTheme.typography.bodySmall, color = colors.text)
        if (failed) {
            Text(
                stringResource(R.string.fork_failed),
                style = MaterialTheme.typography.labelMedium,
                color = colors.danger,
                modifier = Modifier.testTag("fork-failed"),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BlockButton(stringResource(R.string.action_cancel), onCancel, Modifier.weight(1f).testTag("fork-cancel"), enabled = !running)
            BlockButton(
                stringResource(
                    when {
                        running -> R.string.fork_running
                        failed -> R.string.fork_retry
                        else -> R.string.fork_action
                    },
                ),
                onConfirm,
                Modifier.weight(1f).testTag("fork-confirm-button"),
                primary = true,
                enabled = !running,
            )
        }
    }
}
