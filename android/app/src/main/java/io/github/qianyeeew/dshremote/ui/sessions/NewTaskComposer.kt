package io.github.qianyeeew.dshremote.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import io.github.qianyeeew.dshremote.ui.common.ChoiceChevron
import io.github.qianyeeew.dshremote.ui.common.ChoiceList
import io.github.qianyeeew.dshremote.ui.common.ChoiceRow
import io.github.qianyeeew.dshremote.ui.common.DesignDivider
import io.github.qianyeeew.dshremote.ui.common.InlineChoices
import io.github.qianyeeew.dshremote.ui.common.StatusMark
import io.github.qianyeeew.dshremote.ui.common.StatusMarker
import io.github.qianyeeew.dshremote.ui.common.designPanel
import io.github.qianyeeew.dshremote.ui.common.onTap

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
    // 收起的动画期间还要画最后一次展开的选项
    var lastMenu by remember { mutableStateOf(Menu.PROJECT) }
    val toggle = { which: Menu ->
        menu = if (menu == which || (which == Menu.PROJECT && menu == Menu.CREATE)) null else which
        menu?.let { lastMenu = it }
    }
    // 返回键先收起选项，再按一次才收起输入框（这个后注册，比首页收起输入框的那个先拿到返回键）
    BackHandler(enabled = menu != null) { menu = null }
    val noProject = stringResource(R.string.new_task_no_project)
    val models = state.models
    val model = state.model ?: models?.default
    val presets = state.presets
    val preset = state.preset ?: presets?.default
    val choose = { action: () -> Unit ->
        menu = null
        action()
    }
    Column(
        modifier
            .fillMaxWidth()
            .designPanel(RoundedCornerShape(22.dp), focused = true)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)
            .testTag("new-task"),
    ) {
        // 项目：在「项目」那一行的正上方展开，卡片向上长
        InlineChoices(visible = menu == Menu.PROJECT || menu == Menu.CREATE) {
            ChoiceList(Modifier.padding(top = 6.dp)) {
                if ((menu ?: lastMenu) == Menu.CREATE) {
                    ChoiceRow(
                        stringResource(R.string.project_create),
                        { choose { onProject(ProjectMode.CREATE) } },
                        Modifier.testTag("new-task-create"),
                        contentPadding = InCard,
                    )
                    ChoiceRow(
                        stringResource(R.string.project_import),
                        { choose { onProject(ProjectMode.IMPORT) } },
                        Modifier.testTag("new-task-import"),
                        contentPadding = InCard,
                    )
                } else {
                    workspaces.forEach { workspace ->
                        val current = (project as? ProjectPick.Existing)?.workspace?.workspaceId == workspace.workspaceId
                        ChoiceRow(
                            workspace.title,
                            { choose { actions.onChooseProject(ProjectPick.Existing(workspace)) } },
                            Modifier.testTag("new-task-project:${workspace.workspaceId}"),
                            selected = current,
                            contentPadding = InCard,
                        )
                    }
                    ChoiceRow(
                        noProject,
                        { choose { actions.onChooseProject(ProjectPick.None) } },
                        Modifier.testTag("new-task-project:none"),
                        selected = project == ProjectPick.None,
                        muted = project != ProjectPick.None,
                        contentPadding = InCard,
                    )
                    ChoiceRow(
                        stringResource(R.string.new_task_create_or_import),
                        {
                            menu = Menu.CREATE
                            lastMenu = Menu.CREATE
                        },
                        Modifier.testTag("new-task-create-or-import"),
                        leadingIcon = R.drawable.ic_add,
                        contentPadding = InCard,
                    )
                }
            }
            DesignDivider(Modifier.padding(end = 10.dp, top = 4.dp))
        }
        val projectOpen = menu == Menu.PROJECT || menu == Menu.CREATE
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .clickable(enabled = !state.starting, role = Role.Button) { toggle(Menu.PROJECT) }
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
                modifier = Modifier.weight(1f, fill = false),
            )
            ChoiceChevron(projectOpen, Modifier.size(12.dp))
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
                    .onTap { menu = null }
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
        // 模型和审批方式：在最下面一行的正上方展开
        InlineChoices(visible = (menu == Menu.MODEL && models != null) || (menu == Menu.PRESET && presets != null)) {
            DesignDivider(Modifier.padding(end = 10.dp, top = 8.dp, bottom = 4.dp))
            ChoiceList(withSubtitles = true) {
                if ((menu ?: lastMenu) == Menu.MODEL) {
                    models?.models?.forEach { option ->
                        val ref = ModelRef(option.provider, option.model)
                        ChoiceRow(
                            option.name,
                            { choose { actions.onChooseModel(ref) } },
                            subtitle = option.providerName,
                            selected = ref == model,
                            contentPadding = InCard,
                        )
                    }
                } else {
                    presets?.presets?.forEach { option ->
                        val known = permissionHint(option.id)
                        ChoiceRow(
                            if (known != null) permissionLabel(option.id) else option.name,
                            { choose { actions.onChoosePreset(option.id) } },
                            subtitle = known,
                            selected = option.id == preset,
                            contentPadding = InCard,
                        )
                    }
                }
            }
            DesignDivider(Modifier.padding(end = 10.dp, top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val modelName =
                    model?.let { ref ->
                        models?.models?.firstOrNull { it.provider == ref.provider && it.model == ref.model }?.name
                            ?: ref.model
                    }
                Choice(
                    modelName ?: stringResource(R.string.design_choose_model),
                    enabled = models != null && !state.starting,
                    open = menu == Menu.MODEL,
                    tag = "new-task-model",
                    modifier = Modifier.weight(1f, fill = false),
                ) { toggle(Menu.MODEL) }
                Choice(
                    permissionLabel(preset),
                    enabled = presets != null && !state.starting,
                    open = menu == Menu.PRESET,
                    tag = "new-task-preset",
                    modifier = Modifier.weight(1f, fill = false),
                ) { toggle(Menu.PRESET) }
            }
            StartKey(
                enabled = online && text.isNotBlank() && !state.starting,
                starting = state.starting,
            ) { actions.onStart(text, project) }
        }
    }
}

/** 卡片左边已经有 16 的内边距，右边只有 6：选项行左边不再加，右边补到和左边对称。 */
private val InCard = PaddingValues(end = 10.dp)

private enum class Menu { PROJECT, CREATE, MODEL, PRESET }

@Composable
private fun Choice(
    label: String,
    enabled: Boolean,
    open: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DesignTheme.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 展开时文字换成主文字色、字重 500，小箭头朝上（设计规格 6.10）
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (open) FontWeight.Medium else FontWeight.Normal),
            color = (if (open) colors.text else colors.textSecondary).copy(alpha = if (enabled) 1f else DISABLED),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        ChoiceChevron(open, Modifier.size(12.dp), tint = if (open) colors.text else colors.textSecondary)
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

private const val DISABLED = 0.5f
