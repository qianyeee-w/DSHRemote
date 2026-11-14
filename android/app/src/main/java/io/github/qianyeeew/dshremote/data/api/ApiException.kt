package io.github.qianyeeew.dshremote.data.api

import java.io.IOException

/**
 * 调用插件接口失败。异常信息中不包含 token、配对码或请求内容（红线第 5 条）。
 */
sealed class ApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 插件返回了错误响应（protocol.md 第 7 节）。 */
    class Http(
        val status: Int,
        val code: ErrorCode,
        /** 插件给出的说明，可以显示给用户（插件保证其中不含敏感信息）。 */
        val serverMessage: String?,
        /** `429 PAIRING_LOCKED` 时的 `Retry-After`（秒）。 */
        val retryAfterSeconds: Long? = null,
    ) : ApiException("插件返回错误：HTTP $status ${code.name}")

    /** 服务器证书的指纹和配对时保存的不一致。不能重试，也不提供「仍然继续」（红线第 2 条）。 */
    class FingerprintMismatch(
        cause: Throwable,
    ) : ApiException("服务器证书的指纹和配对时保存的不一致", cause)

    /**
     * 连不上、超时、连接中断等网络问题。可以重试。
     * [maybeDelivered]：请求体已经开始写出，插件可能已经收到（只有发消息区分这一点，其他接口一律为 true）。
     */
    class Network(
        cause: IOException,
        val maybeDelivered: Boolean = true,
    ) : ApiException("网络错误：${cause.javaClass.simpleName}", cause)

    /** 插件返回了无法解析的内容（多半是 App 和插件的版本不匹配）。 */
    class BadResponse(
        detail: String,
        cause: Throwable? = null,
    ) : ApiException("插件返回了无法解析的内容：$detail", cause)
}

/** protocol.md 第 7 节的错误码。不认识的错误码为 [UNKNOWN]。 */
enum class ErrorCode {
    TOKEN_INVALID,
    PAIRING_CODE_INVALID,
    PAIRING_LOCKED,
    PAIRING_DEVICE_LIMIT,
    ATTENTION_RESOLVED,
    DSH_UNAVAILABLE,
    DSH_ERROR,
    DSH_INCOMPATIBLE,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    BAD_REQUEST,
    DIRECTORY_INVALID,
    DIRECTORY_UNAVAILABLE,
    DIRECTORY_EXISTS,
    DIRECTORY_CREATE_FAILED,
    UNSUPPORTED_MEDIA_TYPE,
    PAYLOAD_TOO_LARGE,

    /** DSH 认为消息里的图片已损坏或格式不支持，消息没有发出（落地方案 C2）。 */
    IMAGE_INVALID,

    /** 当前模型看不了图片，消息没有发出（落地方案 C2）。 */
    IMAGES_NOT_SUPPORTED,
    INTERNAL,
    UNKNOWN,
    ;

    companion object {
        fun of(code: String?): ErrorCode = entries.firstOrNull { it != UNKNOWN && it.name == code } ?: UNKNOWN
    }
}

/** token 无效或已被撤销：停止重连，提示重新配对（protocol.md 第 3 节）。 */
val ApiException.isTokenInvalid: Boolean
    get() = this is ApiException.Http && (code == ErrorCode.TOKEN_INVALID || status == 401)
