# .env Support + Platform 252 Downgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Propsy 0.1.4 — render/edit `.env*` files in the existing Key/Value tool window (gated on the `ru.adelf.idea.dotenv` plugin), and lower the minimum IDE from 2026.1 to 2025.2.

**Architecture:** Introduce a `PropsyBackend` extension-point interface with a backend-neutral `PropsyEntry`. `PropertiesBackend` adapts the existing `PropertiesFileBridge`; `DotEnvBackend` reads/writes `.env` files line-by-line through the Document. A `ResolvedFile` (backend + opaque handle) is what the tool window holds, so `PropsyTableModel`/`PropsyTablePanel` become format-agnostic and the UI/UX does not change. The `.env` backend is registered only via an optional `config-file` that loads when the dotenv plugin is present.

**Tech Stack:** Kotlin, IntelliJ Platform Gradle plugin 2.x, JUnit4 + `BasePlatformTestCase`, bundled Properties PSI, `ru.adelf.idea.dotenv` plugin.

## Global Constraints

- JDK 21; Kotlin + Java toolchain pinned to 21 (do not change).
- Target platform after this work: `intellijIdea("2025.2")`, `sinceBuild = "252"`, `untilBuild = null`.
- Release version: `0.1.4`.
- Plugin id / package: `io.github.armanayvazyan.propsy` (unchanged).
- Dotenv plugin id: `ru.adelf.idea.dotenv`. Dependency is **optional** — Propsy loads and `.properties` fully works without it; `.env` support activates only when it is installed.
- **UI/UX must not change**: same combo picker, same 2-column table, same add/remove/refresh actions and dialogs.
- All PSI/Document access wrapped in `ReadAction` / `WriteCommandAction` (named, undoable). Preserve comments, blank lines, key order.
- `PathEntry` compared by value. Getters/setters hand out defensive copies.
- TDD: failing test first. Commit after each green task.
- `.env` filename match rule (used everywhere): `name == ".env" || name.startsWith(".env.")`.

---

## Task 1: Downgrade to platform 252 + version bump

Orthogonal to the feature; do first to establish a green baseline on the target platform.

**Files:**
- Modify: `build.gradle.kts` (version, `intellijIdea`, `sinceBuild`)
- Modify: `README.md:15`

**Interfaces:**
- Consumes: nothing.
- Produces: build compiles and existing tests pass on platform 2025.2.

- [ ] **Step 1: Lower the platform and bump version in `build.gradle.kts`**

Change `version = "0.1.3"` to:

```kotlin
version = "0.1.4"
```

Change the dependency `intellijIdea("2026.1")` to:

```kotlin
        intellijIdea("2025.2")
```

Change `sinceBuild = "261"` to:

```kotlin
            sinceBuild = "252"
```

(Leave `untilBuild = provider { null }` as-is.)

- [ ] **Step 2: Update the README minimum-IDE line**

`README.md:15` currently reads:

```
- IntelliJ IDEA 2026.1 or newer (`sinceBuild = 261`; Community or Ultimate)
```

Replace with:

```
- IntelliJ IDEA 2025.2 or newer (`sinceBuild = 252`; Community or Ultimate)
```

- [ ] **Step 3: Verify build + existing tests pass on 252**

Run: `./gradlew test verifyPlugin`
Expected: BUILD SUCCESSFUL. All existing tests pass. `verifyPlugin` reports no compatibility problems against 252. (First run re-downloads the 2025.2 platform — needs internet.)

