package io.github.konove.notmytodo.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Where 0.2 kept the items of one project, kept in the workspace file. Only read now, to import that file into the store. */
@Service(Service.Level.PROJECT)
@State(name = "NotMyTodoProject", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TodoProjectSettings : PersistentStateComponent<TodoProjectSettings.Values> {
    data class Values(var itemsFile: String = DEFAULT_ITEMS_FILE)

    private var current = Values()

    /** The items file 0.2 used, relative to the project root, with forward slashes. */
    var itemsFile: String
        get() = current.itemsFile
        set(value) {
            current = Values(value)
        }

    override fun getState(): Values = current

    override fun loadState(state: Values) {
        current = state
    }

    companion object {
        const val DEFAULT_ITEMS_FILE = ".todos/items.json"

        fun getInstance(project: Project): TodoProjectSettings = project.service()
    }
}
