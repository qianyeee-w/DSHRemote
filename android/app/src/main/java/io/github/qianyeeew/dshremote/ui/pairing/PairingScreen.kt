package io.github.qianyeeew.dshremote.ui.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.net.PairingProbe
import io.github.qianyeeew.dshremote.data.pairing.PairingResult
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.common.BlockButton
import io.github.qianyeeew.dshremote.ui.common.DesignBackBar
import io.github.qianyeeew.dshremote.ui.common.PillButton
import io.github.qianyeeew.dshremote.ui.common.fingerprintPrefix
import io.github.qianyeeew.dshremote.ui.common.groupFingerprint

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    replacing: Boolean,
    onClose: () -> Unit,
    /** 配对成功（不是从「重新配对」进来的）：首页切到这台电脑。 */
    onPaired: (hostId: String) -> Unit = {},
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    LaunchedEffect(step) {
        val done = step as? PairingStep.Done ?: return@LaunchedEffect
        if (!replacing && done.hostId.isNotEmpty()) onPaired(done.hostId)
    }
    PairingContent(
        step = step,
        replacing = replacing,
        actions =
            PairingActions(
                onQrScanned = viewModel::onQrScanned,
                onSwitchToManual = viewModel::switchToManual,
                onFormChange = viewModel::updateForm,
                onSubmitManual = viewModel::submitManual,
                onConfirmFingerprint = viewModel::confirmFingerprint,
                onRejectFingerprint = viewModel::rejectFingerprint,
                onStartOver = viewModel::startOver,
                onClose = onClose,
            ),
    )
}

/** 配对页面上的所有操作。 */
class PairingActions(
    val onQrScanned: (String) -> Unit = {},
    val onSwitchToManual: () -> Unit = {},
    val onFormChange: (host: String, port: String, code: String) -> Unit = { _, _, _ -> },
    val onSubmitManual: () -> Unit = {},
    val onConfirmFingerprint: () -> Unit = {},
    val onRejectFingerprint: () -> Unit = {},
    val onStartOver: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@Composable
fun PairingContent(
    step: PairingStep,
    replacing: Boolean,
    actions: PairingActions,
) {
    Scaffold(topBar = { DesignBackBar(actions.onClose) }, containerColor = DesignTheme.colors.page) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 重新配对时说明是在替换这台电脑原来的配对
            if (replacing) {
                Text(
                    stringResource(R.string.repair_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = DesignTheme.colors.text,
                )
            }
            when (step) {
                is PairingStep.Scan -> ScanStep(step, actions)
                is PairingStep.Manual -> ManualStep(step.form, actions)
                is PairingStep.Probing -> Progress(stringResource(R.string.pair_probing, step.address.toString()))
                is PairingStep.ConfirmFingerprint -> ConfirmStep(step, actions)
                PairingStep.FingerprintRejected ->
                    Outcome(
                        title = stringResource(R.string.pair_rejected_title),
                        body = stringResource(R.string.pair_rejected_body),
                        primary = stringResource(R.string.pair_reenter) to actions.onStartOver,
                        secondary = stringResource(R.string.action_back) to actions.onClose,
                    )
                PairingStep.Pairing -> Progress(stringResource(R.string.pair_in_progress))
                is PairingStep.Done ->
                    Outcome(
                        title = stringResource(if (step.replaced) R.string.pair_done_replaced else R.string.pair_done, step.hostName),
                        body = null,
                        primary = stringResource(R.string.action_done) to actions.onClose,
                    )
                is PairingStep.Failed -> FailedStep(step.result, actions)
            }
        }
    }
}

@Composable
private fun ScanStep(
    step: PairingStep.Scan,
    actions: PairingActions,
) {
    val context = LocalContext.current

    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasCamera()) }
    var asked by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            asked = true
        }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    // 从系统设置回来时重新检查权限
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCamera() }

    when {
        granted && !unavailable ->
            Viewfinder {
                QrScanner(
                    onText = actions.onQrScanned,
                    onUnavailable = { unavailable = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        unavailable -> Text(stringResource(R.string.pair_camera_unavailable), color = MaterialTheme.colorScheme.error)
        !asked -> Text(stringResource(R.string.pair_camera_needed))
        else -> {
            Text(stringResource(R.string.pair_camera_denied), color = MaterialTheme.colorScheme.error)
            PillButton(stringResource(R.string.pair_camera_grant), { launcher.launch(Manifest.permission.CAMERA) }, primary = true)
            PillButton(stringResource(R.string.pair_camera_settings), {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            })
        }
    }
    Text(stringResource(R.string.pair_scan_hint), style = MaterialTheme.typography.bodyLarge, color = DesignTheme.colors.text)
    if (step.invalidQr) Text(stringResource(R.string.pair_scan_invalid), color = MaterialTheme.colorScheme.error)
    Text(
        stringResource(R.string.pair_scan_switch_manual),
        style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline),
        color = DesignTheme.colors.textSecondary,
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = actions.onSwitchToManual)
                .padding(vertical = 10.dp),
    )
}

