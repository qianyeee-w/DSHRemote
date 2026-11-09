package io.github.qianyeeew.dshremote.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.net.Fingerprint

/** 带返回按钮的标题栏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
        },
    )
}

/** 按 4 个字符一组分开，和插件配对页的显示一致（plugin/src/admin/page.js 的 groupFingerprint）。 */
fun groupFingerprint(fingerprint: Fingerprint): String = fingerprint.value.chunked(FINGERPRINT_GROUP).joinToString(" ")

/** 指纹的前 8 位：手动配对时请用户和电脑配对页上加粗的部分核对（protocol.md 2.3）。 */
fun fingerprintPrefix(fingerprint: Fingerprint): String = fingerprint.value.take(FINGERPRINT_PREFIX)

const val FINGERPRINT_PREFIX = 8
private const val FINGERPRINT_GROUP = 4

/** 等宽字体显示指纹，前 8 位加粗（和配对页一致）。 */
@Composable
fun FingerprintText(fingerprint: Fingerprint) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(fingerprintPrefix(fingerprint)) }
            append(fingerprint.value.drop(FINGERPRINT_PREFIX))
        },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** 只有一个「知道了」按钮的提示框。 */
@Composable
fun MessageDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        title = { Text(title) },
        text = { Column { Text(body) } },
    )
}
