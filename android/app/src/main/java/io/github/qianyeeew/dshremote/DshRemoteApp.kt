package io.github.qianyeeew.dshremote

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.data.live.ConnectionManager
import io.github.qianyeeew.dshremote.data.live.NetworkMonitor
import io.github.qianyeeew.dshremote.data.net.ClientFactory
import io.github.qianyeeew.dshremote.data.pairing.PairingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 创建 Application 作用域的对象（手动注入，android/AGENTS.md「技术选型」）。 */
class DshRemoteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Application 作用域的对象，每个进程只有一份：
 * - [hosts]：主机列表（DataStore 要求每个文件只有一个实例）；
 * - [connections]：每台主机一条实时连接，界面和前台服务共用（m2-android.md 3.3）；
 * - [pairing]：配对、重新配对和取消配对。
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clientFactory = ClientFactory()

    val hosts: HostRepository = HostRepository.create(appContext)

    val connections: ConnectionManager =
        ConnectionManager(
            hosts = hosts.hosts,
            scope = scope,
            clientFor = clientFactory::forPin,
            markGoodAddress = hosts::markGoodAddress,
            log = ::log,
        )

    val pairing: PairingService =
        PairingService(
            repository = hosts,
            clientFactory = clientFactory,
            deviceName = { deviceName(appContext) },
            log = ::log,
        )

    init {
        // 进程存活期间一直监听；网络变化时正在等待重连的主机立即重试
        NetworkMonitor(appContext, connections::onNetworkAvailable).register()
    }

    private companion object {
        const val TAG = "DSHRemote"

        /** 日志内容由调用方保证不含 token、配对码和消息内容。 */
        fun log(message: String) {
            Log.i(TAG, message)
        }

        /** 显示在电脑配对页上的手机名：系统设置中的设备名称，没有时用厂商和型号。 */
        fun deviceName(context: Context): String =
            Settings.Global
                .getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
