package io.github.qianyeeew.dshremote.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 首页要记的两项：上次用的电脑、最近列表的分组方式。 */
class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun lastHostAndHomeGrouping() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { folder.newFile("settings.preferences_pb").also { it.delete() } }
            val settings = SettingsRepository(store)
            assertNull(settings.settings.first().lastHostId)
            settings.setLastHost("host-2")
            assertEquals("host-2", settings.settings.first().lastHostId)

            // 首页的分组方式：所有电脑共用，没选过时为 null（按项目分组）
            assertNull(settings.homeGrouping.first())
            settings.setHomeGrouping("DATE")
            assertEquals("DATE", settings.homeGrouping.first())
        }
}
