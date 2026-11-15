package io.github.qianyeeew.dshremote.ui.sessions

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.chat.AddImageButton
import io.github.qianyeeew.dshremote.ui.chat.ComposerImageStrip
import io.github.qianyeeew.dshremote.ui.chat.contextRing
import io.github.qianyeeew.dshremote.ui.chat.noticeText
import io.github.qianyeeew.dshremote.ui.chat.permissionHint
import io.github.qianyeeew.dshremote.ui.chat.permissionLabel
import io.github.qianyeeew.dshremote.ui.common.ChoiceRow
import io.github.qianyeeew.dshremote.ui.common.ComposerOption
import io.github.qianyeeew.dshremote.ui.common.ComposerOptionDot
import io.github.qianyeeew.dshremote.ui.common.ComposerOptionsRow
import io.github.qianyeeew.dshremote.ui.common.KeyboardPanel
import io.github.qianyeeew.dshremote.ui.common.LocalPanelDismiss
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.TuckUnderKeyboard
import io.github.qianyeeew.dshremote.ui.common.designPanel
import io.github.qianyeeew.dshremote.ui.common.onTap
import io.github.qianyeeew.dshremote.ui.common.panelArea
import io.github.qianyeeew.dshremote.ui.common.presetTint
import io.github.qianyeeew.dshremote.ui.common.pressScale
import io.github.qianyeeew.dshremote.ui.input

/** 新任务输入框上的操作。 */
class NewTaskActions(
    /** 读取模型和审批方式的列表（页面打开、在线时就读，输入框下面要显示）。 */
    val onExpand: () -> Unit = {},
    val onChooseProject: (ProjectPick) -> Unit = {},
    /** 不再指定这次的项目（回到上一次用的）。 */
    val onClearProject: () -> Unit = {},
    val onChooseModel: (ModelRef) -> Unit = {},
    val onChoosePreset: (String) -> Unit = {},
    /** 开始任务；返回 false 表示没有开始。 */
    val onStart: (text: String, project: ProjectPick) -> Boolean = { _, _ -> false },
    /** 任务已经开始（会话已创建）：进入会话。 */
    val onStarted: (StartedTask) -> Unit = {},
    /** 用户选了图片（落地方案 C2）。 */
    val onAddImages: (List<Uri>) -> Unit = {},
    val onRemoveImage: (key: String) -> Unit = {},
    /** 点了不可用的加号：说明原因。 */
    val onImagesUnavailable: () -> Unit = {},
)

/** 输入框下面面板里打开的是哪一种选项。 */
enum class NewTaskMenu { MODEL, PRESET }

/** 新任务输入框的占位文字：点过某个项目的加号时是「在 <项目> 工作区新建任务」，否则是「新任务」。 */
@Composable
internal fun newTaskHint(target: ProjectPick?): String =
    when (target) {
        is ProjectPick.Existing -> stringResource(R.string.new_task_in_project, target.workspace.title)
        else -> stringResource(R.string.home_new_task)
    }

/**
 * 收起时：一行占位文字（写过的字留着），右边带加号的实心圆。点了原地展开。
 * [targeted]：点过某个项目的加号，描边变成主文字色，提示这次要建在那个项目里。
 */
@Composable
internal fun NewTaskBar(
    text: String,
    hint: String,
    targeted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(52.dp)
            .designPanel(shape, focused = targeted)
            .clip(shape)
            .clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED)
            .padding(start = 18.dp, end = 6.dp)
            .testTag("home-new-session"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.ifBlank { hint },
            style = MaterialTheme.typography.bodyMedium,
            color = if (text.isBlank()) colors.textSecondary else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("home-new-session-text"),
        )
        Box(Modifier.size(40.dp).background(colors.text, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp), tint = colors.panel)
        }
    }
}

