package io.github.qianyeeew.dshremote.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.ui.CodeTextStyles
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.BlockButton
import io.github.qianyeeew.dshremote.ui.common.DesignBackBar
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.FingerprintText
import io.github.qianyeeew.dshremote.ui.common.MessageDialog
import io.github.qianyeeew.dshremote.ui.common.RowChevron

/** 主机管理页面，从会话列表的菜单进入。 */
@Composable
fun HostDetailScreen(
    viewModel: HostDetailViewModel,
    onBack: () -> Unit,
    onRepair: (manual: Boolean) -> Unit,
    /** 这台是首页正在用的电脑。 */
    currentHost: Boolean = false,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val removal by viewModel.removal.collectAsStateWithLifecycle()
    var seen by remember { mutableStateOf(false) }

    LaunchedEffect(detail, removal) {
        if (detail != null) seen = true
        // 主机被删除了（在这里删除并已在电脑上撤销，或者在别处被删除）：回到列表
        val done = removal as? Removal.Done
        if ((done?.revokedOnComputer == true) || (seen && detail == null && removal == Removal.None)) onBack()
    }

    val current = detail
    if (current == null) {
        if (removal is Removal.Done) {
            MessageDialog(
                title = stringResource(R.string.host_removed_not_revoked_title),
                body = stringResource(R.string.host_removed_not_revoked_body),
                onDismiss = onBack,
            )
        }
        return
    }
    HostDetailContent(
        detail = current,
        removing = removal == Removal.InProgress,
        onBack = onBack,
        onRename = viewModel::rename,
        onSaveAddress = viewModel::saveAddress,
        onRemoveAddress = viewModel::removeAddress,
        onRetry = viewModel::retryNow,
        onRepair = onRepair,
        onRemove = viewModel::remove,
        current = currentHost,
    )
}

