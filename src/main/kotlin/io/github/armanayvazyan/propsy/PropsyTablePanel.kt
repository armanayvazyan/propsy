package io.github.armanayvazyan.propsy

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Tool window content: a path picker on top and an editable Key/Value table below.
 */
class PropsyTablePanel(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()) {

    private val comboModel = DefaultComboBoxModel<PathEntry>()
    private val combo = ComboBox(comboModel)
    private val statusLabel = JBLabel()
    private val tableModel = PropsyTableModel(project)
    private val table = object : JBTable(tableModel) {
        // Values with commented-out alternatives edit through an editable combo: pick one to swap
        // which line is commented, or type a custom value to edit the active line in place.
        override fun getCellEditor(row: Int, column: Int): TableCellEditor {
            val alternatives = alternativesAt(this, row, column)
            if (alternatives.isEmpty()) return super.getCellEditor(row, column)
            val combo = ComboBox(arrayOf(getValueAt(row, column).toString()) + alternatives)
            combo.isEditable = true
            return object : DefaultCellEditor(combo) {
                // Read the editor text directly so a typed value is not lost when editing ends on focus loss.
                override fun getCellEditorValue(): Any? = combo.editor.item
            }
        }

        override fun getCellRenderer(row: Int, column: Int): TableCellRenderer {
            val modelRow = convertRowIndexToModel(row)
            return when {
                tableModel.sectionAt(modelRow) != null -> sectionRenderer
                column == 0 && tableModel.entryAt(modelRow)?.section != null -> indentedKeyRenderer
                alternativesAt(this, row, column).isNotEmpty() -> alternativesRenderer
                else -> super.getCellRenderer(row, column)
            }
        }
    }

    /** Section row: a collapse chevron and the bold header name in the Key column, an empty Value cell. */
    private val sectionRenderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            icon = null
            if (column != 0) return
            val name = value?.toString() ?: ""
            icon = if (tableModel.isCollapsed(name)) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
            append(name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }

    /** Keys inside a section are indented to line up under the header name, past its chevron. */
    private val indentedKeyRenderer = object : ColoredTableCellRenderer() {
        init {
            ipad = JBUI.insetsLeft(22)
        }

        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            append(value?.toString() ?: "")
        }
    }

    /** Values with alternatives get a dropdown arrow at the right edge. */
    private val alternativesRenderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            append(value?.toString() ?: "")
            icon = AllIcons.General.ArrowDown
            isIconOnTheRight = true
        }
    }

    private fun alternativesAt(table: JTable, viewRow: Int, column: Int): List<String> =
        if (column != 1) emptyList()
        else tableModel.entryAt(table.convertRowIndexToModel(viewRow))?.alternatives.orEmpty()

    /** Guards combo mutations in [refreshAll] so the action listener does not fire mid-rebuild. */
    private var suppressComboEvents = false

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(true)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                if (row >= 0) tableModel.toggleSection(table.convertRowIndexToModel(row))
            }
        })

        combo.addActionListener { if (!suppressComboEvents) reloadSelectedFile() }
        combo.renderer = SimpleListCellRenderer.create("") { it.name.ifBlank { it.path } }

        val top = JPanel(BorderLayout())
        top.border = JBUI.Borders.empty(4)
        top.add(combo, BorderLayout.CENTER)
        top.add(statusLabel, BorderLayout.SOUTH)

        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { addRow() }
            .setRemoveAction { removeRow() }
            .addExtraAction(object : DumbAwareAction("Refresh", "Reload from disk", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshAll()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            .addExtraAction(object : DumbAwareAction("Settings", "Open Propsy settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) =
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PropsyConfigurable::class.java)
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            .createPanel()

        add(top, BorderLayout.NORTH)
        add(decorated, BorderLayout.CENTER)

        project.messageBus.connect(parentDisposable)
            .subscribe(PropsySettings.CHANGED_TOPIC, Runnable { refreshAll() })

        refreshAll()
    }

    /** Repopulates the combo from settings, keeping the current selection if still present. */
    private fun refreshAll() {
        val previousPath = (combo.selectedItem as? PathEntry)?.path
        val entries = PropsySettings.getInstance(project).entries
        suppressComboEvents = true
        try {
            comboModel.removeAllElements()
            entries.forEach { comboModel.addElement(it) }
            when {
                entries.isEmpty() -> combo.selectedItem = null
                previousPath != null && entries.any { it.path == previousPath } ->
                    combo.selectedItem = entries.first { it.path == previousPath }
                else -> combo.selectedIndex = 0
            }
        } finally {
            suppressComboEvents = false
        }
        if (entries.isEmpty()) {
            tableModel.load(null)
            statusLabel.text = "No paths configured. Add them in Settings | Tools | Propsy."
        } else {
            reloadSelectedFile()
        }
    }

    private fun currentPath(): String? = (combo.selectedItem as? PathEntry)?.path

    private fun reloadSelectedFile() {
        val path = currentPath() ?: run {
            tableModel.load(null)
            return
        }
        val file = PropsyFiles.resolve(project, path)
        if (file == null) {
            tableModel.load(null)
            statusLabel.text = "Cannot resolve '$path' (missing or not a supported .properties/.env file)."
        } else {
            tableModel.load(file)
            statusLabel.text = " "
        }
    }

    private fun currentFileOrWarn(): ResolvedFile? {
        val file = tableModel.currentFile()
        if (file == null) {
            Messages.showWarningDialog(project, "Select a resolvable properties/.env file first.", "Propsy")
        }
        return file
    }

    private fun addRow() {
        val file = currentFileOrWarn() ?: return
        val key = Messages.showInputDialog(
            project, "Property key:", "Add Property", null,
        )?.trim()
        if (key.isNullOrEmpty()) return
        val added = file.addEntry(project, key, "")
        if (!added) {
            Messages.showWarningDialog(project, "Key '$key' already exists.", "Add Property")
            return
        }
        reloadSelectedFile()
    }

    private fun removeRow() {
        val row = table.selectedRow
        if (row < 0) return
        val file = tableModel.currentFile() ?: return
        val entry = tableModel.entryAt(table.convertRowIndexToModel(row)) ?: return
        file.deleteEntry(project, entry)
        reloadSelectedFile()
    }
}
