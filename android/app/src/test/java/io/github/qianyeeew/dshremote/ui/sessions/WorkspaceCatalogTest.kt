package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.live.OfflineException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCatalogTest {
    @Test fun failedRefreshRetainsLastSuccessfulProjectsAndCanRetry() =
        runTest {
            var offline = false
            val projects = listOf(Workspace("p1", "工程", "E:/work/app"))
            val catalog = WorkspaceCatalog(backgroundScope) { if (offline) throw OfflineException() else projects }
            catalog.refresh()
            runCurrent()
            assertEquals(projects, catalog.state.value.workspaces)
            offline = true
            catalog.refresh()
            runCurrent()
            assertEquals(projects, catalog.state.value.workspaces)
            assertTrue(catalog.state.value.failed)
            assertFalse(catalog.state.value.loading)
            offline = false
            catalog.refresh()
            runCurrent()
            assertFalse(catalog.state.value.failed)
        }

    @Test fun repeatedRefreshWhileLoadingUsesOneRequest() =
        runTest {
            var calls = 0
            val result = CompletableDeferred<List<Workspace>>()
            val catalog =
                WorkspaceCatalog(backgroundScope) {
                    calls++
                    result.await()
                }
            catalog.refresh()
            catalog.refresh()
            runCurrent()
            assertEquals(1, calls)
            result.complete(emptyList())
            runCurrent()
            assertFalse(catalog.state.value.loading)
        }
}
