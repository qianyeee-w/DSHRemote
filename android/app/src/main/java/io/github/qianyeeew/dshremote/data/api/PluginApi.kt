package io.github.qianyeeew.dshremote.data.api

import io.github.qianyeeew.dshremote.BuildConfig
import io.github.qianyeeew.dshremote.data.host.DeviceToken
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.host.HostNames
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.isFingerprintMismatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 插件的 REST 接口（protocol.md 第 2、4 节）。App 只调用这里列出的接口。
 *
 * - [client] 必须来自 [ClientFactory.forPin]（证书指纹校验）。
 * - [token] 为 null 时只能调用无需认证的 [info] 和 [pair]。
 * - 失败时抛出 [ApiException]；异常信息中不包含 token 和请求内容。
 * - 请求头只有 `Accept`、`Authorization`、`Content-Type`，不发送 `Origin`。
 */
class PluginApi(
    private val client: OkHttpClient,
    address: HostAddress,
    private val token: DeviceToken?,
) {
    private val base: HttpUrl =
        HttpUrl
            .Builder()
            .scheme("https")
            .host(address.host)
            .port(address.port)
            .build()

    // ---------------------------------------------------------------- 无需认证

    /** `GET /v1/info`：在线状态和版本检查，同时用响应的 `Date` 头估算两边时钟的差（[PluginInfo.clockOffsetMillis]）。 */
    suspend fun info(): PluginInfo {
        var offset: Long? = null
        val info =
            send("GET", listOf("info"), auth = false, onResponse = { response ->
                offset =
                    response.headers
                        .getDate("Date")
                        ?.time
                        ?.let { it - System.currentTimeMillis() }
            }) { decode(PluginInfo.serializer(), it) }
        return info.copy(clockOffsetMillis = offset)
    }

    /**
     * `POST /v1/pair`：用配对码换取设备 token（protocol.md 2.4）。
     * 配对码原样发送（插件忽略大小写、空格和连字符）。[deviceName] 按电脑名的规则清理，清理后为空时用「Android」。
     */
    suspend fun pair(
        code: String,
        deviceName: String,
        appVersion: String = BuildConfig.VERSION_NAME,
    ): PairResult {
        val request = PairRequest(code, HostNames.clean(deviceName).ifEmpty { "Android" }, PLATFORM, appVersion)
        val response =
            send("POST", listOf("pair"), body = encode(PairRequest.serializer(), request), auth = false) {
                decode(PairResponse.serializer(), it)
            }
        val token = DeviceToken.parse(response.token) ?: throw ApiException.BadResponse("token 格式不对")
        return PairResult(response.deviceId, token, response.host.name, response.host.pluginVersion)
    }

    // ---------------------------------------------------------------- 设备

    suspend fun me(): DeviceInfo = get(listOf("me"), DeviceInfo.serializer())

    /** `DELETE /v1/me`：取消配对，之后 token 立即失效。 */
    suspend fun deleteMe() {
        send("DELETE", listOf("me")) { }
    }

    // ---------------------------------------------------------------- 工作区和会话

    suspend fun workspaces(): List<Workspace> = get(listOf("workspaces"), WorkspaceList.serializer()).workspaces

    suspend fun directories(path: String? = null): DirectoryListing =
        get(listOf("directories"), DirectoryListing.serializer(), path?.let { mapOf("path" to it) } ?: emptyMap())

    suspend fun createDirectory(
        path: String,
        name: String,
    ): String {
        val body = encode(CreateDirectoryRequest.serializer(), CreateDirectoryRequest(path, name)).oneShot()
        return send("POST", listOf("directories"), body = body) { decode(DirectoryPath.serializer(), it) }.path
    }

    suspend fun registerWorkspace(path: String): WorkspaceRegistration {
        val body = encode(DirectoryPath.serializer(), DirectoryPath(path))
        return send("POST", listOf("workspaces"), body = body) { decode(WorkspaceRegistration.serializer(), it) }
    }

    suspend fun sessions(): List<SessionSummary> = get(listOf("sessions"), SessionList.serializer()).sessions

    /** 新建会话，返回 sessionId。[workspaceId] 为 null 时不归入任何工作区。 */
    suspend fun createSession(workspaceId: String? = null): String {
        workspaceId?.let(::requireId)
        val body = encode(CreateSessionRequest.serializer(), CreateSessionRequest(workspaceId))
        return send("POST", listOf("sessions"), body = body) { decode(CreateSessionResponse.serializer(), it) }.sessionId
    }

    /**
     * 历史消息，从最新的一页开始。[before] 传上一页的 [MessagePage.cursor] 取更早的一页。
     * 是否还有更早的消息只看 [MessagePage.hasMore]，`messages` 为空不代表翻到底了。
     */
    suspend fun messages(
        sessionId: String,
        before: String? = null,
        limit: Int? = null,
    ): MessagePage {
        requireId(sessionId)
        limit?.let { require(it in 1..MAX_PAGE_LIMIT) { "limit 超出范围" } }
        val query =
            buildMap {
                before?.let { put("before", it) }
                limit?.let { put("limit", it.toString()) }
            }
        return get(listOf("sessions", sessionId, "messages"), MessagePage.serializer(), query)
    }

    /**
     * 发消息。网络失败后重试时**传入同一个 [OutgoingMessage]**：它的 requestId 不变，
     * 插件在 10 分钟内只会把它发出一次（protocol.md 4「发消息」）。
     */
    suspend fun prompt(
        sessionId: String,
        message: OutgoingMessage,
    ) {
        requireId(sessionId)
        // 请求体开始写出之前就失败（连不上、握手失败），插件肯定没收到；之后失败就说不准（落地方案 B3）
        val written = AtomicBoolean(false)
        val body = PromptBody(message).tracked(written)
        try {
            send("POST", listOf("sessions", sessionId, "prompt"), body = body) { decode(Accepted.serializer(), it) }
        } catch (e: ApiException.Network) {
            throw ApiException.Network(e.cause as IOException, maybeDelivered = written.get())
        }
    }

    /**
     * 从第 [beforeTurn] 轮之前另开一个对话（protocol.md 第 4 节「另开对话」，落地方案 C1）。
     * 回执丢失后重试时传同一个 [requestId]：插件 10 分钟内按它去重，不会分出两个会话。
     */
    suspend fun fork(
        sessionId: String,
        requestId: String,
        beforeTurn: Int,
    ): ForkResult {
        requireId(sessionId)
        val body = encode(ForkRequest.serializer(), ForkRequest(requestId, beforeTurn))
        return send("POST", listOf("sessions", sessionId, "fork"), body = body) { decode(ForkResult.serializer(), it) }
    }

    /**
     * 读这个会话里的一张图片（protocol.md 第 4 节「图片」），返回图片文件的字节。图片不属于这个会话、
     * 或者旧插件时 `404`。响应体超过 [MAX_IMAGE_RESPONSE_BYTES] 时视为插件返回了无法处理的内容。
     */
    suspend fun image(
        sessionId: String,
        imageId: String,
    ): ByteArray {
        requireId(sessionId)
        require(IMAGE_ID.matches(imageId)) { "图片 id 格式不对" }
        return exchange("GET", listOf("sessions", sessionId, "images", imageId), accept = "image/*") { response ->
            val declared = response.body.contentLength()
            if (declared > MAX_IMAGE_RESPONSE_BYTES) throw ApiException.BadResponse("图片太大")
            val bytes =
                try {
                    response.body.source().use { source ->
                        // 先确认不超过上限再一次读完（request 返回 false 表示已经读到结尾）
                        if (source.request(MAX_IMAGE_RESPONSE_BYTES + 1)) throw ApiException.BadResponse("图片太大")
                        source.readByteArray()
                    }
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                }
            bytes
        }
    }

    /** 停止当前任务。会话空闲时也成功。 */
    suspend fun cancel(sessionId: String) {
        requireId(sessionId)
        send("POST", listOf("sessions", sessionId, "cancel"), body = EMPTY_BODY) { decode(Accepted.serializer(), it) }
    }

    /** 计划（protocol.md 第 4 节「计划」）。旧插件没有这个接口，返回 `404`。 */
    suspend fun plan(sessionId: String): SessionPlan {
        requireId(sessionId)
        return get(listOf("sessions", sessionId, "plan"), SessionPlan.serializer())
    }

    /** 用量与上下文（protocol.md 第 4 节「用量」）。旧插件没有这个接口，返回 `404`。 */
    suspend fun usage(sessionId: String): SessionUsage {
        requireId(sessionId)
        return get(listOf("sessions", sessionId, "usage"), SessionUsage.serializer())
    }

    // ---------------------------------------------------------------- 模型和权限模式

    suspend fun models(): ModelList = get(listOf("models"), ModelList.serializer())

    suspend fun setModel(
        sessionId: String,
        model: ModelRef,
    ): ModelRef {
        requireId(sessionId)
        val body = encode(ModelRef.serializer(), model)
        return send("POST", listOf("sessions", sessionId, "model"), body = body) { decode(SetModelResponse.serializer(), it) }.model
    }

    suspend fun permissionPresets(): PermissionPresetList = get(listOf("permission-presets"), PermissionPresetList.serializer())

    suspend fun setPermissionPreset(
        sessionId: String,
        preset: String,
    ): String {
        requireId(sessionId)
        val body = encode(SetPresetRequest.serializer(), SetPresetRequest(preset))
        return send("POST", listOf("sessions", sessionId, "permission-preset"), body = body) {
            decode(SetPresetResponse.serializer(), it)
        }.permissionPreset
    }

    // ---------------------------------------------------------------- 待处理事项

    /**
     * 处理审批或回答提问（protocol.md 5.3）。已经在别处处理掉时抛出 `409 ATTENTION_RESOLVED`。
     * 插件会多等约 0.3 秒确认没有被别处抢先，所以这个接口比其他接口稍慢。
     */
    suspend fun answer(
        attentionId: String,
        answer: AttentionAnswer,
    ) {
        requireId(attentionId)
        val body =
            when (answer) {
                is AttentionAnswer.Decision ->
                    encode(DecisionRequest.serializer(), DecisionRequest(if (answer.allow) "allow" else "reject"))
                is AttentionAnswer.Answers -> encode(AnswersRequest.serializer(), AnswersRequest(answer.answers))
            }
        send("POST", listOf("attention", attentionId, "answer"), body = body) { decode(AnswerResponse.serializer(), it) }
    }

    // ---------------------------------------------------------------- 实现

    private suspend fun <T> get(
        path: List<String>,
        serializer: KSerializer<T>,
        query: Map<String, String> = emptyMap(),
        auth: Boolean = true,
    ): T = send("GET", path, query = query, auth = auth) { decode(serializer, it) }

    private suspend fun <T> send(
        method: String,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        auth: Boolean = true,
        /** 成功的响应到达时调用（读取响应头）。 */
        onResponse: (Response) -> Unit = {},
        parse: (String) -> T,
    ): T =
        exchange(method, path, query, body, auth) {
            val text =
                try {
                    it.body.string()
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                }
            onResponse(it)
            parse(text)
        }

    /** 发出请求；失败的响应转成 [ApiException.Http]，成功时交给 [onSuccess] 读取（在 IO 线程上）。 */
    private suspend fun <T> exchange(
        method: String,
        path: List<String>,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        auth: Boolean = true,
        accept: String = "application/json",
        onSuccess: (Response) -> T,
    ): T {
        val url =
            base
                .newBuilder()
                .addPathSegment("v1")
                .apply {
                    path.forEach(::addPathSegment)
                    query.forEach { (k, v) -> addQueryParameter(k, v) }
                }.build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", accept)
                .apply {
                    if (auth) {
                        val bearer = checkNotNull(token) { "没有 token，只能调用无需认证的接口" }
                        header("Authorization", "Bearer ${bearer.value}")
                    }
                }.method(method, body)
                .build()
        return withContext(Dispatchers.IO) {
            val response =
                try {
                    client.newCall(request).await()
                } catch (e: IOException) {
                    throw if (e.isFingerprintMismatch()) ApiException.FingerprintMismatch(e) else ApiException.Network(e)
                }
            response.use {
                if (!it.isSuccessful) {
                    val text =
                        try {
                            it.body.string()
                        } catch (e: IOException) {
                            throw ApiException.Network(e)
                        }
                    throw httpError(it, text)
                }
                onSuccess(it)
            }
        }
    }

    private fun httpError(
        response: Response,
        text: String,
    ): ApiException.Http {
        val error = runCatching { ApiJson.decodeFromString(ErrorEnvelope.serializer(), text).error }.getOrNull()
        return ApiException.Http(
            status = response.code,
            code = ErrorCode.of(error?.code),
            serverMessage = error?.message,
            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
        )
    }

    private fun <T> decode(
        serializer: KSerializer<T>,
        text: String,
    ): T =
        try {
            ApiJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw ApiException.BadResponse(serializer.descriptor.serialName.substringAfterLast('.'), e)
        } catch (e: IllegalArgumentException) {
            throw ApiException.BadResponse(serializer.descriptor.serialName.substringAfterLast('.'), e)
        }

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): RequestBody = ApiJson.encodeToString(serializer, value).toRequestBody(JSON_MEDIA_TYPE)

    companion object {
        const val PLATFORM = "android"

        /** 这个版本的 App 支持的协议版本（`/v1/info` 和 `hello` 的 `protocol`，protocol.md 第 8 节）。 */
        const val PROTOCOL = 1
        const val MAX_PAGE_LIMIT = 200
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val IMAGE_ID = Regex("^[0-9a-f]{64}$")

        /** 读一张图片最多接受多少字节：DSH 允许电脑上发的图片单张 50 MB。 */
        const val MAX_IMAGE_RESPONSE_BYTES = 50L * 1024 * 1024

        /** protocol.md 4：路径中的 id 只能由字母、数字、`_`、`-` 组成，最长 128 个字符。也防止 `..` 之类改变路径。 */
        private fun requireId(id: String) = require(ID.matches(id)) { "id 格式不对" }
    }
}

