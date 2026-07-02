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
 *
 * Note: automated tests always run with the dotenv plugin present (it is a build
 * dependency), so the "dotenv plugin absent → only Properties backend, .properties
 * still works" path is verified manually via `runIde` with the plugin disabled, not in CI.
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