If any used API is missing in 252 (unlikely — `guessProjectDir`, `WriteCommandAction.runWriteCommandAction`, `FilenameIndex`, `ProjectFileIndex`, Properties PSI all exist), fix the call site to the 252-available equivalent before proceeding.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts README.md
git commit -m "chore: lower minimum IDE to 2025.2 (sinceBuild 252); bump to 0.1.4"
```

---

## Task 2: Backend abstraction + format-agnostic tool window

Extract the interface, add the Properties adapter, register it via an extension point, and refactor the table model/panel to hold a `ResolvedFile`. No behavior change: only the Properties backend is registered, so the tool window works exactly as before.

**Files:**
- Create: `src/main/kotlin/io/github/armanayvazyan/propsy/PropsyBackend.kt`
- Create: `src/main/kotlin/io/github/armanayvazyan/propsy/ResolvedFile.kt`
- Create: `src/main/kotlin/io/github/armanayvazyan/propsy/PropertiesBackend.kt`
- Modify: `src/main/kotlin/io/github/armanayvazyan/propsy/PropsyTableModel.kt`
- Modify: `src/main/kotlin/io/github/armanayvazyan/propsy/PropsyTablePanel.kt`
- Modify: `src/main/kotlin/io/github/armanayvazyan/propsy/PropsyConfigurable.kt` (scan source only)
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify (test): `src/test/kotlin/io/github/armanayvazyan/propsy/PropsyTableModelTest.kt`
- Create (test): `src/test/kotlin/io/github/armanayvazyan/propsy/PropertiesBackendTest.kt`

**Interfaces:**
- Consumes: existing `PropertiesFileBridge`, `PropertiesScanner`, `PathEntry`.
- Produces:
  - `data class PropsyEntry(val key: String, val value: String, val handle: Any)`
  - `interface PropsyBackend { fun resolve(project, relPath: String): Any?; fun entries(handle: Any): List<PropsyEntry>; fun setValue(project, entry: PropsyEntry, newValue: String); fun setKey(project, entry: PropsyEntry, newKey: String); fun addEntry(project, handle: Any, key: String, value: String): Boolean; fun deleteEntry(project, entry: PropsyEntry); fun discover(project): List<PathEntry> }` with `companion object { val EP_NAME }`.
  - `class ResolvedFile(val backend: PropsyBackend, val handle: Any)` with `entries()`, `setValue()`, `setKey()`, `addEntry()`, `deleteEntry()`.
  - `object PropsyFiles { fun resolve(project, relPath): ResolvedFile?; fun discoverAll(project): List<PathEntry> }`
  - `class PropertiesBackend : PropsyBackend`
  - `PropsyTableModel.load(ResolvedFile?)`, `currentFile(): ResolvedFile?`, `entryAt(row): PropsyEntry?`

- [ ] **Step 1: Write `PropsyBackend.kt`**

```kotlin
package io.github.armanayvazyan.propsy

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** Backend-neutral key/value entry. [handle] is opaque and owned by the producing backend. */
data class PropsyEntry(val key: String, val value: String, val handle: Any)

/**
 * A pluggable key/value file backend — one per file format (.properties, .env).
 * Registered via the `io.github.armanayvazyan.propsy.backend` extension point;
 * the .env backend is contributed only when the dotenv plugin is present.
 */
interface PropsyBackend {
    /** Resolves [relPath] (relative to project base) to an opaque handle this backend owns, or null. */
    fun resolve(project: Project, relPath: String): Any?

    /** Reads entries of a handle produced by [resolve], in document order. */
    fun entries(handle: Any): List<PropsyEntry>

    fun setValue(project: Project, entry: PropsyEntry, newValue: String)

    fun setKey(project: Project, entry: PropsyEntry, newKey: String)

    /** Returns true if added; false if the key already exists. Appends at the end. */
    fun addEntry(project: Project, handle: Any, key: String, value: String): Boolean

    fun deleteEntry(project: Project, entry: PropsyEntry)

    /** Discovers files of this backend's format in the project, each named after its owning module. */
    fun discover(project: Project): List<PathEntry>

    companion object {
        val EP_NAME: ExtensionPointName<PropsyBackend> =
            ExtensionPointName.create("io.github.armanayvazyan.propsy.backend")
    }
}
```

- [ ] **Step 2: Write `ResolvedFile.kt`**

```kotlin
package io.github.armanayvazyan.propsy