/** 配对成功的结果。[token] 的 toString 不输出 token。 */
data class PairResult(
    val deviceId: String,
    val token: DeviceToken,
    val hostName: String,
    val pluginVersion: String,
)

/**
 * 一条要发送的消息。[requestId] 在创建时生成，**重试时沿用同一个对象**，插件据此去重。
 */
class OutgoingMessage(
    val text: String,
    val mode: PromptMode,
    /** 附带的图片（落地方案 C2），已经在手机上压缩过。重试时一起重发同样的字节。 */
    val images: List<OutgoingImage> = emptyList(),
    val requestId: String = UUID.randomUUID().toString(),
) {
    init {
        require(text.isNotBlank() || images.isNotEmpty()) { "消息不能为空" }
        require(text.length <= MAX_TEXT) { "消息太长" }
        require(images.size <= MAX_IMAGES) { "图片太多" }
        require(Regex("^[A-Za-z0-9_-]{1,128}$").matches(requestId)) { "requestId 格式不对" }
    }

    /** 不输出消息内容（可能包含用户的隐私）。 */
    override fun toString(): String = "OutgoingMessage(requestId=$requestId, mode=$mode, length=${text.length}, images=${images.size})"

    companion object {
        const val MAX_TEXT = 100_000

        /** 每条消息最多几张图片（protocol.md 第 4 节「图片」）。 */
        const val MAX_IMAGES = 4
    }
}

