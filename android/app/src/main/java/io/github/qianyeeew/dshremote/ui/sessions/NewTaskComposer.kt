package io.github.qianyeeew.dshremote.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.qianyeeew.dshremote.R
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.ui.DesignTheme
import io.github.qianyeeew.dshremote.ui.chat.permissionHint
import io.github.qianyeeew.dshremote.ui.chat.permissionLabel
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.DesignPopup
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.designPanel

/** 新任务输入框上的操作。 */
class NewTaskActions(
    /** 展开了：读取模型和审批方式的列表。 */
    val onExpand: () -> Unit = {},
    val onChooseProject: (ProjectPick) -> Unit = {},
    val onChooseModel: (ModelRef) -> Unit = {},
    val onChoosePreset: (String) -> Unit = {},
    /** 开始任务；返回 false 表示没有开始。 */
    val onStart: (text: String, project: ProjectPick) -> Boolean = { _, _ -> false },
    /** 任务已经开始（会话已创建）：进入会话。 */
    val onStarted: (StartedTask) -> Unit = {},
)

/** 收起时：一行占位文字「新任务」（写过的字留着），右边带加号的实心圆（设计规格 7.1）。点了原地展开。 */
@Composable
internal fun NewTaskBar(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.page)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .designPanel(RoundedCornerShape(26.dp))
                .clickable(enabled = enabled, onClickLabel = stringResource(R.string.home_new_task), role = Role.Button, onClick = onClick)
                .alpha(if (enabled) 1f else DISABLED)
                .padding(start = 18.dp, end = 6.dp)
                .testTag("home-new-session"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text.ifBlank { stringResource(R.string.home_new_task) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (text.isBlank()) colors.textSecondary else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(40.dp).background(colors.text, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp), tint = colors.panel)
            }
        }
    }
}

/**
 * 展开的新任务输入框（设计规格 7.6）：最上面一行「项目 <项目名>」，点了选项目（已有项目、不指定项目、新建或导入项目）；
 * 中间是任务内容；最下面是模型、审批方式、发送键。图片（落地方案 C2）先不做。
 */
