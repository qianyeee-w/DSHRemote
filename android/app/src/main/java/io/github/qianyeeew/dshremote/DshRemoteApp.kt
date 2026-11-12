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
import io.github.qianyeeew.dshremote.data.settings.SettingsRepository
import io.github.qianyeeew.dshremote.service.LinkController
import io.github.qianyeeew.dshremote.service.NotificationTarget
import io.github.qianyeeew.dshremote.service.VisibleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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
 * - [settings]：通知开关等设置；[linkController] 据此启停前台服务（T11）。
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

    val settings: SettingsRepository = SettingsRepository.create(appContext)

    /** 首页开始新任务时没有完成的步骤，交给会话页（只在内存里）。 */
    val handoffs =
        io.github.qianyeeew.dshremote.ui.chat
            .ChatHandoffs()

    /** App 的界面是否在前台（MainActivity 的 onStart 到 onStop）。 */
    val foreground = MutableStateFlow(false)

    /** 用户正在看的会话（会话详情页可见时），通知规则据此不打扰。 */
    val visibleSession = MutableStateFlow<VisibleSession?>(null)

    /** 点通知后要打开的页面，由导航取走。 */
    val pendingTarget = MutableStateFlow<NotificationTarget?>(null)

    /** 决定前台服务是否运行。 */
    val linkController: LinkController

    init {
        // 进程存活期间一直监听；网络变化时正在等待重连的主机立即重试
        NetworkMonitor(appContext, connections::onNetworkAvailable).register()
        linkController =
            LinkController(
                context = appContext,
                scope = scope,
                hasHosts = hosts.hosts.map { it.isNotEmpty() },
                notificationsEnabled = settings.settings.map { it.notificationsEnabled },
                foreground = foreground,
                log = ::log,
            )
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
