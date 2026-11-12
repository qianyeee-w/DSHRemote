package io.github.qianyeeew.dshremote.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.BlockButton

/**
 * 还没配对电脑时的首页（设计规格第 9 节「空状态」，没有出稿，按第 3 到 6 节的规则做）：一句话说明，
 * 主按钮「扫码配对」，下面「扫不了，手动输入」。
 */
@Composable
fun NoHostsContent(
    versionName: String,
    onPair: (manual: Boolean) -> Unit,
) {
    val colors = DesignTheme.colors
    Scaffold(containerColor = colors.page) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.no_hosts_title), style = MaterialTheme.typography.titleLarge, color = colors.text)
                Text(stringResource(R.string.no_hosts_body), style = MaterialTheme.typography.bodyLarge, color = colors.text)
                BlockButton(
                    stringResource(R.string.pair_method_scan),
                    { onPair(false) },
                    Modifier.fillMaxWidth().padding(top = 8.dp).testTag("no-hosts-scan"),
                    primary = true,
                    tall = true,
                )
                Text(
                    stringResource(R.string.pair_scan_switch_manual),
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    color = colors.textSecondary,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { onPair(true) }
                            .padding(vertical = 12.dp)
                            .testTag("no-hosts-manual"),
                )
                Text(
                    stringResource(R.string.hosts_version, versionName),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BlockButton(stringResource(R.string.pair_method_scan), { onChoose(false) }, Modifier.fillMaxWidth(), primary = true)
                BlockButton(stringResource(R.string.pair_method_manual), { onChoose(true) }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
