package io.github.qianyeeew.dshremote.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qianyeeew.dshremote.data.api.DirectoryEntry
import io.github.qianyeeew.dshremote.data.api.DirectoryListing
import io.github.qianyeeew.dshremote.data.api.Workspace
import io.github.qianyeeew.dshremote.ui.sessions.ProjectError
import io.github.qianyeeew.dshremote.ui.sessions.ProjectMode
import io.github.qianyeeew.dshremote.ui.sessions.ProjectPickerContent
import io.github.qianyeeew.dshremote.ui.sessions.ProjectState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPickerUiTest {
    @get:Rule
    val compose = createComposeRule()
    private val listing = DirectoryListing("E:/项目", "C:/Users/test", "E:/", listOf(DirectoryEntry("已有项目", "E:/项目/app", false)), false)

    @Test
    fun directorySelectionUsesComputerPathAndEditingPathRequiresNavigation() {
        var browsed: String? = null
        compose.setContent {
            DshRemoteTheme {
                ProjectPickerContent(ProjectState(ProjectMode.IMPORT, listing), "测试电脑", { browsed = it }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("测试电脑").assertExists()
        compose.onNodeWithText("已有项目").performClick()
        assertEquals("E:/项目/app", browsed)
        compose.onNodeWithText("电脑上的目录路径").performTextReplacement("E:/其他")
        compose.onNodeWithText("导入当前目录").assertIsNotEnabled()
        compose.onNodeWithText("转到").performClick()
        assertEquals("E:/其他", browsed)
    }

    @Test
    fun unknownCreationResultDisablesAnotherWrite() {
        compose.setContent {
            DshRemoteTheme {
                ProjectPickerContent(ProjectState(ProjectMode.CREATE, listing, "app", uncertainCreation = true), "电脑", {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("在此目录创建项目").assertIsNotEnabled()
    }

    @Test
    fun knownPartialSuccessOffersOnlyRegistrationRetry() {
        var retries = 0
        compose.setContent {
            DshRemoteTheme {
                ProjectPickerContent(
                    ProjectState(
                        ProjectMode.CREATE,
                        listing,
                        registrationPath = "E:/项目/new",
                        directoryCreated = true,
                        error = ProjectError.FAILED,
                    ),
                    "电脑",
                    {},
                    {},
                    { retries++ },
                    {},
                    {},
                )
            }
        }
        compose.onNodeWithText("重试登记工作区").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("新项目目录名称").assertDoesNotExist()
    }

    @Test
    fun registeredWorkspaceWaitsForExplicitStartSession() {
        var started = 0
        compose.setContent {
            DshRemoteTheme {
                ProjectPickerContent(
                    ProjectState(ProjectMode.IMPORT, listing, workspace = Workspace("w1", "项目", "E:/项目")),
                    "电脑",
                    {},
                    {},
                    {},
                    {},
                    { started++ },
                )
            }
        }
        assertEquals(0, started)
        compose.onNodeWithText("开始会话").performClick()
        assertEquals(1, started)
    }
}
