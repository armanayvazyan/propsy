package io.github.armanayvazyan.propstableview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PropsViewSettingsTest : BasePlatformTestCase() {

    fun testEntriesRoundTripThroughState() {
        val settings = PropsViewSettings.getInstance(project)
        settings.entries = listOf(
            PathEntry("messages", "src/messages.properties"),
            PathEntry("app", "config/app.properties"),
        )

        val state = settings.state
        val restored = PropsViewSettings()
        restored.loadState(state)

        assertEquals(
            listOf(
                PathEntry("messages", "src/messages.properties"),
                PathEntry("app", "config/app.properties"),
            ),
            restored.entries,
        )
    }

    fun testLegacyPathsMigrateToEntries() {
        val legacy = PropsViewSettings.State().apply {
            paths = mutableListOf("src/messages.properties", "config/app.properties")
        }
        val settings = PropsViewSettings()
        settings.loadState(legacy)

        assertEquals(
            listOf(
                PathEntry("messages.properties", "src/messages.properties"),
                PathEntry("app.properties", "config/app.properties"),
            ),
            settings.entries,
        )
        // legacy field cleared after migration
        assertTrue(settings.state.paths.isEmpty())
    }

    fun testEntriesGetterReturnsDefensiveCopy() {
        val settings = PropsViewSettings.getInstance(project)
        settings.entries = listOf(PathEntry("a", "a.properties"))
        val first = settings.entries
        settings.entries = listOf(PathEntry("a", "a.properties"), PathEntry("b", "b.properties"))
        assertEquals(1, first.size)
    }
}
