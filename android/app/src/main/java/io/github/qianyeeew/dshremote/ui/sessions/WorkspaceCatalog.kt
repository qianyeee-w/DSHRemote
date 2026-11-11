package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.live.OfflineException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceCatalogState(
    val workspaces: List<Workspace> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** 页面可见时更新项目名称与空项目；断线不清空上次成功的内容。 */
class WorkspaceCatalog(
    private val scope: CoroutineScope,
    private val creator: SessionCreator,
) {
    private val mutable = MutableStateFlow(WorkspaceCatalogState())
    val state = mutable.asStateFlow()
    private var request: Job? = null

    fun refresh() {
        if (request?.isActive == true) return
        mutable.value = mutable.value.copy(loading = true, failed = false)
        request =
            scope.launch {
                try {
                    mutable.value = WorkspaceCatalogState(creator.workspaces())
                } catch (_: OfflineException) {
                    mutable.value = mutable.value.copy(loading = false, failed = true)
                } catch (_: ApiException) {
                    mutable.value = mutable.value.copy(loading = false, failed = true)
                }
            }
    }
}
