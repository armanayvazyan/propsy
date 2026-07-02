package io.github.armanayvazyan.propsy

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DotEnvBackendTest : BasePlatformTestCase() {

    private val backend = DotEnvBackend()

    private fun envFile(text: String): VirtualFile =
        myFixture.configureByText(".env", text).virtualFile

    private fun docText(vf: VirtualFile): String =
        FileDocumentManager.getInstance().getDocument(vf)!!.text

    fun testEntriesReadInOrder() {
        val f = envFile("# comment\nFOO=1\nBAR=2\n")
        assertEquals(listOf("FOO", "BAR"), backend.entries(f).map { it.key })
        assertEquals(listOf("1", "2"), backend.entries(f).map { it.value })
    }

    fun testSetValuePreservesCommentsAndBlankLines() {
        val f = envFile("# top\n\nFOO=1\nBAR=2\n")
        val bar = backend.entries(f).first { it.key == "BAR" }
        backend.setValue(project, bar, "22")
        assertEquals("# top\n\nFOO=1\nBAR=22\n", docText(f))
    }

    fun testSetKeyPreservesExportPrefix() {
        val f = envFile("export FOO=1\n")
        backend.setKey(project, backend.entries(f).first(), "RENAMED")
        assertEquals("export RENAMED=1\n", docText(f))
    }

    fun testAddAppendsAndRejectsDuplicate() {
        val f = envFile("FOO=1\n")
        assertTrue(backend.addEntry(project, f, "BAR", "2"))
        assertFalse(backend.addEntry(project, f, "FOO", "x"))
        assertEquals(listOf("FOO", "BAR"), backend.entries(f).map { it.key })
        assertEquals("FOO=1\nBAR=2\n", docText(f))
    }

    fun testDeleteRemovesLine() {
        val f = envFile("FOO=1\nBAR=2\n")
        backend.deleteEntry(project, backend.entries(f).first { it.key == "FOO" })
        assertEquals(listOf("BAR"), backend.entries(f).map { it.key })
        assertEquals("BAR=2\n", docText(f))
    }
}
