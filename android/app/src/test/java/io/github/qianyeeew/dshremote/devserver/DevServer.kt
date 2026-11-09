package io.github.qianyeeew.dshremote.devserver

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.PairResult
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.net.Fingerprint
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * devServerTest 共用的工具：通过插件 dev-server 的命令行查询状态、生成配对码、撤销设备、重启 DSH。
 * 运行前先在 plugin/ 下启动 dev-server：`npm run dev-server`。
 */
internal object DevServer {
    private val pluginDir = File(System.getProperty("dshremote.pluginDir") ?: "../../plugin").canonicalFile

    class Status(
        val address: HostAddress,
        val pin: Fingerprint,
    )

    fun status(): Status {
        val status = Json.parseToJsonElement(run("status", "--json")).jsonObject
        check(status["running"]?.jsonPrimitive?.content == "true") {
            "dev-server 没有在运行。先在 plugin/ 下运行：npm run dev-server"
        }
        return Status(
            HostAddress("127.0.0.1", status.getValue("lanPort").jsonPrimitive.int),
            Fingerprint.parse(status.getValue("fingerprint").jsonPrimitive.content)!!,
        )
    }

    /** 生成一个新的配对码（只在内存中使用，不打印）。 */
    fun newPairingCode(): String =
        Json
            .parseToJsonElement(run("pair", "--json", "--no-qr", "--addr", "127.0.0.1"))
            .jsonObject
            .getValue("code")
            .jsonPrimitive
            .content

    /** 配对一台测试设备。 */
    suspend fun pair(
        status: Status,
        deviceName: String,
    ): PairResult = PluginApi(ClientFactory().forPin(status.pin), status.address, token = null).pair(newPairingCode(), deviceName)

    fun revoke(deviceId: String) {
        run("revoke", deviceId)
    }

    /** 重启 DSH（插件随之重启，局域网端口不变），等到 DSH 重新启动完成才返回。 */
    fun restartDsh() {
        run("restart")
    }

    /** 运行 dev-server 的命令行，返回标准输出的最后一行（其中可能有配对码，不打印）。 */
    private fun run(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("node", "scripts/dev-server.mjs") + args)
                .directory(pluginDir)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(150, TimeUnit.SECONDS) && process.exitValue() == 0) { "dev-server ${args.first()} 失败" }
        return output.trim().lines().last()
    }
}

internal suspend fun <T : Any> waitFor(
    what: String,
    attempts: Int = 100,
    check: suspend () -> T?,
): T {
    repeat(attempts) {
        check()?.let { return it }
        delay(200)
    }
    fail("等待超时：$what")
    throw IllegalStateException()
}

internal suspend inline fun <reified T : ApiException> expect(crossinline block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: ApiException) {
        if (e is T) return e
        throw AssertionError("期望 ${T::class.java.simpleName}，实际是 $e", e)
    }
    fail("期望 ${T::class.java.simpleName}，但没有抛出异常")
    throw IllegalStateException()
}
