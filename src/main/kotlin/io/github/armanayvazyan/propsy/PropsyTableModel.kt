package io.github.armanayvazyan.propsy

import com.intellij.openapi.project.Project
import javax.swing.table.AbstractTableModel

/**
 * Editable table model over a single resolved key/value file. Column 0 = key, column 1 = value.
 * Entries that sit under a comment header are preceded by a collapsible, read-only section row.
 * Edits are written straight through the backing [ResolvedFile]; the owning view reloads
 * after structural changes (add/delete/key rename).
 */
class PropsyTableModel(
    private val project: Project,
) : AbstractTableModel() {

    private sealed interface Row {
        data class Section(val name: String) : Row
        data class Item(val entry: PropsyEntry) : Row
    }

    private var file: ResolvedFile? = null
    private var entries: List<PropsyEntry> = emptyList()
    private var rows: List<Row> = emptyList()

    /** Collapsed section names. Kept across reloads of the same file, since every edit reloads the model. */
    private val collapsed = mutableSetOf<String>()

    fun load(file: ResolvedFile?) {
        if (file?.handle != this.file?.handle) collapsed.clear()
        this.file = file
        entries = file?.entries() ?: emptyList()
        rebuildRows()
    }

    private fun rebuildRows() {
        rows = buildList {
            var current: String? = null
            for (entry in entries) {
                val section = entry.section
                if (section != null && section != current) add(Row.Section(section))
                current = section
                if (section == null || section !in collapsed) add(Row.Item(entry))
            }
        }
        fireTableDataChanged()
    }

    fun currentFile(): ResolvedFile? = file

    /** The entry shown at [row], or null for a section row. */
    fun entryAt(row: Int): PropsyEntry? = (rows.getOrNull(row) as? Row.Item)?.entry

    /** The section name if [row] is a section row, else null. */
    fun sectionAt(row: Int): String? = (rows.getOrNull(row) as? Row.Section)?.name

    fun isCollapsed(section: String): Boolean = section in collapsed

    fun toggleSection(row: Int) {
        val name = sectionAt(row) ?: return
        if (!collapsed.remove(name)) collapsed += name
        rebuildRows()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 2

    override fun getColumnName(column: Int): String = if (column == 0) "Key" else "Value"

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = entryAt(rowIndex) != null

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (val row = rows[rowIndex]) {
        is Row.Section -> if (columnIndex == 0) row.name else ""
        is Row.Item -> if (columnIndex == 0) row.entry.key else row.entry.value
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val entry = entryAt(rowIndex) ?: return
        val f = file ?: return
        val text = aValue?.toString() ?: ""
        if (columnIndex == 0) {
            if (text.isBlank() || text == entry.key) return
            f.setKey(project, entry, text)
        } else {
            if (text == entry.value) return
            // Picking a commented-out value swaps which line is commented; anything else edits in place.
            if (text in entry.alternatives) f.selectAlternative(project, entry, text) else f.setValue(project, entry, text)
        }
        // Re-read so cached key/value stay consistent with the backing file.
        load(file)
    }
}