import com.intellij.openapi.project.Project

/** A resolved key/value file: a backend paired with its opaque handle. */
class ResolvedFile(val backend: PropsyBackend, val handle: Any) {
    fun entries(): List<PropsyEntry> = backend.entries(handle)
    fun setValue(project: Project, entry: PropsyEntry, newValue: String) = backend.setValue(project, entry, newValue)
    fun setKey(project: Project, entry: PropsyEntry, newKey: String) = backend.setKey(project, entry, newKey)
    fun addEntry(project: Project, key: String, value: String): Boolean = backend.addEntry(project, handle, key, value)
    fun deleteEntry(project: Project, entry: PropsyEntry) = backend.deleteEntry(project, entry)
}

/** Resolves a project-relative path and discovers files across all registered backends. */
object PropsyFiles {
    fun resolve(project: Project, relPath: String): ResolvedFile? {
        for (backend in PropsyBackend.EP_NAME.extensionList) {
            val handle = backend.resolve(project, relPath) ?: continue
            return ResolvedFile(backend, handle)
        }
        return null
    }

    fun discoverAll(project: Project): List<PathEntry> =
        PropsyBackend.EP_NAME.extensionList
            .flatMap { it.discover(project) }
            .distinct()
            .sortedWith(compareBy({ it.name }, { it.path }))
}
```

- [ ] **Step 3: Write `PropertiesBackend.kt` (adapter over `PropertiesFileBridge`)**

```kotlin
package io.github.armanayvazyan.propsy

import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.project.Project

/** Backend for `.properties` files, delegating to [PropertiesFileBridge]. Handle = PropertiesFile; entry handle = IProperty. */
class PropertiesBackend : PropsyBackend {
    override fun resolve(project: Project, relPath: String): Any? =
        PropertiesFileBridge.resolve(project, relPath)

    override fun entries(handle: Any): List<PropsyEntry> =
        PropertiesFileBridge.entries(handle as PropertiesFile)
            .map { PropsyEntry(it.key, it.value, it.property) }

    override fun setValue(project: Project, entry: PropsyEntry, newValue: String) =
        PropertiesFileBridge.setValue(project, entry.handle as IProperty, newValue)

    override fun setKey(project: Project, entry: PropsyEntry, newKey: String) =
        PropertiesFileBridge.setKey(project, entry.handle as IProperty, newKey)

    override fun addEntry(project: Project, handle: Any, key: String, value: String): Boolean =
        PropertiesFileBridge.addEntry(project, handle as PropertiesFile, key, value)

    override fun deleteEntry(project: Project, entry: PropsyEntry) =
        PropertiesFileBridge.deleteEntry(project, entry.handle as IProperty)

    override fun discover(project: Project): List<PathEntry> = PropertiesScanner.scan(project)
}
```

- [ ] **Step 4: Register the extension point and the Properties backend in `plugin.xml`**

In `src/main/resources/META-INF/plugin.xml`, add an `<extensionPoints>` block and a Propsy-namespaced `<extensions>` block (immediately after the existing `<extensions defaultExtensionNs="com.intellij"> … </extensions>` block, before `<applicationListeners/>`):

```xml
    <extensionPoints>
        <extensionPoint name="backend"
                        interface="io.github.armanayvazyan.propsy.PropsyBackend"
                        dynamic="true"/>
    </extensionPoints>

    <extensions defaultExtensionNs="io.github.armanayvazyan.propsy">
        <backend implementation="io.github.armanayvazyan.propsy.PropertiesBackend"/>
    </extensions>
```

- [ ] **Step 5: Refactor `PropsyTableModel.kt` to hold a `ResolvedFile`**

Replace the whole file body with:

```kotlin
package io.github.armanayvazyan.propsy

import com.intellij.openapi.project.Project
import javax.swing.table.AbstractTableModel

