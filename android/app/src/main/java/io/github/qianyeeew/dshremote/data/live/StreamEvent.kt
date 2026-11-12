package io.github.qianyeeew.dshremote.data.live

import io.github.qianyeeew.dshremote.data.api.ApiJson
import io.github.qianyeeew.dshremote.data.api.Attention
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.Message
import io.github.qianyeeew.dshremote.data.api.SessionSummary
import io.github.qianyeeew.dshremote.data.api.Turn
import io.github.qianyeeew.dshremote.data.api.TurnResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 实时流中插件发给 App 的消息（protocol.md 6.1）。每条都带 [eventId]，App 用它去重。
 *
 * 解析规则和 REST 一样宽松：多出来的字段忽略；不认识的 `type` 解析为 [Unknown]，App 忽略它；
 * 认识的 `type` 缺少必需的字段时整条丢弃（[parse] 返回 null），不让一条坏消息断开连接。
 */
sealed interface StreamEvent {
    val eventId: String

    /** 连接建立后的第一条消息：当前的完整状态，App 用它**整体覆盖**本地状态。 */
    data class Hello(
        override val eventId: String,
        val protocol: Int,
        val sessions: List<SessionSummary>,
        val attention: List<Attention>,
    ) : StreamEvent

    data class SessionUpdated(
        override val eventId: String,
        val session: SessionSummary,
    ) : StreamEvent

    data class SessionRemoved(
        override val eventId: String,
        val sessionId: String,
    ) : StreamEvent

    /** 会话从运行变为空闲并持续了 8 秒，用于「任务结束」通知（T11）。[result] 是刚结束那一轮的结果，旧插件没有。 */
    data class SessionIdle(
        override val eventId: String,
        val sessionId: String,
        val title: String?,
        val result: TurnResult? = null,
    ) : StreamEvent

    data class AttentionRequested(
        override val eventId: String,
        val attention: Attention,
    ) : StreamEvent

    data class AttentionResolved(
        override val eventId: String,
        val attentionId: String,
        val sessionId: String,
        val by: ResolvedBy,
    ) : StreamEvent

    /** 只对已订阅的会话发送。合并规则（rev、replaces）见 protocol.md 6.4，由会话详情页（T9）处理。 */
    data class MessageUpsert(
        override val eventId: String,
        val sessionId: String,
        val rev: Long,
        val message: Message,
        val replaces: String?,
    ) : StreamEvent

    data class MessageRemoved(
        override val eventId: String,
        val sessionId: String,
        val messageId: String,
    ) : StreamEvent

    /** 只对已订阅的会话发送：一轮开始或结束（protocol.md 6.1）。 */
    data class TurnUpdated(
        override val eventId: String,
        val sessionId: String,
        val turn: Turn,
    ) : StreamEvent

    /** 插件报告的错误。[message] 由插件保证不含敏感信息。 */
    data class Error(
        override val eventId: String,
        val code: ErrorCode,
        val message: String?,
    ) : StreamEvent

    /** 这个版本的 App 不认识的消息类型，忽略。 */
    data class Unknown(
        override val eventId: String,
        val type: String,
    ) : StreamEvent

    companion object {
        /** 解析一个文本帧。不是 JSON、缺少 `type` 或 `eventId`、必需字段缺失或格式不对时返回 null。 */
        fun parse(text: String): StreamEvent? {
            val json =
                try {
                    ApiJson.parseToJsonElement(text) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return null
            val type = json.string("type") ?: return null
            val eventId = json.string("eventId")?.takeIf { it.isNotEmpty() } ?: return null
            return try {
                when (type) {
                    "hello" ->
                        Hello(
                            eventId,
                            protocol = json.number("protocol")?.intOrNull ?: return null,
                            sessions = json.decode("sessions", ListSerializers.sessions) ?: return null,
                            attention = json.decode("attention", ListSerializers.attention) ?: return null,
                        )
                    "session.updated" -> SessionUpdated(eventId, json.decode("session", SessionSummary.serializer()) ?: return null)
                    "session.removed" -> SessionRemoved(eventId, json.string("sessionId") ?: return null)
                    "session.idle" ->
                        SessionIdle(
                            eventId,
                            json.string("sessionId") ?: return null,
                            json.string("title"),
                            json.string("result")?.let { value ->
                                TurnResult.entries.firstOrNull { it.serialName() == value }
                                    ?: TurnResult.UNKNOWN
                            },
                        )
                    "attention.requested" -> AttentionRequested(eventId, json.decode("attention", Attention.serializer()) ?: return null)
                    "attention.resolved" ->
                        AttentionResolved(
                            eventId,
                            attentionId = json.string("attentionId") ?: return null,
                            sessionId = json.string("sessionId") ?: return null,
                            by = ResolvedBy.of(json.string("by")),
                        )
                    "message.upsert" ->
                        MessageUpsert(
                            eventId,
                            sessionId = json.string("sessionId") ?: return null,
                            rev = json.number("rev")?.longOrNull ?: return null,
                            message = json.decode("message", Message.serializer()) ?: return null,
                            replaces = json.string("replaces"),
                        )
                    "message.removed" ->
                        MessageRemoved(
                            eventId,
                            sessionId = json.string("sessionId") ?: return null,
                            messageId = json.string("messageId") ?: return null,
                        )
                    "turn.updated" ->
                        TurnUpdated(
                            eventId,
                            sessionId = json.string("sessionId") ?: return null,
                            turn = json.decode("turn", Turn.serializer()) ?: return null,
                        )
                    "error" -> Error(eventId, ErrorCode.of(json.string("code")), json.string("message"))
                    else -> Unknown(eventId, type)
                }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** `attention.resolved` 的 `by`。 */
enum class ResolvedBy {
    /** 这台手机处理的。 */
    THIS_DEVICE,

    /** 在别处处理掉了，或者任务被停止（DSH 0.2.2 不区分这两种情况）。 */
    OTHER,

    UNKNOWN,
    ;

    companion object {
        fun of(value: String?): ResolvedBy =
            when (value) {
                "this-device" -> THIS_DEVICE
                "other" -> OTHER
                else -> UNKNOWN
            }
    }
}

/** App 发给插件的消息（protocol.md 6.2）。sessionId 的格式由调用方先校验。 */
internal object StreamCommands {
    fun subscribe(sessionId: String): String = command("subscribe", sessionId)

    fun unsubscribe(sessionId: String): String = command("unsubscribe", sessionId)

    private fun command(
        type: String,
        sessionId: String,
    ): String =
        buildJsonObject {
            put("type", type)
            put("sessionId", sessionId)
        }.toString()
}

private object ListSerializers {
    val sessions = ListSerializer(SessionSummary.serializer())
    val attention = ListSerializer(Attention.serializer())
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

/** 数字字段。字符串形式的数字（例如 `"1"`）当作缺失。 */
private fun JsonObject.number(key: String): JsonPrimitive? = primitive(key)?.takeIf { !it.isString }

/** 字符串字段。JSON 的 null、数字等其他类型都当作缺失。 */
private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.contentOrNull

private fun <T> JsonObject.decode(
    key: String,
    serializer: KSerializer<T>,
): T? {
    val element: JsonElement = this[key] ?: return null
    if (element is JsonPrimitive && element.contentOrNull == null) return null
    return ApiJson.decodeFromJsonElement(serializer, element)
}

/** 枚举在协议里的名字（@SerialName）。 */
internal fun TurnResult.serialName(): String = TurnResult.serializer().descriptor.getElementName(ordinal)
