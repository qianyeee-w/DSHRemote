package io.github.qianyeeew.dshremote.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 首页要记的两项：上次用的电脑、每台电脑的项目筛选。 */
class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun lastHostAndPerHostProjectFilter() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { folder.newFile("settings.preferences_pb").also { it.delete() } }
            val settings = SettingsRepository(store)
            assertNull(settings.settings.first().lastHostId)
            settings.setLastHost("host-2")
            assertEquals("host-2", settings.settings.first().lastHostId)

            assertNull(settings.projectFilter("host-1").first())
            settings.setProjectFilter("host-1", "w1")
            settings.setProjectFilter("host-2", "cwd:/tmp")
            assertEquals("w1", settings.projectFilter("host-1").first())
            assertEquals("cwd:/tmp", settings.projectFilter("host-2").first())
            // 回到「全部项目」时删掉这一项
            settings.setProjectFilter("host-1", null)
            assertNull(settings.projectFilter("host-1").first())
            assertEquals("cwd:/tmp", settings.projectFilter("host-2").first())
        }
}