/**
 * 一张要发出的图片：压缩好的 JPEG 或 PNG 文件（落地方案 C2）。[key] 在这台手机上唯一，界面和缩略图缓存用它区分。
 */
class OutgoingImage(
    val key: String,
    val mediaType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(mediaType == JPEG || mediaType == PNG) { "只能是 JPEG 或 PNG" }
        require(bytes.size <= MAX_BYTES) { "图片太大" }
    }

    /** 不输出图片内容。 */
    override fun toString(): String = "OutgoingImage($key, $mediaType, ${width}x$height, ${bytes.size} 字节)"

    companion object {
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"

        /** 单张最多多少字节（解码后，protocol.md 第 4 节「图片」）。 */
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}

/**
 * 发消息的请求体 `{ requestId, text, mode, images? }`。图片的 base64 边编码边写出，不在内存里拼成整个 JSON 字符串
 * （4 张大图编码后有几十 MB）。没有图片时不带 `images`。
 */
internal class PromptBody(
    private val message: OutgoingMessage,
) : RequestBody() {
    private val head: ByteString =
        buildString {
            append("{\"requestId\":").append(jsonString(message.requestId))
            append(",\"text\":").append(jsonString(message.text))
            append(",\"mode\":").append(ApiJson.encodeToString(PromptMode.serializer(), message.mode))
            if (message.images.isNotEmpty()) append(",\"images\":[")
        }.encodeUtf8()
    private val tail: ByteString = (if (message.images.isNotEmpty()) "]}" else "}").encodeUtf8()

    private fun imageHead(
        index: Int,
        image: OutgoingImage,
    ): ByteString = "${if (index > 0) "," else ""}{\"mediaType\":${jsonString(image.mediaType)},\"data\":\"".encodeUtf8()

    override fun contentType() = JSON_MEDIA_TYPE

    override fun contentLength(): Long =
        head.size.toLong() + tail.size +
            message.images.withIndex().sumOf { (i, image) -> imageHead(i, image).size + base64Length(image.bytes.size) + IMAGE_TAIL.size }

    override fun writeTo(sink: BufferedSink) {
        sink.write(head)
        val encoder = Base64.getEncoder()
        message.images.forEachIndexed { index, image ->
            sink.write(imageHead(index, image))
            var offset = 0
            while (offset < image.bytes.size) {
                // 每段是 3 的倍数个字节：中间各段编码后没有填充，拼起来就是整个文件的 base64
                val end = minOf(offset + CHUNK, image.bytes.size)
                sink.write(encoder.encode(image.bytes.copyOfRange(offset, end)))
                offset = end
            }
            sink.write(IMAGE_TAIL)
        }
        sink.write(tail)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val IMAGE_TAIL = "\"}".encodeUtf8()
        const val CHUNK = 3 * 16 * 1024

        fun jsonString(value: String): String = ApiJson.encodeToString(String.serializer(), value)

        fun base64Length(bytes: Int): Long = (bytes.toLong() + 2) / 3 * 4
    }
}