/** 输入框下面的「模型 · 审批方式」。还没读到列表时显示默认值，不能点。 */
@Composable
internal fun NewTaskOptions(
    state: NewTaskState,
    open: NewTaskMenu?,
    enabled: Boolean,
    onOpen: (NewTaskMenu) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    val models = state.models
    val model = state.model ?: models?.default
    val preset = state.preset ?: state.presets?.default
    val modelName =
        model?.let { ref ->
            models?.models?.firstOrNull { it.provider == ref.provider && it.model == ref.model }?.name ?: ref.model
        } ?: stringResource(R.string.design_choose_model)
    val presetName = permissionLabel(preset)
    ComposerOptionsRow(modifier.padding(top = 2.dp, bottom = 4.dp)) {
        ComposerOption(
            modelName,
            stringResource(R.string.design_model_value, modelName),
            R.drawable.ic_dsh_bolt,
            if (open == NewTaskMenu.MODEL) colors.text else colors.textSecondary,
            enabled = enabled && models != null && !state.starting,
            open = open == NewTaskMenu.MODEL,
            onClick = { onOpen(NewTaskMenu.MODEL) },
            modifier = Modifier.weight(1f, fill = false).testTag("new-task-model"),
        )
        ComposerOptionDot()
        ComposerOption(
            presetName,
            stringResource(R.string.design_permission_value, presetName),
            R.drawable.ic_dsh_shield,
            presetTint(preset),
            enabled = enabled && state.presets != null && !state.starting,
            open = open == NewTaskMenu.PRESET,
            onClick = { onOpen(NewTaskMenu.PRESET) },
            modifier = Modifier.weight(1f, fill = false).testTag("new-task-preset"),
        )
    }
}

