package io.github.qianyeeew.dshremote.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

/** 首页（没有主机或有多台主机时）：已配对的电脑和在线状态。[rows] 为 null 表示还在读取。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListContent(
    rows: List<HostRow>?,
    versionName: String,
    onOpenHost: (hostId: String) -> Unit,
    onPair: (manual: Boolean) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.hosts_title)) }) },
        floatingActionButton = {
            if (!rows.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { choosing = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.hosts_add)) },
                )
            }
        },
    ) { padding ->
        when {
            rows == null -> Unit
            rows.isEmpty() -> EmptyHosts(versionName, onPair, Modifier.padding(padding))
            else ->
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(padding),
                ) {
                    items(rows, key = { it.hostId }) { row -> HostCard(row) { onOpenHost(row.hostId) } }
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
private fun HostCard(
    row: HostRow,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.address?.let {
                    Text(
                        it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusBadge(row.status, Modifier.padding(start = 12.dp))
        }
    }
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
