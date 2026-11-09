package io.github.qianyeeew.dshremote.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.ui.common.BackTopBar
import io.github.qianyeeew.dshremote.ui.common.FingerprintText
import io.github.qianyeeew.dshremote.ui.common.MessageDialog

/** 主机管理页面，从会话列表的菜单进入。 */
@Composable
fun HostDetailScreen(
    viewModel: HostDetailViewModel,
    onBack: () -> Unit,
    onRepair: (manual: Boolean) -> Unit,
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
) {
    val host = detail.host
    var renaming by remember { mutableStateOf(false) }
    // null：没有在编辑；-1：添加；其他：修改第几个
    var editingAddress by remember { mutableStateOf<Int?>(null) }
    var choosingRepair by remember { mutableStateOf(false) }
    var confirmingRemove by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar(host.name, onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StatusCard(detail, onRetry = onRetry, onRepair = { choosingRepair = true })

            Section(stringResource(R.string.host_section_name)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(host.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.host_rename)) }
                }
            }

            Section(stringResource(R.string.host_section_addresses)) {
                host.addresses.forEachIndexed { index, address ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(address.toString(), style = MaterialTheme.typography.bodyLarge)
                            if (address == host.lastGoodAddress) {
                                Text(
                                    stringResource(R.string.host_address_last_good),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { editingAddress = index }) {
                            Icon(
                                painterResource(R.drawable.ic_edit),
                                contentDescription = stringResource(R.string.host_address_edit, address.toString()),
                            )
                        }
                        IconButton(onClick = { onRemoveAddress(index) }, enabled = host.addresses.size > 1) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.host_address_remove, address.toString()),
                            )
                        }
                    }
                }
                if (host.addresses.size < HostRepository.MAX_ADDRESSES) {
                    TextButton(onClick = { editingAddress = -1 }) { Text(stringResource(R.string.host_address_add)) }
                }
                Text(
                    stringResource(R.string.host_addresses_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(R.string.host_section_fingerprint)) {
                val fingerprint = host.credentials?.fingerprint
                if (fingerprint != null) {
                    FingerprintText(fingerprint)
                } else {
                    Text(stringResource(R.string.host_fingerprint_unavailable), color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = { choosingRepair = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.host_repair))
            }
            Button(
                onClick = { confirmingRemove = true },
                enabled = !removing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(if (removing) R.string.host_removing else R.string.host_remove))
            }
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
        )
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
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 连接状态和对应的操作。指纹不一致时只有「重新配对」，没有任何继续连接的选项（红线第 2 条）。
 */
@Composable
fun StatusCard(
    detail: HostDetail,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
) {
    val status = detail.status
    Card(Modifier.fillMaxWidth().testTag("status-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusBadge(status)
            Text(statusDetail(status), style = MaterialTheme.typography.bodyMedium)
            if (status.offersRepair) {
                Button(onClick = onRepair) { Text(stringResource(R.string.host_repair)) }
            } else if (status.offersRetry) {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.host_retry)) }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