/**
 * 展开的新任务输入框：上面是加号、任务内容和开始键，加上的图片（56 见方，右上角移除，落地方案 C2）在文字下面；
 * 下面常驻「模型 · 审批方式」（键盘弹出时收起）。
 * 点了模型或审批方式，键盘收起，原来键盘的位置换成选项面板。占位文字只在没有焦点时显示，点输入框就消失。
 * 项目由首页决定（点过加号的项目，或者上一次用的），这里不再选。
 *
 * [startMenu] 不为 null 时是从收起的输入框下面直接点了模型或审批方式：打开面板，不弹键盘。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewTaskComposer(
    text: String,
    onText: (String) -> Unit,
    state: NewTaskState,
    project: ProjectPick,
    hint: String,
    online: Boolean,
    actions: NewTaskActions,
    modifier: Modifier = Modifier,
    startMenu: NewTaskMenu? = null,
) {
    val colors = DesignTheme.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var menu by remember { mutableStateOf(startMenu) }
    // 收起的动画期间还要画最后一次打开的选项
    var lastMenu by remember { mutableStateOf(startMenu ?: NewTaskMenu.MODEL) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (startMenu == null) focus.requestFocus() }
    val imeVisible = WindowInsets.isImeVisible
    // 打开面板前正在打字：选完回到键盘
    var backToKeyboard by remember { mutableStateOf(false) }
    val toggle = { which: NewTaskMenu ->
        if (menu == which) {
            menu = null
        } else {
            if (menu == null) backToKeyboard = imeVisible
            keyboard?.hide()
            menu = which
            lastMenu = which
        }
    }
    val choose = { action: () -> Unit ->
        menu = null
        action()
        if (backToKeyboard) {
            focus.requestFocus()
            keyboard?.show()
        }
        backToKeyboard = false
    }
    // 返回键先收起面板，再按一次才收起输入框（这个后注册，比首页收起输入框的那个先拿到返回键）
    BackHandler(enabled = menu != null) { menu = null }
    val models = state.models
    val model = state.model ?: models?.default
    val presets = state.presets
    val preset = state.preset ?: presets?.default
    val description = stringResource(R.string.home_new_task)
    val pickImages =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(OutgoingMessage.MAX_IMAGES)) { uris ->
            if (uris.isNotEmpty()) actions.onAddImages(uris)
        }
    Column(modifier.fillMaxWidth().panelArea(LocalPanelDismiss.current).testTag("new-task")) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
                .fillMaxWidth()
                .designPanel(RoundedCornerShape(22.dp), focused = focused),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 3.dp, end = 1.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AddImageButton(
                    available = online && !state.starting && state.imageSupport.canAdd && state.imageSlots > 0,
                    onAdd = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onUnavailable = { if (state.imageSlots > 0 && !state.starting) actions.onImagesUnavailable() },
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    enabled = !state.starting,
                    textStyle = MaterialTheme.typography.input.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    minLines = 1,
                    maxLines = 8,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(top = 12.dp, bottom = 12.dp, end = 10.dp)
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }
                            .onTap {
                                // 点输入框：键盘回来，面板让位
                                menu = null
                                backToKeyboard = false
                                if (focused) keyboard?.show()
                            }.testTag("new-task-input")
                            .semantics { contentDescription = description },
                    decorationBox = { field ->
                        Box {
                            // 占位文字只在没有焦点时显示：点了输入框就消失，直接写
                            if (text.isEmpty() && !focused) {
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.input,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("new-task-hint"),
                                )
                            }
                            field()
                        }
                    },
                )
                StartKey(
                    enabled = online && (text.isNotBlank() || state.images.isNotEmpty()) && !state.starting && state.preparingImages == 0,
                    starting = state.starting,
                ) { actions.onStart(text, project) }
            }
            ComposerImageStrip(state.images, state.preparingImages, actions.onRemoveImage, Modifier.padding(start = 16.dp, bottom = 12.dp))
            state.imageNotice?.let { notice ->
                Text(
                    stringResource(noticeText(notice)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).testTag("new-task-image-notice"),
                )
            }
            state.error?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            CreateError.OFFLINE -> R.string.new_session_offline
                            CreateError.DSH_UNAVAILABLE -> R.string.new_session_dsh_unavailable
                            CreateError.NEEDS_PAIRING -> R.string.new_session_needs_pairing
                            CreateError.FAILED -> R.string.new_session_failed
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).testTag("new-task-error"),
                )
            }
        }
        // 键盘弹出时这一行藏在键盘后面，收键盘时跟着露出来
        TuckUnderKeyboard(exposed = menu != null) {
            NewTaskOptions(state, menu, enabled = true, onOpen = toggle)
        }
        val shown = menu ?: lastMenu
        KeyboardPanel(
            open = menu != null,
            title = stringResource(if (shown == NewTaskMenu.MODEL) R.string.panel_models else R.string.panel_permissions),
            // 点输入区外面：只收起面板，输入框留着
            onDismiss = {
                menu = null
                backToKeyboard = false
            },
        ) {
            val rowPadding = PaddingValues(horizontal = 12.dp)
            if (shown == NewTaskMenu.MODEL) {
                models?.models?.forEach { option ->
                    val ref = ModelRef(option.provider, option.model)
                    ChoiceRow(
                        option.name,
                        { choose { actions.onChooseModel(ref) } },
                        Modifier.testTag("new-task-model:${option.provider}/${option.model}"),
                        subtitle = option.providerName,
                        selected = ref == model,
                        contentPadding = rowPadding,
                    )
                }
            } else {
                presets?.presets?.forEach { option ->
                    val known = permissionHint(option.id)
                    ChoiceRow(
                        if (known != null) permissionLabel(option.id) else option.name,
                        { choose { actions.onChoosePreset(option.id) } },
                        Modifier.testTag("new-task-preset:${option.id}"),
                        subtitle = known,
                        selected = option.id == preset,
                        leadingIcon = R.drawable.ic_dsh_shield,
                        leadingTint = presetTint(option.id),
                        contentPadding = rowPadding,
                    )
                }
            }
        }
    }
}

/** 开始键：有字时主文字色实心圆和向上箭头；开始中换成转圈标记。按下时缩小。 */
@Composable
private fun StartKey(
    enabled: Boolean,
    starting: Boolean,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    val description = stringResource(R.string.new_task_start)
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(press)
            // 新任务还没有上下文，外圈只画轨道（设计规格 6.3）
            .contextRing(ratio = null, trackOnly = true)
            .clickable(interactionSource = press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("new-task-start"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).background(if (enabled || starting) colors.text else colors.fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (starting) {
                StatusMarker(StatusMark.RUNNING)
            } else {
                Icon(
                    painterResource(R.drawable.ic_dsh_arrow_up),
                    null,
                    Modifier.size(18.dp),
                    tint = if (enabled) colors.panel else colors.textSecondary,
                )
            }
        }
    }
}


private const val DISABLED = 0.5f
