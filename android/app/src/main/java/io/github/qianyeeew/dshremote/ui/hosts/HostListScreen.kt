package io.github.qianyeeew.dshremote.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.CodeFontFamily
import io.github.qianyeeew.dshremote.ui.common.DesignRow
import io.github.qianyeeew.dshremote.ui.common.DesignTopBar
import io.github.qianyeeew.dshremote.ui.common.RoundIconButton

/** 已配对电脑的分组列表；[onBack] 用于从会话首页进入管理列表。 */
@Composable
fun HostListContent(
    rows: List<HostRow>?,
    versionName: String,
    onOpenHost: (hostId: String) -> Unit,
    onPair: (manual: Boolean) -> Unit,
    onOpenSettings: () -> Unit = {},
    hint: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    var choosing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            DesignTopBar(
                title = stringResource(if (onBack == null) R.string.hosts_title else R.string.design_manage_hosts),
                navigation = { onBack?.let { RoundIconButton(R.drawable.ic_arrow_back, stringResource(R.string.action_back), it) } },
                actions = {
                    RoundIconButton(R.drawable.ic_add, stringResource(R.string.hosts_add), { choosing = true })
                    Box {
                        RoundIconButton(R.drawable.ic_more_vert, stringResource(R.string.action_more), { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) }, onClick = {
                                menu = false
                                onOpenSettings()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            rows == null -> Unit
            rows.isEmpty() -> EmptyHosts(versionName, onPair, Modifier.padding(padding))
            else ->
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
                    modifier = Modifier.padding(padding).fillMaxSize(),
                ) {
                    item(key = "intro") {
                        Text(
                            stringResource(R.string.design_hosts_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 24.dp),
                        )
                    }
                    hint?.let { item(key = "hint") { it() } }
                    item(key = "host-heading") {
                        Text(
                            stringResource(R.string.design_paired_hosts),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(rows, key = { it.hostId }) { row -> HostRowView(row) { onOpenHost(row.hostId) } }
                    item(key = "pair-actions") {
                        Text(
                            stringResource(R.string.design_connection_management),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
                        )
                        DesignRow(
                            stringResource(
                                R.string.pair_method_scan,
                            ),
                            R.drawable.ic_dsh_scan,
                            stringResource(R.string.design_scan_hint),
                            onClick = {
                                onPair(false)
                            },
                            trailing = { HostChevron() },
                        )
                        DesignRow(
                            stringResource(
                                R.string.pair_method_manual,
                            ),
                            R.drawable.ic_dsh_keyboard,
                            stringResource(R.string.design_manual_hint),
                            onClick = {
                                onPair(true)
                            },
                            trailing = { HostChevron() },
                        )
                    }
                }
        }
    }
    if (choosing) {
        PairMethodDialog(
            title = stringResource(R.string.pair_method_title),
            onChoose = { manual ->
                choosing = false
                onPair(manual)
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun HostRowView(
    row: HostRow,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_dsh_computer), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                row.address?.let {
                    Text(
                        it.toString(),
                        fontFamily = CodeFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(row.status)
            }
            HostChevron()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun HostChevron() {
    Icon(painterResource(R.drawable.ic_dsh_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EmptyHosts(
    versionName: String,
    onPair: (manual: Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.hosts_empty_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.hosts_empty_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onPair(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pair_method_scan)) }
            OutlinedButton(onClick = { onPair(true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pair_method_manual))
            }
            Text(
                stringResource(R.string.hosts_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 选择扫码还是手动输入。 */
@Composable
fun PairMethodDialog(
    title: String,
    onChoose: (manual: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onChoose(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pair_method_scan)) }
                OutlinedButton(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pair_method_manual))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
