package io.github.qianyeeew.dshremote.ui.sessions

import io.github.qianyeeew.dshremote.data.api.ApiException
import io.github.qianyeeew.dshremote.data.api.DirectoryListing
import io.github.qianyeeew.dshremote.data.api.ErrorCode
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.data.api.WorkspaceRegistration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val backend = FakeProjects()
    private val flow = ProjectFlow(scope, backend)

    @After
    fun close() = scope.cancel()

    @Test
    fun importRegistersChosenDirectoryWithoutMkdirAndWaitsForStartSession() {
        flow.open(ProjectMode.IMPORT)
        flow.browse("/work/existing")
        flow.submit()
        assertEquals(listOf("/work/existing"), backend.registered)
        assertEquals(0, backend.created.size)
        assertEquals(
            "w1",
            flow.state.value
                ?.workspace
                ?.workspaceId,
        )
        flow.submit()
        assertEquals(1, backend.registered.size)
    }

    @Test
    fun createThenRegistrationFailureRetriesOnlyRegistration() {
        backend.registerError = ApiException.Http(503, ErrorCode.DSH_UNAVAILABLE, null)
        flow.open(ProjectMode.CREATE)
        flow.name("中文项目")
        flow.submit()
        assertTrue(flow.state.value!!.directoryCreated)
        assertEquals("/work/中文项目", flow.state.value?.registrationPath)
        backend.registerError = null
        flow.submit()
        assertEquals(listOf("中文项目"), backend.created)
        assertEquals(listOf("/work/中文项目", "/work/中文项目"), backend.registered)
        assertEquals(
            "w1",
            flow.state.value
                ?.workspace
                ?.workspaceId,
        )
    }

    @Test
    fun lostCreationReceiptNeverAutomaticallyRepeatsMkdirOrRegisters() {
        backend.createError = ApiException.Network(IOException())
        flow.open(ProjectMode.CREATE)
        flow.name("app")
        flow.submit()
        assertTrue(flow.state.value!!.uncertainCreation)
        flow.submit()
        assertEquals(1, backend.created.size)
        assertEquals(0, backend.registered.size)
        flow.browse("/work")
        assertFalse(flow.state.value!!.uncertainCreation)
    }

    @Test
    fun duplicateClicksAndDismissWhileWritingDoNotDuplicateRequests() {
        backend.gate = CompletableDeferred()
        flow.open(ProjectMode.CREATE)
        flow.name("app")
        flow.submit()
        assertTrue(flow.state.value!!.writing)
        flow.submit()
        flow.dismiss()
        flow.browse("/elsewhere")
        assertTrue(flow.state.value!!.writing)
        backend.gate!!.complete(Unit)
        assertEquals(listOf("app"), backend.created)
        assertEquals(1, backend.registered.size)
    }

    @Test
    fun existingDirectoryIsNotSilentlyImportedAndNameCanBeCorrected() {
        backend.createError = ApiException.Http(409, ErrorCode.DIRECTORY_EXISTS, null)
        flow.open(ProjectMode.CREATE)
        flow.name("app")
        flow.submit()
        assertEquals(ProjectError.EXISTS, flow.state.value?.error)
        assertFalse(flow.state.value!!.uncertainCreation)
        assertEquals(0, backend.registered.size)
        flow.name("another")
        backend.createError = null
        flow.submit()
        assertEquals(listOf("/work/another"), backend.registered)
    }

    @Test
    fun oldPluginRevocationAndFingerprintErrorsAreExplicit() {
        for ((exception, expected) in listOf(
            ApiException.Http(404, ErrorCode.NOT_FOUND, null) to ProjectError.UPGRADE,
            ApiException.Http(401, ErrorCode.TOKEN_INVALID, null) to ProjectError.NEEDS_PAIRING,
            ApiException.FingerprintMismatch(IOException()) to ProjectError.FINGERPRINT,
        )) {
            backend.listError = exception
            flow.open(ProjectMode.IMPORT)
            assertEquals(expected, flow.state.value?.error)
            flow.submit()
            assertTrue(backend.registered.isEmpty())
            flow.dismiss()
        }
    }

    @Test
    fun closingWhileLoadingCancelsReadAndDoesNotReopenTheDialog() {
        backend.listGate = CompletableDeferred()
        flow.open(ProjectMode.IMPORT)
        flow.dismiss()
        backend.listGate!!.complete(Unit)
        assertNull(flow.state.value)
    }

    @Test
    fun failedNavigationCannotAccidentallyCreateInThePreviousDirectory() {
        flow.open(ProjectMode.CREATE)
        backend.listError = ApiException.Http(400, ErrorCode.DIRECTORY_INVALID, null)
        flow.browse("relative")
        flow.name("new-name")
        flow.submit()
        assertTrue(flow.state.value!!.listingFailed)
        assertEquals(ProjectError.INVALID, flow.state.value?.error)
        assertTrue(backend.created.isEmpty())
    }

    private class FakeProjects : ProjectBackend {
        val created = mutableListOf<String>()
        val registered = mutableListOf<String>()
        var listError: ApiException? = null
        var createError: ApiException? = null
        var registerError: ApiException? = null
        var gate: CompletableDeferred<Unit>? = null
        var listGate: CompletableDeferred<Unit>? = null

        override suspend fun directories(path: String?): DirectoryListing {
            listGate?.await()
            listError?.let { throw it }
            return DirectoryListing(path ?: "/work", "/work", "/", emptyList(), false)
        }

        override suspend fun createDirectory(
            path: String,
            name: String,
        ): String {
            created += name
            gate?.await()
            createError?.let { throw it }
            return "$path/$name"
        }

        override suspend fun registerWorkspace(path: String): WorkspaceRegistration {
            registered += path
            registerError?.let { throw it }
            return WorkspaceRegistration(Workspace("w1", "项目", path), false)
        }
    }
}
