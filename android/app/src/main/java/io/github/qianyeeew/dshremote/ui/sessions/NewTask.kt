package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.ModelList
import io.github.qianyeeew.dshremote.data.api.ModelRef
import io.github.qianyeeew.dshremote.data.api.OutgoingImage
import io.github.qianyeeew.dshremote.data.api.OutgoingMessage
import io.github.qianyeeew.dshremote.data.api.PermissionPresetList
import io.github.qianyeeew.dshremote.data.api.PromptMode
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.api.isTokenInvalid
import io.github.qianyeeew.dshremote.data.chat.deliveryStatus
import io.github.qianyeeew.dshremote.data.image.ImageSupport
import io.github.qianyeeew.dshremote.data.live.OfflineException
import io.github.qianyeeew.dshremote.ui.chat.ChatHandoff
import io.github.qianyeeew.dshremote.ui.chat.ChatNotice
import io.github.qianyeeew.dshremote.ui.chat.imageNotice
import io.github.qianyeeew.dshremote.ui.chat.imageRejection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 新任务在哪个项目里开始。 */
sealed interface ProjectPick {
    data class Existing(
        val workspace: Workspace,
    ) : ProjectPick

    /** 不指定项目，由 DSH 决定工作目录（用户确认保留这一项）。 */
    data object None : ProjectPick
}

enum class CreateError {
    /** 电脑不在线（或请求时连接断了）。 */
    OFFLINE,

    /** 插件在运行，但 DSH 暂时不可用。 */
    DSH_UNAVAILABLE,

    /** token 已失效，需要重新配对。 */
    NEEDS_PAIRING,

    FAILED,
}

/** 任务已经开始（会话已创建），界面进入这个会话；[handoff] 不为 null 时会话页要接着处理。 */
data class StartedTask(
    val sessionId: String,
    val handoff: ChatHandoff?,
)

data class NewTaskState(
    /** 用户在这次展开里选的项目；null 时用 [defaultProject]。 */
    val project: ProjectPick? = null,
    val models: ModelList? = null,
    val presets: PermissionPresetList? = null,
    /** 选的模型；null 是默认模型。 */
    val model: ModelRef? = null,
    /** 选的审批方式；null 是默认。 */
    val preset: String? = null,
    val starting: Boolean = false,
    /** 会话没能创建（之后的步骤失败不在这里，见 [StartedTask.handoff]）。 */
    val error: CreateError? = null,
    val started: StartedTask? = null,
    /** 加上的图片（落地方案 C2，只在内存里）和还在压缩的张数。 */
    val images: List<OutgoingImage> = emptyList(),
    val preparingImages: Int = 0,
    /** 和图片有关的说明（不能加图片的原因、有的图片读不出来）。 */
    val imageNotice: ChatNotice? = null,
) {
    /** 按选的模型（没选时是默认模型）判断能不能加图片。 */
    val imageSupport: ImageSupport get() = ImageSupport.of(models, model)

    val imageSlots: Int get() = (OutgoingMessage.MAX_IMAGES - images.size - preparingImages).coerceAtLeast(0)
}

/**
 * 默认的项目：上一次用的项目还在就用它（[last] 为空串表示上次选了「不指定项目」）；没有记录时用第一个项目；
 * 一个项目都没有时不指定。
 */
fun defaultProject(
    workspaces: List<Workspace>,
    last: String?,
): ProjectPick =
    when {
        last == NO_PROJECT -> ProjectPick.None
        last != null -> workspaces.firstOrNull { it.workspaceId == last }?.let { ProjectPick.Existing(it) } ?: firstOrNone(workspaces)
        else -> firstOrNone(workspaces)
    }

private fun firstOrNone(workspaces: List<Workspace>): ProjectPick =
    workspaces.firstOrNull()?.let { ProjectPick.Existing(it) } ?: ProjectPick.None

/** 本地设置里「上次选了不指定项目」的记法。 */
const val NO_PROJECT = ""

/**
 * 首页开始新任务（设计规格 7.6、落地方案 A5）。开始一个任务是三步，用的都是已有接口：
 * 1. 创建会话。失败时留在首页，文字保留，提示原因，可以再点发送。
 * 2. 模型或审批方式和默认不同时分别设置。失败时会话已经有了，不再重复创建：进入会话，文字放回输入框并提示。
 * 3. 发出第一条消息。失败时进入会话，显示成发送失败的气泡，重试沿用同一个 requestId。
 *
 * 所有状态只在 [scope]（主线程）上修改。
 */
