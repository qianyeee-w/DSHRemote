package io.github.qianyeeew.dshremote.data.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * 网络变化时通知 [ConnectionManager] 立即重连（m2-android.md 3.3「重连」）。
 *
 * 监听系统的默认网络：切换 Wi-Fi、从移动数据回到 Wi-Fi、连上 VPN 时都会收到 [ConnectivityManager.NetworkCallback.onAvailable]。
 * 注册时系统会立即为当前网络回调一次，这时触发的重试没有副作用。由 Application 在创建 ConnectionManager 后注册（T7）。
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable()
        }

    fun register() = connectivity.registerDefaultNetworkCallback(callback)

    fun unregister() = connectivity.unregisterNetworkCallback(callback)
}