/**
 * Editable table model over a single resolved key/value file. Column 0 = key, column 1 = value.
 * Edits are written straight through the backing [ResolvedFile]; the owning view reloads
 * after structural changes (add/delete/key rename).
 */
class PropsyTableModel(
    private val project: Project,
) : AbstractTableModel() {

    private var file: ResolvedFile? = null
    private var rows: List<PropsyEntry> = emptyList()

    fun load(file: ResolvedFile?) {
        this.file = file
        rows = file?.entries() ?: emptyList()
        fireTableDataChanged()
    }

    fun currentFile(): ResolvedFile? = file

    fun entryAt(row: Int): PropsyEntry? = rows.getOrNull(row)

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 2

    override fun getColumnName(column: Int): String = if (column == 0) "Key" else "Value"

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = rows[rowIndex]
        return if (columnIndex == 0) entry.key else entry.value
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val entry = rows.getOrNull(rowIndex) ?: return
        val f = file ?: return
        val text = aValue?.toString() ?: ""
        if (columnIndex == 0) {
            if (text.isBlank() || text == entry.key) return
            f.setKey(project, entry, text)
        } else {
            if (text == entry.value) return
            f.setValue(project, entry, text)
        }
        // Re-read so cached key/value stay consistent with the backing file.
        load(file)
    }
}
```

- [ ] **Step 6: Refactor `PropsyTablePanel.kt` to use `ResolvedFile` / `PropsyFiles`**

Make these edits in `PropsyTablePanel.kt`:

1. Remove the import `import com.intellij.lang.properties.psi.PropertiesFile`.
2. In `reloadSelectedFile()`, replace the body after `val path = currentPath() ?: run { … }` with:

```kotlin
        val file = PropsyFiles.resolve(project, path)
        if (file == null) {
            tableModel.load(null)
            statusLabel.text = "Cannot resolve '$path' (missing or not a supported .properties/.env file)."
        } else {
            tableModel.load(file)
            statusLabel.text = " "
        }
```

3. Change `currentFileOrWarn()` return type and message:

```kotlin
    private fun currentFileOrWarn(): ResolvedFile? {
        val file = tableModel.currentFile()
        if (file == null) {
            Messages.showWarningDialog(project, "Select a resolvable properties/.env file first.", "Propsy")
        }
        return file
    }
```

4. In `addRow()`, replace the add call:

```kotlin
        val added = file.addEntry(project, key, "")
```

5. In `removeRow()`, replace the delete call so it goes through the resolved file:

```kotlin
    private fun removeRow() {
        val row = table.selectedRow
        if (row < 0) return
        val file = tableModel.currentFile() ?: return
        val entry = tableModel.entryAt(table.convertRowIndexToModel(row)) ?: return
        file.deleteEntry(project, entry)
        reloadSelectedFile()
    }
```

- [ ] **Step 7: Point the settings Scan at `PropsyFiles.discoverAll`**

In `PropsyConfigurable.kt`, in `scanAndMerge()`, replace the discovery source line:

```kotlin
        val discovered = PropsyFiles.discoverAll(project).filter { it.path !in existing }
```

(Leave the messages as-is for now; copy is refined in Task 4.)

- [ ] **Step 8: Update `PropsyTableModelTest.kt` to wrap files in `ResolvedFile`**

Each test currently calls `model.load(file)` with a `PropertiesFile`. Wrap it. Replace the three `model.load(file)` lines with:

```kotlin
        model.load(ResolvedFile(PropertiesBackend(), file))
```

(The `as PropertiesFile` casts on `configureByText` stay; `PropertiesBackend.entries` casts the handle back.)

- [ ] **Step 9: Write `PropertiesBackendTest.kt` (adapter mapping)**

```kotlin
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
}
```

- [ ] **Step 10: Run tests to verify the refactor is behavior-preserving**

Run: `./gradlew test`
Expected: PASS — including the existing `PropertiesFileBridgeTest`, `PropertiesScannerTest`, `PropsySettingsTest`, the updated `PropsyTableModelTest`, and the new `PropertiesBackendTest`.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin src/main/resources/META-INF/plugin.xml src/test/kotlin
git commit -m "refactor: introduce PropsyBackend abstraction; make tool window format-agnostic"
```