class NewTaskFlow(
    private val scope: CoroutineScope,
    private val backend: SessionCreator,
    private val saveLastProject: suspend (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(NewTaskState())
    val state: StateFlow<NewTaskState> = mutable.asStateFlow()

    /** 展开时读取模型和审批方式的列表（默认值从这里来）。读不到不影响开始任务，只是不能改选。 */
    fun loadOptions() {
        val current = mutable.value
        if (current.models == null) {
            scope.launch { runCatchingApi { backend.models() }?.let { list -> mutable.update { it.copy(models = list) } } }
        }
        if (current.presets == null) {
            scope.launch { runCatchingApi { backend.presets() }?.let { list -> mutable.update { it.copy(presets = list) } } }
        }
    }

    fun chooseProject(pick: ProjectPick) = mutable.update { it.copy(project = pick, error = null) }

    fun chooseModel(model: ModelRef) = mutable.update { it.copy(model = model, imageNotice = null) }

    /** 点了不可用的加号：说明原因（还没读到模型列表时顺便再读）。 */
    fun explainImages() {
        val support = mutable.value.imageSupport
        if (support == ImageSupport.CHECKING) {
            mutable.update { it.copy(models = null) }
            loadOptions()
        }
        mutable.update { it.copy(imageNotice = imageNotice(support)) }
    }

    /** 加上用户选的图片（在后台压缩），超过张数的部分不加。 */
    fun addImages(sources: List<suspend () -> OutgoingImage?>) {
        val current = mutable.value
        val take = sources.take(current.imageSlots)
        if (take.isEmpty() || !current.imageSupport.canAdd) return
        mutable.update { it.copy(preparingImages = it.preparingImages + take.size, imageNotice = null) }
        scope.launch {
            var unreadable = false
            for (source in take) {
                val image =
                    try {
                        source()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                if (image == null) unreadable = true
                mutable.update {
                    it.copy(
                        preparingImages = it.preparingImages - 1,
                        images = if (image != null && it.images.size < OutgoingMessage.MAX_IMAGES) it.images + image else it.images,
                    )
                }
            }
            if (unreadable) mutable.update { it.copy(imageNotice = ChatNotice.IMAGE_UNREADABLE) }
        }
    }

    fun removeImage(key: String) = mutable.update { s -> s.copy(images = s.images.filterNot { it.key == key }) }

    fun choosePreset(preset: String) = mutable.update { it.copy(preset = preset) }

    /** 开始任务。[project] 是界面上显示的项目（用户选的或默认的）。返回 false 表示没有开始（没写内容、太长或正在开始）。 */
    fun start(
        text: String,
        project: ProjectPick,
    ): Boolean {
        val current = mutable.value
        val images = current.images
        if (current.starting || (text.isBlank() && images.isEmpty()) || text.length > OutgoingMessage.MAX_TEXT) return false
        if (current.preparingImages > 0) return false
        if (images.isNotEmpty() && !current.imageSupport.canAdd) {
            mutable.update { it.copy(imageNotice = imageNotice(current.imageSupport)) }
            return false
        }
        mutable.update { it.copy(starting = true, error = null, imageNotice = null) }
        scope.launch {
            val workspaceId = (project as? ProjectPick.Existing)?.workspace?.workspaceId
            val sessionId =
                try {
                    backend.create(workspaceId)
                } catch (e: ApiException) {
                    fail(e)
                    return@launch
                } catch (e: OfflineException) {
                    fail(e)
                    return@launch
                }
            runCatchingApi { saveLastProject(workspaceId ?: NO_PROJECT) }
            val state = mutable.value
            val model = state.model?.takeIf { it != state.models?.default }
            val preset = state.preset?.takeIf { it != state.presets?.default }
            val configured =
                runCatchingApi {
                    model?.let { backend.setModel(sessionId, it) }
                    preset?.let { backend.setPreset(sessionId, it) }
                    true
                } ?: false
            if (!configured) {
                finish(StartedTask(sessionId, ChatHandoff.NotSent(text, images)))
                return@launch
            }
            val message = OutgoingMessage(text, PromptMode.QUEUE, images)
            // 发一次，不自动重发；没送到和不确定交给会话页，由用户决定（落地方案 B3）
            val failure =
                try {
                    backend.prompt(sessionId, message)
                    null
                } catch (e: ApiException) {
                    e
                } catch (e: OfflineException) {
                    e
                }
            // 插件因为图片拒绝了：消息肯定没发出，文字和图片放回会话的输入框并说明（落地方案 C2）
            val handoff =
                failure?.let { e ->
                    imageRejection(e)?.let { ChatHandoff.NotSent(text, images, it) }
                        ?: ChatHandoff.FailedFirstMessage(message, deliveryStatus(e))
                }
            finish(StartedTask(sessionId, handoff))
        }
        return true
    }

    /** 界面已经进入了新会话：清掉这次的选择，下次展开从默认值开始。 */
    fun consumeStarted() = mutable.update { NewTaskState(models = it.models, presets = it.presets) }

    fun dismissImageNotice() = mutable.update { it.copy(imageNotice = null) }

    private fun finish(started: StartedTask) = mutable.update { it.copy(starting = false, started = started) }

    private fun fail(e: Exception) = mutable.update { it.copy(starting = false, error = errorOf(e)) }

    private inline fun <T> runCatchingApi(block: () -> T): T? =
        try {
            block()
        } catch (_: ApiException) {
            null
        } catch (_: OfflineException) {
            null
        }

    private companion object {
        const val HTTP_UNAVAILABLE = 503
    }

    private fun errorOf(e: Exception): CreateError =
        when {
            e is OfflineException || e is ApiException.Network -> CreateError.OFFLINE
            e is ApiException && e.isTokenInvalid -> CreateError.NEEDS_PAIRING
            e is ApiException.Http && (e.code == ErrorCode.DSH_UNAVAILABLE || e.status == HTTP_UNAVAILABLE) -> CreateError.DSH_UNAVAILABLE
            else -> CreateError.FAILED
        }
}
