package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.DirectoryListing
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.PluginApi
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.api.WorkspaceRegistration
import io.github.qianyeeew.dshremote.data.api.isTokenInvalid
import io.github.qianyeeew.dshremote.data.live.OfflineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ProjectMode { CREATE, IMPORT }

enum class ProjectError { OFFLINE, NEEDS_PAIRING, FINGERPRINT, UPGRADE, INVALID, UNAVAILABLE, EXISTS, FAILED }

data class ProjectState(
    val mode: ProjectMode,
    val listing: DirectoryListing? = null,
    val name: String = "",
    val loading: Boolean = false,
    val listingFailed: Boolean = false,
    val writing: Boolean = false,
    val error: ProjectError? = null,
    /** 已知创建成功或准备导入的目录；重试只登记这个路径。 */
    val registrationPath: String? = null,
    val directoryCreated: Boolean = false,
    val uncertainCreation: Boolean = false,
    val workspace: Workspace? = null,
)

interface ProjectBackend {
    suspend fun directories(path: String?): DirectoryListing

    suspend fun createDirectory(
        path: String,
        name: String,
    ): String

    suspend fun registerWorkspace(path: String): WorkspaceRegistration
}

class PluginProjectBackend(
    private val api: () -> PluginApi?,
) : ProjectBackend {
    private fun connected() = api() ?: throw OfflineException()

    override suspend fun directories(path: String?) = connected().directories(path)

    override suspend fun createDirectory(
        path: String,
        name: String,
    ) = connected().createDirectory(path, name)

    override suspend fun registerWorkspace(path: String) = connected().registerWorkspace(path)
}

/** 由会话页 ViewModel 持有。读操作可取消；写入不因重复点击或关闭而重复执行。 */
class ProjectFlow(
    private val scope: CoroutineScope,
    private val backend: ProjectBackend,
) {
    private val mutableState = MutableStateFlow<ProjectState?>(null)
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null

    fun open(mode: ProjectMode) {
        if (mutableState.value != null) return
        mutableState.value = ProjectState(mode)
        browse(null)
    }

    fun dismiss() {
        if (mutableState.value?.writing == true) return
        readJob?.cancel()
        mutableState.value = null
    }

    fun name(value: String) {
        val current = mutableState.value ?: return
        if (!current.writing) {
            mutableState.value =
                current.copy(
                    name = value,
                    error =
                        if (!current.listingFailed &&
                            current.error in setOf(ProjectError.INVALID, ProjectError.EXISTS)
                        ) {
                            null
                        } else {
                            current.error
                        },
                )
        }
    }

    fun browse(path: String?) {
        val current = mutableState.value ?: return
        if (current.writing) return
        readJob?.cancel()
        val next =
            current.copy(
                loading = true,
                listingFailed = false,
                error = null,
                registrationPath = null,
                directoryCreated = false,
                uncertainCreation = false,
            )
        mutableState.value = next
        readJob =
            scope.launch {
                try {
                    mutableState.value = next.copy(listing = backend.directories(path), loading = false)
                } catch (e: ApiException) {
                    mutableState.value = next.copy(loading = false, listingFailed = true, error = projectError(e))
                } catch (_: OfflineException) {
                    mutableState.value = next.copy(loading = false, listingFailed = true, error = ProjectError.OFFLINE)
                }
            }
    }

    fun submit() {
        val current = mutableState.value ?: return
        val listing = current.listing ?: return
        if (current.loading || current.listingFailed || current.writing || current.workspace != null || current.uncertainCreation) return
        if (current.error != null && current.registrationPath == null) return
        if (current.mode == ProjectMode.CREATE && current.registrationPath == null && current.name.isBlank()) return
        mutableState.value = current.copy(writing = true, error = null)
        scope.launch {
            var path = current.registrationPath
            var created = current.directoryCreated
            var creating = false
            try {
                if (path == null) {
                    if (current.mode == ProjectMode.CREATE) {
                        creating = true
                        path = backend.createDirectory(listing.path, current.name)
                        creating = false
                        created = true
                    } else {
                        path = listing.path
                    }
                }
                mutableState.value = current.copy(writing = true, registrationPath = path, directoryCreated = created)
                val result = backend.registerWorkspace(path)
                mutableState.value = current.copy(workspace = result.workspace, registrationPath = path, directoryCreated = created)
            } catch (e: ApiException) {
                // 只有明确的拒绝才能判断没有创建；网络／无法解析／服务端异常均可能已落盘。
                val definitelyRejected =
                    e is ApiException.Http &&
                        e.code in
                        setOf(
                            ErrorCode.DIRECTORY_INVALID,
                            ErrorCode.DIRECTORY_EXISTS,
                            ErrorCode.DIRECTORY_UNAVAILABLE,
                            ErrorCode.TOKEN_INVALID,
                            ErrorCode.NOT_FOUND,
                            ErrorCode.DSH_INCOMPATIBLE,
                        ) ||
                        e is ApiException.FingerprintMismatch
                mutableState.value =
                    current.copy(
                        error = projectError(e),
                        registrationPath = path,
                        directoryCreated = created,
                        uncertainCreation = creating && !definitelyRejected,
                    )
            } catch (_: OfflineException) {
                mutableState.value = current.copy(error = ProjectError.OFFLINE, registrationPath = path, directoryCreated = created)
            }
        }
    }
}

private fun projectError(e: ApiException): ProjectError =
    when {
        e.isTokenInvalid -> ProjectError.NEEDS_PAIRING
        e is ApiException.FingerprintMismatch -> ProjectError.FINGERPRINT
        e is ApiException.Network -> ProjectError.OFFLINE
        e is ApiException.Http ->
            when (e.code) {
                ErrorCode.NOT_FOUND, ErrorCode.METHOD_NOT_ALLOWED -> ProjectError.UPGRADE
                ErrorCode.DIRECTORY_INVALID, ErrorCode.BAD_REQUEST -> ProjectError.INVALID
                ErrorCode.DIRECTORY_UNAVAILABLE -> ProjectError.UNAVAILABLE
                ErrorCode.DIRECTORY_EXISTS -> ProjectError.EXISTS
                else -> ProjectError.FAILED
            }
        else -> ProjectError.FAILED
    }