---

## Task 3: `.env` backend + optional dotenv wiring

Add the `.env` backend (document/regex based — no dependence on the dotenv plugin's PSI classes) and register it only when the dotenv plugin is installed.

**Files:**
- Create: `src/main/kotlin/io/github/armanayvazyan/propsy/DotEnvBackend.kt`
- Create: `src/main/resources/META-INF/propsy-dotenv.xml`
- Modify: `src/main/resources/META-INF/plugin.xml` (optional `<depends>`)
- Modify: `build.gradle.kts` (compile dependency on the dotenv plugin)
- Create (test): `src/test/kotlin/io/github/armanayvazyan/propsy/DotEnvBackendTest.kt`

**Interfaces:**
- Consumes: `PropsyBackend`, `PropsyEntry`, `PathEntry` (Task 2).
- Produces: `class DotEnvBackend : PropsyBackend` with handle = `VirtualFile`, entry handle = `VirtualFile`. Registered via `propsy-dotenv.xml`.

- [ ] **Step 1: Write the failing `DotEnvBackendTest.kt`**

```kotlin
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
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew test --tests "io.github.armanayvazyan.propsy.DotEnvBackendTest"`
Expected: FAIL — `DotEnvBackend` unresolved (does not compile yet).

- [ ] **Step 3: Write `DotEnvBackend.kt`**

```kotlin
package io.github.armanayvazyan.propsy

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Backend for `.env*` files. Reads and edits line-by-line through the Document,
 * preserving comments, blank lines and key order, all inside undoable
 * [WriteCommandAction]s. Uses no dotenv-plugin PSI classes, so it loads safely;
 * it is registered only when the dotenv plugin is present (see propsy-dotenv.xml).
 *
 * Handle (file) = [VirtualFile]. Entry handle = the same [VirtualFile]; the entry's
 * key locates its line on write.
 */
class DotEnvBackend : PropsyBackend {

    /** groups: 1 = prefix (leading space + optional `export `), 2 = key, 3 = `=` incl. spaces, 4 = value. */
    private val pattern = Regex("""^(\s*(?:export\s+)?)([A-Za-z_][A-Za-z0-9_.]*)(\s*=)(.*)$""")

    private data class LineRef(
        val start: Int, val end: Int,
        val prefix: String, val key: String, val sep: String, val value: String,
    )

    private fun isEnvName(name: String): Boolean = name == ".env" || name.startsWith(".env.")

    private fun docOf(vf: VirtualFile): Document? = FileDocumentManager.getInstance().getDocument(vf)

    private fun scan(doc: Document): List<LineRef> =
        (0 until doc.lineCount).mapNotNull { i ->
            val start = doc.getLineStartOffset(i)
            val end = doc.getLineEndOffset(i)
            val m = pattern.matchEntire(doc.getText(TextRange(start, end))) ?: return@mapNotNull null
            LineRef(start, end, m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }

    /** Runs [mutate] on the first line whose key equals [matchKey], inside a named write command. */
    private fun edit(project: Project, vf: VirtualFile, name: String, matchKey: String, mutate: (Document, LineRef) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, name, null, Runnable {
            val doc = docOf(vf) ?: return@Runnable
            val ref = scan(doc).firstOrNull { it.key == matchKey } ?: return@Runnable
            mutate(doc, ref)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        })
    }

    override fun resolve(project: Project, relPath: String): Any? = ReadAction.compute<VirtualFile?, RuntimeException> {
        val base = project.guessProjectDir() ?: return@compute null
        val vf = base.findFileByRelativePath(relPath.trim()) ?: return@compute null
        if (vf.isDirectory || !vf.isValid || !isEnvName(vf.name)) null else vf
    }

    override fun entries(handle: Any): List<PropsyEntry> = ReadAction.compute<List<PropsyEntry>, RuntimeException> {
        val vf = handle as VirtualFile
        val doc = docOf(vf) ?: return@compute emptyList()
        scan(doc).map { PropsyEntry(it.key, it.value, vf) }
    }

    override fun setValue(project: Project, entry: PropsyEntry, newValue: String) =
        edit(project, entry.handle as VirtualFile, "Edit .env Value", entry.key) { doc, ref ->
            doc.replaceString(ref.start, ref.end, ref.prefix + ref.key + ref.sep + newValue)
        }

    override fun setKey(project: Project, entry: PropsyEntry, newKey: String) =
        edit(project, entry.handle as VirtualFile, "Rename .env Key", entry.key) { doc, ref ->
            doc.replaceString(ref.start, ref.end, ref.prefix + newKey + ref.sep + ref.value)
        }

    override fun addEntry(project: Project, handle: Any, key: String, value: String): Boolean {
        val vf = handle as VirtualFile
        val exists = ReadAction.compute<Boolean, RuntimeException> {
            val doc = docOf(vf) ?: return@compute false
            scan(doc).any { it.key == key }
        }
        if (exists) return false
        WriteCommandAction.runWriteCommandAction(project, "Add .env Entry", null, Runnable {
            val doc = docOf(vf) ?: return@Runnable
            val len = doc.textLength
            val needsNewline = len > 0 && doc.charsSequence[len - 1] != '\n'
            doc.insertString(len, (if (needsNewline) "\n" else "") + "$key=$value\n")
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        })
        return true
    }

    override fun deleteEntry(project: Project, entry: PropsyEntry) =
        edit(project, entry.handle as VirtualFile, "Delete .env Entry", entry.key) { doc, ref ->
            val delEnd = if (ref.end < doc.textLength) ref.end + 1 else ref.end // consume trailing '\n'
            doc.deleteString(ref.start, delEnd)
        }

    override fun discover(project: Project): List<PathEntry> = ReadAction.compute<List<PathEntry>, RuntimeException> {
        val base = project.guessProjectDir() ?: return@compute emptyList()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        FilenameIndex.getAllFilenames(project)
            .filter { isEnvName(it) }
            .flatMap { FilenameIndex.getVirtualFilesByName(it, scope).toList() }
            .asSequence()
            .filter { vf ->
                !vf.isDirectory && vf.isValid &&
                    fileIndex.isInContent(vf) && !fileIndex.isExcluded(vf) && !fileIndex.isInLibrary(vf)
            }
            .mapNotNull { vf ->
                val rel = VfsUtilCore.getRelativePath(vf, base, '/') ?: return@mapNotNull null
                val name = fileIndex.getModuleForFile(vf)?.name ?: base.name
                PathEntry(name, rel)
            }
            .distinct()
            .toList()
    }
}
```

- [ ] **Step 4: Run the backend test to confirm it passes**

Run: `./gradlew test --tests "io.github.armanayvazyan.propsy.DotEnvBackendTest"`
Expected: PASS.

If `FilenameIndex.getAllFilenames(project)` or `getVirtualFilesByName(name, scope)` fails to compile against 252, switch to the still-present overload `FilenameIndex.getVirtualFilesByName(project, name, scope)` and, for enumeration, keep `getAllFilenames(project)` (it exists on 252). The `discover` path is not exercised by `DotEnvBackendTest`; a compile check (`./gradlew compileKotlin`) confirms the signatures.

- [ ] **Step 5: Add the compile dependency on the dotenv plugin in `build.gradle.kts`**

Inside the `intellijPlatform { … }` dependencies block, after `bundledPlugin("com.intellij.properties")`, add:

```kotlin
        plugin("ru.adelf.idea.dotenv:VERSION")
```

Resolve `VERSION` to the latest `ru.adelf.idea.dotenv` release compatible with build 252 and replace the literal `VERSION`. Find it with:

```bash
curl -s "https://plugins.jetbrains.com/api/plugins/9525/updates?channel=&size=40" \
  | python3 -c "import sys,json; [print(u['version'], u.get('since'), u.get('until')) for u in json.load(sys.stdin)]"
```

Pick the newest `version` whose `since`/`until` range includes `252.*` (an entry with `since` ≤ 252 and `until` empty or ≥ 252). Use that exact string, e.g. `plugin("ru.adelf.idea.dotenv:252.xxxx.yy")`.

- [ ] **Step 6: Add the optional dependency + register the backend**

In `plugin.xml`, add after the existing `<depends>com.intellij.properties</depends>`:

```xml
    <depends optional="true" config-file="propsy-dotenv.xml">ru.adelf.idea.dotenv</depends>
```

Create `src/main/resources/META-INF/propsy-dotenv.xml`:

```xml
<idea-plugin>
    <extensions defaultExtensionNs="io.github.armanayvazyan.propsy">
        <backend implementation="io.github.armanayvazyan.propsy.DotEnvBackend"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 7: Verify full build + plugin structure**

Run: `./gradlew test verifyPlugin`
Expected: PASS. `verifyPlugin` shows no structural problems; the optional dependency and config-file resolve. The dotenv plugin is now on the test/sandbox classpath, so `.env` support is active there.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/github/armanayvazyan/propsy/DotEnvBackend.kt \
        src/main/resources/META-INF/propsy-dotenv.xml \
        src/main/resources/META-INF/plugin.xml \
        build.gradle.kts \
        src/test/kotlin/io/github/armanayvazyan/propsy/DotEnvBackendTest.kt
git commit -m "feat: add .env backend gated on the ru.adelf.idea.dotenv plugin"
```

---

## Task 4: Settings integration for `.env` + changelog + release verify

Let users add `.env*` files from the settings file chooser, update onboarding copy, record the changelog, and run the full release verification. (`PropsyFiles.discoverAll` already includes `.env` results once Task 3 registers the backend — Scan needs no further change.)

**Files:**
- Modify: `src/main/kotlin/io/github/armanayvazyan/propsy/PropsyConfigurable.kt` (file filter + copy)
- Modify: `CHANGELOG.md`
- Modify: `README.md` (feature description mentions `.env`)

**Interfaces:**
- Consumes: `PropsyFiles.discoverAll` (Task 2), `DotEnvBackend` discovery (Task 3).
- Produces: no new public API.

- [ ] **Step 1: Accept `.env*` in the settings file chooser**

In `PropsyConfigurable.kt`, in `chooseAndAdd()`, replace the `descriptor` construction with:

```kotlin
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select .properties or .env File")
            .withFileFilter { f ->
                f.extension.equals("properties", ignoreCase = true) ||
                    f.name == ".env" || f.name.startsWith(".env.")
            }
            .withRoots(base)
```

- [ ] **Step 2: Update onboarding copy + scan message to mention `.env`**

In `createComponent()`, replace the `header` `JBLabel(...)` HTML text with:

```kotlin
        val header = JBLabel(
            "<html><body style='width:480px'>" +
                "Choose which <b>.properties</b> and <b>.env</b> files appear in the Propsy tool window. " +
                "Click <b>Scan</b> to auto-discover them across your modules, " +
                "or <b>+</b> to add one manually. Edit the <b>Name</b> column to label each file — " +
                "that name is what the tool window shows." +
                "</body></html>",
        )
```

In `scanAndMerge()`, replace the two message strings and the dialog title:

```kotlin
        val message = if (discovered.isEmpty()) {
            "No new .properties or .env files found."
        } else {
            "Added ${discovered.size} file(s)."
        }
        Messages.showInfoMessage(project, message, "Scan Key/Value Files")
```

- [ ] **Step 3: Update the CHANGELOG `[Unreleased]` section**

In `CHANGELOG.md`, replace the line `## [Unreleased]` (and its empty body) with:

```markdown
## [Unreleased]

### Added

- Support for `.env` files in the Key/Value tool window (requires the
  ".env files support" plugin, `ru.adelf.idea.dotenv`). Edits preserve
  comments, blank lines and key order like `.properties`.

### Changed

- Lowered the minimum IDE to 2025.2 (`sinceBuild = 252`).
```

- [ ] **Step 4: Mention `.env` in the README feature blurb**

In `README.md`, find the top-level description of what the plugin renders (the sentence describing `.properties` files as an editable table) and extend it to note `.env` files are also supported when the `.env files support` plugin is installed. Keep the existing wording; add one clause — e.g. change "renders configured `.properties` files" to "renders configured `.properties` and `.env` files". (`.env` requires `ru.adelf.idea.dotenv`.)

- [ ] **Step 5: Full release verification**

Run: `./gradlew test verifyPlugin buildPlugin`
Expected: PASS. `build/distributions/` contains the `0.1.4` zip. No verifier problems.

- [ ] **Step 6: Manual smoke check (optional but recommended)**

Run: `./gradlew runIde`
In the sandbox: add a `.env` file with `FOO=1`, open the Propsy tool window, confirm it lists in the picker, edit a value, add/remove a key, and confirm the file on disk preserves comments/order. Confirm `.properties` still works identically.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/io/github/armanayvazyan/propsy/PropsyConfigurable.kt CHANGELOG.md README.md
git commit -m "feat: surface .env files in settings; changelog + docs for 0.1.4"
```

---

## Self-Review

**Spec coverage:**
- `.env` support via `ru.adelf.idea.dotenv`, optional dep, properties works without it → Tasks 2 (abstraction), 3 (backend + `<depends optional config-file>` + `propsy-dotenv.xml`). ✅
- UI/UX unchanged → Task 2 keeps table/combo/actions; only the held type changes. ✅
- Reads via dotenv PSI (spec) → **deviation:** implemented as document/regex read+write instead, to avoid coupling to the dotenv plugin's undocumented PSI mutation API. The spec's stated fallback ("Document-based writes") and non-goals (flat `KEY=VALUE`, preserve-as-typed) make this consistent; the spike is thereby resolved by construction. Noted here and in Task 3's file doc. ✅
- Scanner finds `.env*` by filename → `DotEnvBackend.discover` (Task 3) + `PropsyFiles.discoverAll` (Task 2). ✅
- Settings file chooser accepts `.env*` → Task 4. ✅
- Downgrade to 252, verify APIs, `sinceBuild=252`, README note → Task 1 + Task 4 README blurb. ✅
- Tests mirror `PropertiesFileBridgeTest`; existing Properties tests unchanged (except `PropsyTableModelTest`, whose signature necessarily changed) → Tasks 2–3. ✅
- Version 0.1.4 + changelog → Tasks 1 + 4. ✅

**Placeholder scan:** The only literal placeholder is `VERSION` for the dotenv plugin coordinate — Task 3 Step 5 gives the exact command to resolve it and the substitution to make. No `TODO`/"handle edge cases"/vague steps remain.

**Type consistency:** `PropsyEntry(key, value, handle)`, `PropsyBackend` method signatures, `ResolvedFile` helpers, `PropsyFiles.resolve`/`discoverAll`, and `PropsyTableModel.load(ResolvedFile?)` are used identically across Tasks 2–4. `.env` filename rule (`== ".env" || startsWith(".env.")`) is identical in `DotEnvBackend` and the file chooser filter. Properties entry handle = `IProperty`; `.env` entry handle = `VirtualFile` — each backend casts only its own handle.
