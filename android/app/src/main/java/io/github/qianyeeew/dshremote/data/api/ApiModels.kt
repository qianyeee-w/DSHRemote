package io.github.qianyeeew.dshremote.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * docs/protocol.md 第 4、5 节的数据结构（插件的外观层接口，不是 DSH 的协议）。
 *
 * 兼容规则：插件新增的字段会被忽略；枚举出现不认识的值时变成 UNKNOWN，消息中不认识的 part 变成 UnknownPart。
 * 这样插件在同一协议版本内增加内容时，旧版 App 不会因为解析失败而整页出错。
 */

/** 解析插件响应用的 JSON 配置。 */
internal val ApiJson =
    Json {
        ignoreUnknownKeys = true
        // 不认识的枚举值变成属性的默认值（UNKNOWN）
        coerceInputValues = true
        // 为 null 的可选字段在请求中省略，缺少的可空字段解析为 null
        explicitNulls = false
        // 请求中带默认值的字段也写出（例如回答提问时空的 selected）
        encodeDefaults = true
    }

// ---------------------------------------------------------------- /v1/info、/v1/pair、/v1/me

@Serializable
data class PluginInfo(
    val protocol: Int,
    val pluginVersion: String,
    val dsh: DshInfo,
    /**
     * 电脑的时钟比手机快多少毫秒（可能为负），由响应的 HTTP `Date` 头估算，精度约 1 秒。不在 JSON 中；没有 `Date` 头时为 null。
     * 会话的 `updatedAt` 等时间来自电脑的时钟，显示「多久以前」时要用它换算（两边的时钟可能差很多）。
     */
    @Transient val clockOffsetMillis: Long? = null,
)

@Serializable
data class DshInfo(
    val baseline: String,
    /** 插件读不到 DSH 版本时为 null。 */
    val version: String? = null,
    val compatible: Boolean? = null,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val createdAt: Long,
)

// ---------------------------------------------------------------- 工作区、会话

@Serializable
data class Workspace(
    val workspaceId: String,
    val title: String,
    val path: String,
)

@Serializable
internal data class WorkspaceList(
    val workspaces: List<Workspace>,
)

@Serializable
data class DirectoryEntry(
    val name: String,
    val path: String,
    val hidden: Boolean,
)

@Serializable
data class DirectoryListing(
    val path: String,
    val home: String,
    val parent: String? = null,
    val entries: List<DirectoryEntry>,
    val truncated: Boolean,
)

@Serializable
data class WorkspaceRegistration(
    val workspace: Workspace,
    val created: Boolean,
)

@Serializable
internal data class DirectoryPath(
    val path: String,
)

@Serializable
internal data class CreateDirectoryRequest(
    val path: String,
    val name: String,
)

@Serializable
enum class SessionStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("waiting")
    WAITING,

    @SerialName("idle")
    IDLE,

    UNKNOWN,
}

@Serializable
enum class AttentionKind {
    @SerialName("approval")
    APPROVAL,

    @SerialName("question")
    QUESTION,

    UNKNOWN,
}

@Serializable
data class ModelRef(
    val provider: String,
    val model: String,
)

@Serializable
data class AttentionRef(
    val attentionId: String,
    val kind: AttentionKind = AttentionKind.UNKNOWN,
)

/** 会话摘要（protocol.md 5.1）。 */
@Serializable
data class SessionSummary(
    val sessionId: String,
    /** 还没有标题时为 null，界面显示「新会话」。 */
    val title: String? = null,
    val workspaceId: String? = null,
    val cwd: String? = null,
    val status: SessionStatus = SessionStatus.UNKNOWN,
    val attention: List<AttentionRef> = emptyList(),
    val model: ModelRef? = null,
    val permissionPreset: String? = null,
    val updatedAt: Long,
    /** 最近一轮已经结束的结果（protocol.md 5.1）。插件还没读到或者旧插件时为 null。 */
    val lastTurn: LastTurn? = null,
    /** 那一轮模型最后一段回复的开头。 */
    val lastReply: String? = null,
)