@Composable
internal fun NewTaskComposer(
    text: String,
    onText: (String) -> Unit,
    state: NewTaskState,
    project: ProjectPick,
    workspaces: List<Workspace>,
    online: Boolean,
    actions: NewTaskActions,
    onProject: (ProjectMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DesignTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var menu by remember { mutableStateOf<Menu?>(null) }
    val noProject = stringResource(R.string.new_task_no_project)
    Column(
        modifier
            .fillMaxWidth()
            .designPanel(RoundedCornerShape(22.dp), focused = true)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)
            .testTag("new-task"),
    ) {
        Box {
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = !state.starting, role = Role.Button) { menu = Menu.PROJECT }
                    .testTag("new-task-project"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.new_task_project), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Text(
                    when (project) {
                        is ProjectPick.Existing -> project.workspace.title
                        ProjectPick.None -> noProject
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(painterResource(R.drawable.ic_dsh_chevron_down), null, Modifier.size(12.dp), tint = colors.textSecondary)
            }
            if (menu == Menu.PROJECT || menu == Menu.CREATE) {
                DesignPopup(onDismiss = { menu = null }, modifier = Modifier.width(280.dp)) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                        if (menu == Menu.CREATE) {
                            PickRow(stringResource(R.string.project_create), testTag = "new-task-create") {
                                menu = null
                                onProject(ProjectMode.CREATE)
                            }
                            PickRow(stringResource(R.string.project_import), testTag = "new-task-import") {
                                menu = null
                                onProject(ProjectMode.IMPORT)
                            }
                        } else {
                            workspaces.forEach { workspace ->
                                val current = (project as? ProjectPick.Existing)?.workspace?.workspaceId == workspace.workspaceId
                                PickRow(workspace.title, selected = current, testTag = "new-task-project:${workspace.workspaceId}") {
                                    menu = null
                                    actions.onChooseProject(ProjectPick.Existing(workspace))
                                }
                            }
                            PickRow(noProject, selected = project == ProjectPick.None, testTag = "new-task-project:none") {
                                menu = null
                                actions.onChooseProject(ProjectPick.None)
                            }
                            DesignDivider(Modifier.padding(vertical = 4.dp))
                            PickRow(stringResource(R.string.new_task_create_or_import), testTag = "new-task-create-or-import") {
                                menu =
                                    Menu.CREATE
                            }
                        }
                    }
                }
            }
        }
        val hint = stringResource(R.string.home_new_task)
        BasicTextField(
            value = text,
            onValueChange = onText,
            enabled = !state.starting,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            minLines = 2,
            maxLines = 8,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = 10.dp)
                    .focusRequester(focus)
                    .testTag("new-task-input")
                    .semantics { contentDescription = hint },
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    field()
                }
            },
        )
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
                modifier = Modifier.padding(top = 8.dp).testTag("new-task-error"),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                val models = state.models
                val model = state.model ?: models?.default
                val modelName =
                    model?.let { ref ->
                        models?.models?.firstOrNull { it.provider == ref.provider && it.model == ref.model }?.name
                            ?: ref.model
                    }
                Box(Modifier.weight(1f, fill = false)) {
                    Choice(
                        modelName ?: stringResource(R.string.design_choose_model),
                        enabled = models != null && !state.starting,
                        tag = "new-task-model",
                    ) {
                        menu = Menu.MODEL
                    }
                    if (menu == Menu.MODEL && models != null) {
                        DesignPopup(onDismiss = { menu = null }, alignment = Alignment.BottomStart, modifier = Modifier.width(280.dp)) {
                            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                                models.models.forEach { option ->
                                    val ref = ModelRef(option.provider, option.model)
                                    PickRow(option.name, subtitle = option.providerName, selected = ref == model) {
                                        menu = null
                                        actions.onChooseModel(ref)
                                    }
                                }
                            }
                        }
                    }
                }
                val presets = state.presets
                val preset = state.preset ?: presets?.default
                Box(Modifier.weight(1f, fill = false)) {
                    Choice(permissionLabel(preset), enabled = presets != null && !state.starting, tag = "new-task-preset") {
                        menu =
                            Menu.PRESET
                    }
                    if (menu == Menu.PRESET && presets != null) {
                        DesignPopup(onDismiss = { menu = null }, alignment = Alignment.BottomStart, modifier = Modifier.width(280.dp)) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                presets.presets.forEach { option ->
                                    val hint = permissionHint(option.id)
                                    PickRow(
                                        if (hint !=
                                            null
                                        ) {
                                            permissionLabel(option.id)
                                        } else {
                                            option.name
                                        },
                                        subtitle = hint,
                                        selected = option.id == preset,
                                    ) {
                                        menu = null
                                        actions.onChoosePreset(option.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            StartKey(
                enabled = online && text.isNotBlank() && !state.starting,
                starting = state.starting,
            ) { actions.onStart(text, project) }
        }
    }
}

private enum class Menu { PROJECT, CREATE, MODEL, PRESET }

@Composable
private fun Choice(
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary.copy(alpha = if (enabled) 1f else DISABLED),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(painterResource(R.drawable.ic_dsh_chevron_down), null, Modifier.size(12.dp), tint = colors.textSecondary)
    }
}

/** 发送键：有字时主文字色实心圆和向上箭头；开始中换成转圈标记。 */
@Composable
private fun StartKey(
    enabled: Boolean,
    starting: Boolean,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    val description = stringResource(R.string.new_task_start)
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("new-task-start"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).background(
                if (enabled ||
                    starting
                ) {
                    colors.text
                } else {
                    colors.fill
                },
                CircleShape,
            ),
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

@Composable
private fun PickRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .let { if (testTag != null) it.testTag(testTag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
            }
        }
        if (selected) Icon(painterResource(R.drawable.ic_dsh_check), null, Modifier.size(18.dp), tint = colors.text)
    }
}

private const val DISABLED = 0.5f
