package io.github.armanayvazyan.propsy

import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/**
 * Bridges a project-relative path to the bundled Properties PSI and exposes the
 * mutation operations used by the table. All writes run in a [WriteCommandAction]
 * so they are undoable and preserve comments, blank lines and key order.
 */
object PropertiesFileBridge {

    /**
     * A single key/value entry plus a stable reference to the backing PSI property.
     * [alternatives] are the distinct values of commented-out `#key=value` lines for the same key
     * (excluding the active value), in document order. [section] is the name of the comment header
     * the property sits under (see [sectionHeaders]), or null above the first header.
     */
    data class Entry(
        val key: String,
        val value: String,
        val property: IProperty,
        val alternatives: List<String> = emptyList(),
        val section: String? = null,
    )

    /** A commented-out property line. [prefix] is everything before the key (`# `, `  !`, ...). */
    private data class CommentedLine(val line: Int, val prefix: String, val key: String, val value: String)

    /**
     * groups: 1 = comment prefix incl. leading whitespace, 2 = raw key, 3 = raw value.
     * Requires an explicit `=`/`:` so ordinary prose comments are not mistaken for properties.
     */
    private val commentedProperty = Regex("""^(\s*[#!]+\s*)((?:\\.|[^=:\s\\])+)\s*[=:]\s*(.*)$""")

    private val commentLine = Regex("""^\s*[#!].*$""")

    /** Characters stripped from a header comment, so `# ---- Database ----` is named `Database`. */
    private const val HEADER_DECORATION = "#!-=*~_/\\|+<>[] \t"

    /** Resolves [relPath] (relative to the project base dir) to a [PropertiesFile], or null. */
    fun resolve(project: Project, relPath: String): PropertiesFile? = ReadAction.compute<PropertiesFile?, RuntimeException> {
        val base = project.guessProjectDir() ?: return@compute null
        val vf = base.findFileByRelativePath(relPath.trim()) ?: return@compute null
        if (vf.isDirectory || !vf.isValid) return@compute null
        PsiManager.getInstance(project).findFile(vf) as? PropertiesFile
    }

    /** Reads all entries of [file] in document order. */
    fun entries(file: PropertiesFile): List<Entry> = ReadAction.compute<List<Entry>, RuntimeException> {
        val doc = documentOf(file)
        val properties = file.properties
        val commentedLines = doc?.let { commentedLines(it) }.orEmpty()
        val commented = commentedLines.groupBy({ it.key }, { it.value })
        val startLines = properties.map { p -> doc?.getLineNumber(p.psiElement.textRange.startOffset) ?: -1 }
        val headers = doc?.let { sectionHeaders(it, startLines.toSet(), commentedLines.mapTo(HashSet()) { c -> c.line }) }.orEmpty()

        var next = 0
        var section: String? = null
        properties.mapIndexed { i, p ->
            while (next < headers.size && headers[next].first < startLines[i]) section = headers[next++].second
            val key = p.key ?: ""
            val value = p.value ?: ""
            Entry(key, value, p, commented[key].orEmpty().filter { it != value }.distinct(), section)
        }
    }

    /**
     * Finds section headers as (line, name) pairs in document order. A header is a run of plain
     * comment lines directly above a property, with no blank line between them. Commented-out
     * properties in between are skipped, so `#db.url=local` does not break a header. The name is
     * the run's last line that still has text once [HEADER_DECORATION] is stripped. A section
     * lasts until the next header.
     */
    private fun sectionHeaders(doc: Document, propertyLines: Set<Int>, commentedPropertyLines: Set<Int>): List<Pair<Int, String>> {
        val headers = mutableListOf<Pair<Int, String>>()
        var pending: Pair<Int, String>? = null
        for (i in 0 until doc.lineCount) {
            val text = lineText(doc, i)
            when {
                i in propertyLines -> { pending?.let { headers += it }; pending = null }
                i in commentedPropertyLines -> Unit
                commentLine.matches(text) -> text.trim { it in HEADER_DECORATION }.takeIf { it.isNotEmpty() }?.let { pending = i to it }
                else -> pending = null // blank line or value continuation ends the run
            }
        }
        return headers
    }

    private fun lineText(doc: Document, line: Int): String =
        doc.getText(TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)))

    /**
     * Makes the commented-out line `#key=[value]` the active one and comments out the current
     * [property], reusing the chosen line's comment prefix so switching back restores the layout.
     * One undoable command. No-op if no such commented line exists.
     */
    fun selectAlternative(project: Project, property: IProperty, value: String) {
        val psiFile = property.psiElement.containingFile
        WriteCommandAction.runWriteCommandAction(project, "Switch Property Value", null, {
            val docs = PsiDocumentManager.getInstance(project)
            val doc = docs.getDocument(psiFile) ?: return@runWriteCommandAction
            docs.commitDocument(doc)
            val key = property.key ?: return@runWriteCommandAction
            val target = commentedLines(doc).firstOrNull { it.key == key && it.value == value } ?: return@runWriteCommandAction
            val range = property.psiElement.textRange
            val activeLines = doc.getLineNumber(range.startOffset)..doc.getLineNumber(range.endOffset)
            val commentPrefix = target.prefix.trimStart()

            val uncomment = {
                val start = doc.getLineStartOffset(target.line)
                doc.deleteString(start, start + target.prefix.length)
            }
            val commentOut = {
                for (line in activeLines.reversed()) doc.insertString(doc.getLineStartOffset(line), commentPrefix)
            }
            // Edit the lower line first so the upper line's offsets stay valid.
            if (target.line > activeLines.last) { uncomment(); commentOut() } else { commentOut(); uncomment() }
            docs.commitDocument(doc)
        }, psiFile)
    }

    private fun documentOf(file: PropertiesFile): Document? =
        PsiDocumentManager.getInstance(file.containingFile.project).getDocument(file.containingFile)

    private fun commentedLines(doc: Document): List<CommentedLine> =
        (0 until doc.lineCount).mapNotNull { i ->
            val m = commentedProperty.matchEntire(lineText(doc, i)) ?: return@mapNotNull null
            val value = m.groupValues[3]
            // A trailing odd backslash would continue onto the next line once uncommented; skip those.
            if (value.takeLastWhile { it == '\\' }.length % 2 == 1) return@mapNotNull null
            CommentedLine(i, m.groupValues[1], m.groupValues[2], value)
        }

    fun setValue(project: Project, property: IProperty, newValue: String) {
        WriteCommandAction.runWriteCommandAction(project, "Edit Property Value", null, {
            property.setValue(newValue)
        }, property.psiElement.containingFile)
    }

    fun setKey(project: Project, property: IProperty, newKey: String) {
        WriteCommandAction.runWriteCommandAction(project, "Rename Property Key", null, {
            property.setName(newKey)
        }, property.psiElement.containingFile)
    }

    /** Returns true if added; false if the key already exists. */
    fun addEntry(project: Project, file: PropertiesFile, key: String, value: String): Boolean {
        if (ReadAction.compute<Boolean, RuntimeException> { file.findPropertyByKey(key) != null }) return false
        WriteCommandAction.runWriteCommandAction(project, "Add Property", null, {
            // Append at the end to preserve existing key order (addProperty inserts alphabetically).
            val anchor = file.properties.lastOrNull() as? Property
            if (anchor != null) file.addPropertyAfter(key, value, anchor) else file.addProperty(key, value)
        }, file.containingFile)
        return true
    }

    fun deleteEntry(project: Project, property: IProperty) {
        WriteCommandAction.runWriteCommandAction(project, "Delete Property", null, {
            property.psiElement.delete()
        }, property.psiElement.containingFile)
    }
}