/** 会话摘要里的最近一轮。 */
@Serializable
data class LastTurn(
    val result: TurnResult = TurnResult.UNKNOWN,
    val endedAt: Long? = null,
    val error: String? = null,
)

/** 一轮怎么结束的（protocol.md 5.4）。 */
@Serializable
enum class TurnResult {
    /** 正常结束。**不表示需求已经完成**，界面写「本轮结束」。 */
    @SerialName("completed")
    COMPLETED,

    @SerialName("stopped")
    STOPPED,

    @SerialName("failed")
    FAILED,

    /** DSH 中途退出（崩溃、重启）后补记的结束。 */
    @SerialName("interrupted")
    INTERRUPTED,

    /** DSH 以后新增的结束原因。 */
    @SerialName("other")
    OTHER,

    /** 这个版本的 App 不认识的值。 */
    UNKNOWN,
}

/** 一轮（protocol.md 5.4）。还在运行的没有 [endedAt] 和 [result]。 */
@Serializable
data class Turn(
    val turn: Int,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val result: TurnResult? = null,
    val error: String? = null,
    /** `user` 表示用户点了停止。 */
    val stoppedBy: String? = null,
    /** 这一轮的用量（落地方案 B4）：只在结束、而且插件读到了整轮的事件时才有。 */
    val usage: TurnUsage? = null,
) {
    val running: Boolean get() = result == null && endedAt == null

    /** 同一轮从不同的页、推送里拿到的信息合在一起：后来的有值的字段覆盖前面的。 */
    fun merge(newer: Turn): Turn =
        Turn(
            turn = turn,
            startedAt = newer.startedAt ?: startedAt,
            endedAt = newer.endedAt ?: endedAt,
            result = newer.result ?: result,
            error = newer.error ?: error,
            stoppedBy = newer.stoppedBy ?: stoppedBy,
            usage = newer.usage ?: usage,
        )
}

@Serializable
internal data class SessionList(
    val sessions: List<SessionSummary>,
)

@Serializable
internal data class CreateSessionRequest(
    val workspaceId: String?,
)

@Serializable
internal data class CreateSessionResponse(
    val sessionId: String,
)

// ---------------------------------------------------------------- 消息（protocol.md 5.2）

@Serializable
enum class MessageRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    UNKNOWN,
}

@Serializable
data class Message(
    val id: String,
    val role: MessageRole = MessageRole.UNKNOWN,
    val parts: List<MessagePart>,
    val createdAt: Long,
    /** 流式输出中的草稿（只出现在实时推送中，id 以 `draft:` 开头），见 protocol.md 6.4。 */
    val draft: Boolean = false,
    /** 属于第几轮（protocol.md 5.2）；无法确定或者旧插件时为 null。 */
    val turn: Int? = null,
)

@Serializable(with = MessagePartSerializer::class)
sealed interface MessagePart

@Serializable
data class TextPart(
    val text: String,
) : MessagePart

@Serializable
data class ThinkingPart(
    val text: String,
) : MessagePart

@Serializable
enum class ToolStatus {
    @SerialName("running")
    RUNNING,

    @SerialName("done")
    DONE,

    @SerialName("error")
    ERROR,

    @SerialName("rejected")
    REJECTED,

    UNKNOWN,
}

@Serializable
data class ToolPart(
    val callId: String,
    val name: String,
    /** 一行给人看的说明，例如「运行命令：npm test」。 */
    val summary: String = "",
    val status: ToolStatus = ToolStatus.UNKNOWN,
    val input: JsonObject = JsonObject(emptyMap()),
    val output: String? = null,
    /** `input` 或 `output` 被插件截断了。 */
    val truncated: Boolean = false,
    /** 命令输出去掉了中间（protocol.md 5.2，落地方案 B6）：[output] 是开头加末尾。旧插件没有。 */
    val omitted: OutputOmission? = null,
) : MessagePart