@Serializable
private class PairRequest(
    val code: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
) {
    override fun toString(): String = "PairRequest(code=***)"
}

@Serializable
private class PairResponse(
    val deviceId: String,
    val token: String,
    val host: PairedHost,
) {
    override fun toString(): String = "PairResponse(deviceId=$deviceId, token=***)"
}

@Serializable
private data class PairedHost(
    val name: String,
    val pluginVersion: String,
)

/** 记下请求体是否开始写出（[written]）。OkHttp 自己重试时会再写一次，记录不会变回 false。 */
private fun RequestBody.tracked(written: AtomicBoolean): RequestBody {
    val original = this
    return object : RequestBody() {
        override fun contentType() = original.contentType()

        override fun contentLength() = original.contentLength()

        override fun writeTo(sink: BufferedSink) {
            written.set(true)
            original.writeTo(sink)
        }
    }
}

/** 创建目录的回执丢失后交给界面确认，禁止 HTTP 层自行重发已经写出的请求体。 */
private fun RequestBody.oneShot(): RequestBody {
    val original = this
    return object : RequestBody() {
        override fun contentType() = original.contentType()

        override fun contentLength() = original.contentLength()

        override fun isOneShot() = true

        override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
    }
}

/** 在 OkHttp 的线程上执行请求；协程取消时取消请求。 */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }

@Serializable
private class ForkRequest(
    val requestId: String,
    val beforeTurn: Int,
)
