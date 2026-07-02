package io.github.armanayvazyan.propsy

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Integration test for the [PropsyBackend] extension-point seam: proves that
 * [PropsyFiles.resolve] and [PropsyFiles.discoverAll] route through the real
 * EP registration (plugin.xml / propsy-dotenv.xml) rather than a backend
 * constructed directly in the test.
 */
class PropsyFilesTest : BasePlatformTestCase() {

    private fun relPathOf(vf: com.intellij.openapi.vfs.VirtualFile): String {
        val base = project.guessProjectDir()!!
        return VfsUtilCore.getRelativePath(vf, base, '/')!!
    }

    fun testPropertiesFileResolvesThroughPropertiesBackend() {
        val psiFile = myFixture.addFileToProject("config.properties", "foo=1\n")
        val relPath = relPathOf(psiFile.virtualFile)

        val resolved = PropsyFiles.resolve(project, relPath)

        assertNotNull("expected config.properties to resolve via PropsyFiles", resolved)
        assertTrue("expected PropertiesBackend, got ${resolved!!.backend}", resolved.backend is PropertiesBackend)
    }

    fun testEnvFileResolvesThroughDotEnvBackend() {
        val psiFile = myFixture.addFileToProject(".env", "FOO=1\n")
        val relPath = relPathOf(psiFile.virtualFile)

        val resolved = PropsyFiles.resolve(project, relPath)

        assertNotNull("expected .env to resolve via PropsyFiles", resolved)
        assertTrue("expected DotEnvBackend, got ${resolved!!.backend}", resolved.backend is DotEnvBackend)
    }

    fun testDiscoverAllIncludesBothPropertiesAndEnvFiles() {
        myFixture.addFileToProject("config.properties", "foo=1\n")
        myFixture.addFileToProject(".env", "FOO=1\n")

        val discovered = PropsyFiles.discoverAll(project)
        val paths = discovered.map { it.path }

        assertTrue(
            "expected discoverAll to include a .properties file, got: $paths",
            paths.any { it.endsWith("config.properties") },
        )
        assertTrue(
            "expected discoverAll to include a .env file, got: $paths",
            paths.any { it.endsWith(".env") },
        )
    }
}