/** 命令输出去掉中间的位置：[output] 的前 [offset] 个字符是开头，之后是末尾；中间去掉了 [chars] 个字符、[lines] 个换行。 */
@Serializable
data class OutputOmission(
    val offset: Int,
    val chars: Int,
    val lines: Int,
)

/** 这个版本的 App 不认识的 part 类型，界面上忽略。 */
@Serializable
data object UnknownPart : MessagePart

internal object MessagePartSerializer : JsonContentPolymorphicSerializer<MessagePart>(MessagePart::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out MessagePart> =
        when (((element as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull) {
            "text" -> TextPart.serializer()
            "thinking" -> ThinkingPart.serializer()
            "tool" -> ToolPart.serializer()
            else -> UnknownPart.serializer()
        }
}

/** 一页历史消息。`hasMore` 为 true 时，把 [cursor] 作为下一次请求的 `before`。 */
@Serializable
data class MessagePage(
    val messages: List<Message>,
    val hasMore: Boolean,
    val cursor: String? = null,
    /** 这一页出现的轮次（protocol.md 5.4）；旧插件没有。 */
    val turns: List<Turn> = emptyList(),
)

// ---------------------------------------------------------------- 发消息

@Serializable
enum class PromptMode {
    /** 当前任务结束后再处理。 */
    @SerialName("queue")
    QUEUE,

    /** 插话：在当前任务的下一步就让模型看到。 */
    @SerialName("steer")
    STEER,
}

@Serializable
internal data class PromptRequest(
    val requestId: String,
    val text: String,
    val mode: PromptMode,
)

@Serializable
internal data class Accepted(
    val accepted: Boolean,
)

// ---------------------------------------------------------------- 模型和权限模式

@Serializable
data class ModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class ModelList(
    val default: ModelRef? = null,
    val models: List<ModelOption>,
)

@Serializable
internal data class SetModelResponse(
    val model: ModelRef,
)

@Serializable
data class PermissionPreset(
    val id: String,
    val name: String,
)

@Serializable
data class PermissionPresetList(
    val default: String? = null,
    val presets: List<PermissionPreset>,
)

@Serializable
internal data class SetPresetRequest(
    val preset: String,
)

@Serializable
internal data class SetPresetResponse(
    val permissionPreset: String,
)

// ---------------------------------------------------------------- 待处理事项（protocol.md 5.3）

@Serializable
data class Attention(
    val attentionId: String,
    /** 子代理发起的事项可能是子代理的 id，不在会话列表中。 */
    val sessionId: String,
    val kind: AttentionKind = AttentionKind.UNKNOWN,
    val approval: Approval? = null,
    val question: QuestionSet? = null,
    val createdAt: Long,
)

@Serializable
data class Approval(
    val toolName: String,
    val reason: String = "",
    /** 插件找不到对应的工具调用时没有 summary 和 input。 */
    val summary: String? = null,
    val input: JsonObject? = null,
)

@Serializable
data class QuestionSet(
    val questions: List<Question>,
)

@Serializable
data class Question(
    val id: String,
    val question: String,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

/** 回答待处理事项（`POST /v1/attention/{id}/answer` 的请求体）。回答前的本地校验在 T10。 */
sealed interface AttentionAnswer {
    data class Decision(
        val allow: Boolean,
    ) : AttentionAnswer

    data class Answers(
        val answers: List<QuestionAnswer>,
    ) : AttentionAnswer
}

@Serializable
data class QuestionAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

@Serializable
internal data class DecisionRequest(
    val decision: String,
)

@Serializable
internal data class AnswersRequest(
    val answers: List<QuestionAnswer>,
)

@Serializable
internal data class AnswerResponse(
    val attentionId: String,
)

// ---------------------------------------------------------------- 错误（protocol.md 第 7 节）

@Serializable
internal data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
internal data class ErrorBody(
    val code: String,
    val message: String? = null,
)