/** 扫码的取景框（设计规格 7.10）：深色底、圆角 28、高 350，四角各一个白色的角标。 */
@Composable
private fun Viewfinder(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(350.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF16181C)),
    ) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val inset = 64.dp.toPx()
            val arm = 36.dp.toPx()
            val stroke = 2.dp.toPx()
            val r = 12.dp.toPx()
            val corners =
                listOf(
                    Offset(inset, inset) to Offset(1f, 1f),
                    Offset(size.width - inset, inset) to Offset(-1f, 1f),
                    Offset(inset, size.height - inset) to Offset(1f, -1f),
                    Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
                )
            for ((corner, dir) in corners) {
                val path =
                    Path().apply {
                        moveTo(corner.x, corner.y + dir.y * arm)
                        lineTo(corner.x, corner.y + dir.y * r)
                        quadraticTo(corner.x, corner.y, corner.x + dir.x * r, corner.y)
                        lineTo(corner.x + dir.x * arm, corner.y)
                    }
                drawPath(path, Color.White, style = Stroke(stroke))
            }
        }
    }
}

@Composable
private fun ManualStep(
    form: ManualForm,
    actions: PairingActions,
) {
    Text(stringResource(R.string.pair_manual_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = form.host,
        onValueChange = { actions.onFormChange(it, form.port, form.code) },
        label = { Text(stringResource(R.string.pair_manual_host)) },
        placeholder = { Text(stringResource(R.string.pair_manual_host_example)) },
        isError = form.hostInvalid,
        supportingText = if (form.hostInvalid) ({ Text(stringResource(R.string.pair_manual_host_invalid)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag("manual-host"),
    )
    OutlinedTextField(
        value = form.port,
        onValueChange = { actions.onFormChange(form.host, it, form.code) },
        label = { Text(stringResource(R.string.pair_manual_port)) },
        isError = form.portInvalid,
        supportingText = if (form.portInvalid) ({ Text(stringResource(R.string.pair_manual_port_invalid)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("manual-port"),
    )
    OutlinedTextField(
        value = form.code,
        onValueChange = { actions.onFormChange(form.host, form.port, it) },
        label = { Text(stringResource(R.string.pair_manual_code)) },
        placeholder = { Text(stringResource(R.string.pair_manual_code_example)) },
        isError = form.codeInvalid,
        supportingText = if (form.codeInvalid) ({ Text(stringResource(R.string.pair_manual_code_invalid)) }) else null,
        singleLine = true,
        // 密码类型的键盘不会记住输入的内容（配对码是一次性的秘密）；文字照常显示，方便核对
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
            ),
        modifier = Modifier.fillMaxWidth().testTag("manual-code"),
    )
    form.probeError?.let { error ->
        Text(
            stringResource(
                when (error) {
                    PairingProbe.Result.Timeout -> R.string.pair_probe_timeout
                    PairingProbe.Result.NotTls -> R.string.pair_probe_not_tls
                    else -> R.string.pair_probe_unreachable
                },
            ),
            color = MaterialTheme.colorScheme.error,
        )
    }
    BlockButton(stringResource(R.string.pair_manual_next), actions.onSubmitManual, Modifier.fillMaxWidth(), primary = true, tall = true)
}

/** 核对指纹：大号字体显示前 8 位（protocol.md 2.3）。只有「一致」和「不一致」两个选择。 */
@Composable
private fun ConfirmStep(
    step: PairingStep.ConfirmFingerprint,
    actions: PairingActions,
) {
    Text(stringResource(R.string.pair_confirm_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.pair_confirm_body), style = MaterialTheme.typography.bodyMedium)
    Text(
        fingerprintPrefix(step.fingerprint),
        style = MaterialTheme.typography.displayMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("fingerprint-prefix"),
    )
    Text(
        stringResource(R.string.pair_confirm_full, groupFingerprint(step.fingerprint)),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(step.address.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BlockButton(
        stringResource(R.string.pair_confirm_match),
        actions.onConfirmFingerprint,
        Modifier.fillMaxWidth(),
        primary = true,
        tall = true,
    )
    BlockButton(stringResource(R.string.pair_confirm_mismatch), actions.onRejectFingerprint, Modifier.fillMaxWidth(), tall = true)
}

@Composable
private fun FailedStep(
    result: PairingResult,
    actions: PairingActions,
) {
    val body =
        when (result) {
            PairingResult.CodeInvalid -> stringResource(R.string.pair_failed_code)
            is PairingResult.Locked ->
                result.retryAfterSeconds?.let { stringResource(R.string.pair_failed_locked, it.toInt()) }
                    ?: stringResource(R.string.pair_failed_locked_unknown)
            PairingResult.DeviceLimit -> stringResource(R.string.pair_failed_device_limit)
            PairingResult.FingerprintMismatch -> stringResource(R.string.pair_failed_fingerprint)
            PairingResult.Unreachable -> stringResource(R.string.pair_failed_unreachable)
            is PairingResult.AppTooOld -> stringResource(R.string.pair_failed_app_too_old, result.protocol)
            PairingResult.Failed, is PairingResult.Paired -> stringResource(R.string.pair_failed_other)
        }
    // 指纹不一致时只能从头开始（重新扫码或重新输入地址），没有任何继续连接的选项（红线第 2 条）
    val again =
        when (result) {
            PairingResult.FingerprintMismatch -> stringResource(R.string.pair_start_over)
            else -> stringResource(R.string.action_retry)
        }
    Outcome(
        title = stringResource(R.string.pair_failed_title),
        body = body,
        primary = again to actions.onStartOver,
        secondary = stringResource(R.string.action_back) to actions.onClose,
    )
}

@Composable
private fun Outcome(
    title: String,
    body: String?,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    BlockButton(primary.first, primary.second, Modifier.fillMaxWidth(), primary = true, tall = true)
    secondary?.let { (label, onClick) ->
        BlockButton(label, onClick, Modifier.fillMaxWidth(), tall = true)
    }
}

@Composable
private fun Progress(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(text)
        }
    }
}