@Composable
fun HostDetailContent(
    detail: HostDetail,
    removing: Boolean,
    onBack: () -> Unit,
    onRename: (String) -> Boolean,
    onSaveAddress: (index: Int?, text: String) -> AddressError?,
    onRemoveAddress: (Int) -> Unit,
    onRetry: () -> Unit,
    onRepair: (manual: Boolean) -> Unit,
    onRemove: () -> Unit,
    /** 这台是首页正在用的电脑。 */
    current: Boolean = false,
) {
    val host = detail.host
    val status = detail.status
    val colors = DesignTheme.colors
    var renaming by remember { mutableStateOf(false) }
    // null：没有在编辑；-1：添加；其他：修改第几个
    var editingAddress by remember { mutableStateOf<Int?>(null) }
    var choosingRepair by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }
    var showingFingerprint by remember { mutableStateOf(false) }

    Scaffold(topBar = { DesignBackBar(onBack) }, containerColor = colors.page) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        ) {
            Text(host.name, style = MaterialTheme.typography.titleLarge, color = colors.text)
            val line = lineStatus(status, current)
            Text(
                stringResource(line.text),
                style = MaterialTheme.typography.bodySmall,
                color = line.tone.color(),
                modifier = Modifier.padding(top = 2.dp).testTag("host-status"),
            )
            ProblemSection(status, onRetry = onRetry, onRepair = { choosingRepair = true })

            Column(Modifier.padding(top = 32.dp)) {
                InfoRow(stringResource(R.string.host_info_name), onClick = { renaming = true }, tag = "host-rename") {
                    Text(
                        host.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                host.addresses.forEachIndexed { index, address ->
                    InfoRow(
                        if (index == 0) stringResource(R.string.host_info_address) else "",
                        onClick = { editingAddress = index },
                        clickLabel = stringResource(R.string.host_address_edit, address.toString()),
                        tag = "host-address:$index",
                    ) {
                        Text(
                            address.toString(),
                            style = CodeTextStyles.inline,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (host.addresses.size < HostRepository.MAX_ADDRESSES) {
                    InfoRow("", onClick = { editingAddress = -1 }, tag = "host-address-add") {
                        Text(
                            stringResource(R.string.host_info_add_address),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
                InfoRow(
                    stringResource(R.string.host_info_fingerprint),
                    onClick = if (host.credentials != null) ({ showingFingerprint = true }) else null,
                    tag = "host-fingerprint",
                ) {
                    val fingerprint = host.credentials?.fingerprint
                    if (fingerprint != null) {
                        Text(
                            fingerprint.value,
                            style = CodeTextStyles.inline,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            stringResource(R.string.host_fingerprint_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.danger,
                        )
                    }
                }
                DesignDivider()
            }
            Text(
                stringResource(if (removing) R.string.host_removing else R.string.host_remove_link),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.danger,
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !removing, role = Role.Button) { confirmingRemove = true }
                        .padding(vertical = 12.dp)
                        .testTag("host-remove"),
            )
        }
    }

    if (renaming) {
        val invalid = stringResource(R.string.host_rename_invalid)
        TextInputDialog(
            title = stringResource(R.string.host_rename_title),
            label = stringResource(R.string.host_section_name),
            initial = host.name,
            onDismiss = { renaming = false },
            onSave = { text ->
                if (onRename(text)) {
                    renaming = false
                    null
                } else {
                    invalid
                }
            },
        )
    }
    editingAddress?.let { index ->
        val adding = index < 0
        val errors =
            mapOf(
                AddressError.INVALID to stringResource(R.string.host_address_invalid),
                AddressError.DUPLICATE to stringResource(R.string.host_address_duplicate),
                AddressError.TOO_MANY to stringResource(R.string.host_address_too_many, HostRepository.MAX_ADDRESSES),
            )
        TextInputDialog(
            title = stringResource(if (adding) R.string.host_address_add_title else R.string.host_address_edit_title),
            label = stringResource(R.string.host_address_label),
            supporting = stringResource(R.string.host_address_example),
            initial =
                if (adding) {
                    ""
                } else {
                    host.addresses
                        .getOrNull(index)
                        ?.toString()
                        .orEmpty()
                },
            keyboardType = KeyboardType.Uri,
            onDismiss = { editingAddress = null },
            onSave = { text ->
                val error = onSaveAddress(index.takeIf { !adding }, text)
                if (error == null) editingAddress = null
                error?.let(errors::getValue)
            },
            // 至少保留一个地址
            onDelete =
                if (!adding && host.addresses.size > 1) {
                    {
                        editingAddress = null
                        onRemoveAddress(index)
                    }
                } else {
                    null
                },
        )
    }
    if (showingFingerprint) {
        host.credentials?.fingerprint?.let { fingerprint ->
            AlertDialog(
                onDismissRequest = { showingFingerprint = false },
                title = { Text(stringResource(R.string.host_info_fingerprint)) },
                text = { FingerprintText(fingerprint) },
                confirmButton = { TextButton(onClick = { showingFingerprint = false }) { Text(stringResource(R.string.action_done)) } },
            )
        }
    }
    if (choosingRepair) {
        PairMethodDialog(
            title = stringResource(R.string.repair_method_title),
            onChoose = { manual ->
                choosingRepair = false
                onRepair(manual)
            },
            onDismiss = { choosingRepair = false },
        )
    }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(R.string.host_remove_title, host.name)) },
            text = { Text(stringResource(R.string.host_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    onRemove()
                }) { Text(stringResource(R.string.action_delete), color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 有问题时：一段话说明出了什么事、为什么这样处理，再给一个主按钮（设计规格 7.9）。正常时没有这一段。
 * 证书指纹不匹配时只有「重新配对」，没有任何继续连接的入口（红线第 2 条）。
 */
@Composable
private fun ProblemSection(
    status: LinkStatus,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    if (status is LinkStatus.Online || status == LinkStatus.Connecting || status == LinkStatus.Stopped) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("status-card")) {
        Text(statusDetail(status), style = MaterialTheme.typography.bodyLarge, color = DesignTheme.colors.text)
        when {
            status.offersRepair ->
                BlockButton(
                    stringResource(R.string.host_repair),
                    onRepair,
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    primary = true,
                    tall = true,
                )
            status.offersRetry ->
                BlockButton(stringResource(R.string.host_retry), onRetry, Modifier.fillMaxWidth().padding(top = 16.dp), tall = true)
        }
    }
}

/** 一行信息：左边宽 72 的灰色标签，右边是内容；能点时右边一个小箭头。高 52，上面一条分隔线。 */
@Composable
private fun InfoRow(
    label: String,
    onClick: (() -> Unit)?,
    tag: String,
    clickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Column {
        DesignDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .let { if (onClick != null) it.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else it }
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = DesignTheme.colors.textSecondary,
                modifier = Modifier.width(72.dp),
            )
            Box(Modifier.weight(1f)) { content() }
            if (onClick != null) RowChevron()
        }
    }
}

/** 单行输入框的对话框。[onSave] 返回错误信息，返回 null 表示已保存。 */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDelete: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                label = { Text(label) },
                singleLine = true,
                isError = error != null,
                supportingText = (error ?: supporting)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.host_address_delete), color = DesignTheme.colors.danger) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
