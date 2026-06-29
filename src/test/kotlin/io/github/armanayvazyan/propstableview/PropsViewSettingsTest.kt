package io.github.armanayvazyan.propstableview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PropsViewSettingsTest : BasePlatformTestCase() {

    fun testPathsRoundTripThroughState() {
        val settings = PropsViewSettings.getInstance(project)
        settings.paths = listOf("src/messages.properties", "config/app.properties")

        val state = settings.state
        val restored = PropsViewSettings()
        restored.loadState(state)

        assertEquals(listOf("src/messages.properties", "config/app.properties"), restored.paths)
    }

    fun testPathsGetterReturnsDefensiveCopy() {
        val settings = PropsViewSettings.getInstance(project)
        settings.paths = listOf("a.properties")
        val first = settings.paths
        settings.paths = listOf("a.properties", "b.properties")
        assertEquals(1, first.size)
    }
}
