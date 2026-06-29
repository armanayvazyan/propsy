package io.github.armanayvazyan.propstableview

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import javax.swing.JComponent

/**
 * Settings page under Settings | Tools | Properties Table View.
 * Edits the per-project list of .properties paths (relative to the project root).
 */
class PropsViewConfigurable(private val project: Project) : Configurable {

    private val listModel = CollectionListModel<String>()
    private val list = JBList(listModel)
    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Properties Table View"

    override fun createComponent(): JComponent {
        list.emptyText.text = "No properties files configured"
        val built = ToolbarDecorator.createDecorator(list)
            .setAddAction { chooseAndAdd() }
            .setRemoveAction { removeSelected() }
            .createPanel()
        panel = built
        reset()
        return built
    }

    private fun chooseAndAdd() {
        val base = project.guessProjectDir()
        if (base == null) {
            Messages.showWarningDialog(project, "Project base directory is unknown.", "Add Path")
            return
        }
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Properties File")
            .withFileFilter { it.extension.equals("properties", ignoreCase = true) }
            .withRoots(base)
        val chosen = FileChooser.chooseFile(descriptor, project, base) ?: return
        val rel = VfsUtilCore.getRelativePath(chosen, base, '/')
        if (rel == null) {
            Messages.showWarningDialog(project, "File must live inside the project.", "Add Path")
            return
        }
        if (!listModel.items.contains(rel)) {
            listModel.add(rel)
        }
    }

    private fun removeSelected() {
        val index = list.selectedIndex
        if (index >= 0) listModel.remove(index)
    }

    override fun isModified(): Boolean =
        listModel.items != PropsViewSettings.getInstance(project).paths

    override fun apply() {
        val settings = PropsViewSettings.getInstance(project)
        settings.paths = listModel.items
        project.messageBus.syncPublisher(PropsViewSettings.CHANGED_TOPIC).run()
    }

    override fun reset() {
        listModel.replaceAll(PropsViewSettings.getInstance(project).paths)
    }
}
