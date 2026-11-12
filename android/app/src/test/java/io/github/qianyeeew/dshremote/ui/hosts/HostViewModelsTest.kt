package io.github.qianyeeew.dshremote.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.qianyeeew.dshremote.data.host.Host
import io.github.qianyeeew.dshremote.data.host.HostAddress
import io.github.qianyeeew.dshremote.data.host.HostRepository
import io.github.qianyeeew.dshremote.data.host.testHostRepository
import io.github.qianyeeew.dshremote.data.live.HostLiveState
import io.github.qianyeeew.dshremote.data.live.LinkStatus
import io.github.qianyeeew.dshremote.data.live.OfflineReason
import io.github.qianyeeew.dshremote.data.live.PairingReason
import io.github.qianyeeew.dshremote.ui.FakePairer
import io.github.qianyeeew.dshremote.ui.FakePairer.Companion.host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 主机列表和主机管理的 ViewModel。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostViewModelsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = MutableStateFlow<Map<String, HostLiveState>>(emptyMap())
    private val pairer = FakePairer()
    private val a = HostAddress("192.168.1.5", 3190)
    private val b = HostAddress("my-pc.example.ts.net", 3190)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    /** 测试里建的视图模型。收尾时先取消它们和 [scope]，再复位 Main：主机列表来自 IO 线程上的 DataStore，
     * 复位之后还收到数据会访问已经不存在的 Main，异常会算到后面的测试头上。 */
    private val viewModels = mutableListOf<ViewModel>()

    private fun <T : ViewModel> track(vm: T): T = vm.also { viewModels += it }

    @After
    fun tearDown() {
        // 取消是异步完成的：IO 上的子协程结束后还要回到 Main 收尾，所以等它们真正结束再复位
        runBlocking {
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun listShowsStatusAndAddressOfEachHost() =
        runBlocking<Unit> {
            val hosts =
                MutableStateFlow(
                    listOf(
                        host("h1", "在线的", listOf(a, b)),
                        host("h2", "离线的", listOf(b)),
                        host("h3", "凭据丢失", listOf(a), credentials = null),
                        host("h4", "还没有状态", listOf(a)),
                    ),
                )
            states.value =
                mapOf(
                    "h1" to HostLiveState(LinkStatus.Online(b)),
                    "h2" to HostLiveState(LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000)),
                    // 连接还在运行中也以「凭据丢失」为准
                    "h3" to HostLiveState(LinkStatus.Connecting),
                )
            val vm = track(HostListViewModel(hosts, states))
            val rows = withTimeout(5_000) { vm.rows.first { it != null } }!!
            assertEquals(listOf("h1", "h2", "h3", "h4"), rows.map { it.hostId })
            assertEquals(HostRow("h1", "在线的", b, LinkStatus.Online(b)), rows[0])
            assertEquals(LinkStatus.Offline(OfflineReason.UNREACHABLE, 1_000), rows[1].status)
            assertEquals(LinkStatus.NeedsPairing(PairingReason.CREDENTIALS_LOST), rows[2].status)
            assertEquals(LinkStatus.Stopped, rows[3].status)
            assertEquals(a, rows[3].address)
        }

    @Test
    fun renameRejectsBlankNames() =
        runBlocking<Unit> {
            val (_, vm, _) = detail()
            assertFalse(vm.rename(" \n\t"))
            assertTrue(vm.rename("  书房电脑 "))
            awaitHost(vm) { it.name == "书房电脑" }
        }

    @Test
    fun addressesCanBeAddedEditedAndRemovedWithoutRepairing() =
        runBlocking<Unit> {
            val (repo, vm, hostId) = detail()
            val credentials = repo.get(hostId)!!.credentials

            assertEquals(AddressError.INVALID, vm.saveAddress(null, "192.168.1.5"))
            assertEquals(AddressError.INVALID, vm.saveAddress(null, "fd7a::1:3190"))
            assertEquals(AddressError.DUPLICATE, vm.saveAddress(null, " 192.168.1.5:3190 "))
            assertNull(vm.saveAddress(null, "my-pc.example.ts.net:3190"))
            awaitHost(vm) { it.addresses == listOf(a, b) }

            assertNull(vm.saveAddress(0, "[fd7a::1]:3190"))
            awaitHost(vm) { it.addresses == listOf(HostAddress("fd7a::1", 3190), b) }
            // 把第 0 个改成和第 1 个一样：重复
            assertEquals(AddressError.DUPLICATE, vm.saveAddress(0, "my-pc.example.ts.net:3190"))

            vm.removeAddress(0)
            awaitHost(vm) { it.addresses == listOf(b) }
            // 只剩一个时不能删除
            vm.removeAddress(0)
            kotlinx.coroutines.delay(200)
            assertEquals(listOf(b), repo.get(hostId)!!.addresses)
            // 地址变了，凭据不变（不需要重新配对）
            assertEquals(credentials, repo.get(hostId)!!.credentials)
        }

    @Test
    fun tooManyAddresses() =
        runBlocking<Unit> {
            val (_, vm, _) = detail()
            for (i in 2..HostRepository.MAX_ADDRESSES) {
                assertNull(vm.saveAddress(null, "10.0.0.$i:3190"))
                awaitHost(vm) { it.addresses.size == i }
            }
            assertEquals(AddressError.TOO_MANY, vm.saveAddress(null, "10.0.1.1:3190"))
        }

    @Test
    fun removeUnpairsAndReportsWhetherComputerConfirmed() =
        runBlocking<Unit> {
            val (_, vm, hostId) = detail()
            pairer.unpairResult = false
            vm.remove()
            vm.remove()
            assertEquals(Removal.Done(revokedOnComputer = false), withTimeout(5_000) { vm.removal.first { it is Removal.Done } })
            assertEquals(listOf(hostId), pairer.unpaired.map { it.hostId })
        }

    @Test
    fun retryGoesToConnectionManager() =
        runBlocking<Unit> {
            val retried = mutableListOf<String>()
            val (_, vm, hostId) = detail(retry = { retried += it })
            vm.retryNow()
            assertEquals(listOf(hostId), retried)
        }

    @Test
    fun detailFollowsLiveStatus() =
        runBlocking<Unit> {
            val (_, vm, hostId) = detail()
            states.value = mapOf(hostId to HostLiveState(LinkStatus.FingerprintMismatch))
            assertEquals(
                LinkStatus.FingerprintMismatch,
                withTimeout(5_000) {
                    vm.detail.first { it?.status == LinkStatus.FingerprintMismatch }
                }!!.status,
            )
        }

    private suspend fun detail(retry: (String) -> Unit = {}): Triple<HostRepository, HostDetailViewModel, String> {
        val repo = testHostRepository(File(temp.root, "hosts.json"), scope)
        val added = repo.add("MY-PC", a, "d_1", host().credentials!!)
        val vm = track(HostDetailViewModel(added.hostId, repo.hosts, states, repo, pairer, retry))
        withTimeout(5_000) { vm.detail.first { it != null } }
        return Triple(repo, vm, added.hostId)
    }

    /** 等 ViewModel 看到修改后的主机（它根据自己看到的列表计算下一次修改）。 */
    private suspend fun awaitHost(
        vm: HostDetailViewModel,
        check: (Host) -> Boolean,
    ) {
        withTimeout(5_000) { vm.detail.first { it != null && check(it.host) } }
    }
}
