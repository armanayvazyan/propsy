package io.github.armanayvazyan.propstableview

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Per-project storage for the list of .properties file paths (relative to project root)
 * that should be shown in the Properties Table tool window.
 */
@Service(Service.Level.PROJECT)
@State(name = "PropsViewSettings", storages = [Storage("propsTableView.xml")])
class PropsViewSettings : PersistentStateComponent<PropsViewSettings.State> {

    class State {
        var paths: MutableList<String> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /** Paths relative to the project base dir. Always returns a defensive copy. */
    var paths: List<String>
        get() = myState.paths.toList()
        set(value) {
            myState.paths = value.toMutableList()
        }

    companion object {
        val CHANGED_TOPIC: Topic<Runnable> =
            Topic.create("Properties Table View settings changed", Runnable::class.java)

        fun getInstance(project: Project): PropsViewSettings = project.service()
    }
}
