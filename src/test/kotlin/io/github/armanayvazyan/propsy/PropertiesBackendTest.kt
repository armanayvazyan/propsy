package io.github.armanayvazyan.propsy

import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PropertiesBackendTest : BasePlatformTestCase() {

    private val backend = PropertiesBackend()

    fun testEntriesMapKeyValueAndHandle() {
        val file = myFixture.configureByText("m.properties", "foo=1\nbar=2\n") as PropertiesFile
        val entries = backend.entries(file)
        assertEquals(listOf("foo", "bar"), entries.map { it.key })
        assertEquals(listOf("1", "2"), entries.map { it.value })
        assertTrue(entries.first().handle is IProperty)
    }

    fun testSetValueWritesThrough() {
        val file = myFixture.configureByText("m.properties", "foo=1\n") as PropertiesFile
        val entry = backend.entries(file).first()
        backend.setValue(project, entry, "99")
        assertEquals("foo=99\n", file.containingFile.text)
    }

    private fun load(text: String): PropertiesFile =
        myFixture.configureByText("config.properties", text) as PropertiesFile

    fun testCommentedSameKeyBecomesAlternative() {
        val file = load("# db settings: local\ndb.url=prod\n#db.url=local\n# db.url = staging\nother=1\n")
        val db = backend.entries(file).single { it.key == "db.url" }
        assertEquals("prod", db.value)
        assertEquals(listOf("local", "staging"), db.alternatives)
        assertTrue(backend.entries(file).single { it.key == "other" }.alternatives.isEmpty())
    }

    fun testCommentWithActiveValueOrProseIsNotAlternative() {
        val file = load("mode=a\n#mode=a\n# mode is important\n#mode=b\\\n")
        assertTrue(backend.entries(file).single().alternatives.isEmpty())
    }

    fun testSelectAlternativeSwapsCommentsBelow() {
        val file = load("db.url=prod\n#db.url=local\n")
        backend.selectAlternative(project, backend.entries(file).single(), "local")
        assertEquals("#db.url=prod\ndb.url=local\n", file.containingFile.text)
        val db = backend.entries(file).single()
        assertEquals("local", db.value)
        assertEquals(listOf("prod"), db.alternatives)
    }

    fun testSelectAlternativeAboveReusesCommentPrefixAndRoundTrips() {
        val original = "# db.url=local\ndb.url=prod\nx=1\n"
        val file = load(original)
        backend.selectAlternative(project, backend.entries(file).first(), "local")
        assertEquals("db.url=local\n# db.url=prod\nx=1\n", file.containingFile.text)
        backend.selectAlternative(project, backend.entries(file).first(), "prod")
        assertEquals(original, file.containingFile.text)
    }

    fun testTableModelDispatchesSelectVsCustom() {
        val file = load("db.url=prod\n#db.url=local\n")
        val model = PropsyTableModel(project).apply { load(ResolvedFile(backend, file)) }
        model.setValueAt("local", 0, 1)
        assertEquals("#db.url=prod\ndb.url=local\n", file.containingFile.text)
        model.setValueAt("custom", 0, 1)
        assertEquals("#db.url=prod\ndb.url=custom\n", file.containingFile.text)
        assertEquals(listOf("prod"), model.entryAt(0)?.alternatives)
    }

    fun testCustomValueEditsActiveLineOnly() {
        val file = load("db.url=prod\n#db.url=local\n")
        backend.setValue(project, backend.entries(file).single(), "custom")
        assertEquals("db.url=custom\n#db.url=local\n", file.containingFile.text)
    }

    private fun sections(file: PropertiesFile) = backend.entries(file).map { it.key to it.section }

    fun testCommentAboveKeysStartsSection() {
        val file = load("#database\ndb.port=1121\ndb.host=localhost\n#server\nserver.port=8080\n")
        assertEquals(
            listOf("db.port" to "database", "db.host" to "database", "server.port" to "server"),
            sections(file),
        )
    }

    fun testSectionNameStripsDecorationAndSkipsCommentedAlternatives() {
        val file = load("app=x\n\n# ---- Database ----\n#db.url=local\ndb.url=prod\n")
        assertEquals(listOf("app" to null, "db.url" to "Database"), sections(file))
    }

    fun testBlankLineBetweenCommentAndKeyIsNotSection() {
        val file = load("# license blah\n\nfoo=1\n")
        assertEquals(listOf("foo" to null), sections(file))
    }

    fun testSectionSurvivesSwitchingAlternative() {
        val file = load("#database\ndb.url=prod\n#db.url=local\ndb.port=1\n")
        backend.selectAlternative(project, backend.entries(file).first(), "local")
        assertEquals(listOf("db.url" to "database", "db.port" to "database"), sections(file))
    }

    fun testTableModelShowsCollapsibleSectionRows() {
        val file = load("top=0\n#database\ndb.port=1121\ndb.host=localhost\n")
        val model = PropsyTableModel(project).apply { load(ResolvedFile(backend, file)) }
        assertEquals(4, model.rowCount)
        assertEquals("database", model.sectionAt(1))
        assertNull(model.entryAt(1))
        assertFalse(model.isCellEditable(1, 1))
        assertEquals("db.port", model.entryAt(2)?.key)

        model.toggleSection(1)
        assertEquals(2, model.rowCount)
        // An edit reloads the model; the section must stay collapsed.
        model.setValueAt("1", 0, 1)
        assertEquals(2, model.rowCount)
        model.toggleSection(1)
        assertEquals(4, model.rowCount)
    }
}
