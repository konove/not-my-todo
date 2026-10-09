package io.github.konove.notmytodo.ide

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Hold
import io.github.konove.notmytodo.store.HoldKeeper

/**
 * The statuses the store holds back from the items file, kept in the workspace file: they are mine
 * alone until they are written, and nothing in the project changes for them.
 */
@Service(Service.Level.PROJECT)
@State(name = "NotMyTodoHeld", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HeldStatuses : PersistentStateComponent<HeldStatuses.Values>, HoldKeeper {
    /** By item id: the status the file has, the status that is shown and the time it was set, with spaces between. */
    data class Values(var statuses: MutableMap<String, String> = LinkedHashMap())

    @Volatile
    private var current = Values()

    override fun getState(): Values = current

    override fun loadState(state: Values) {
        current = state
    }

    override fun load(): Map<String, Hold> = current.statuses.mapNotNull { (id, text) ->
        val parts = text.split(' ')
        val from = parts.getOrNull(0)?.let(Status::fromJson)
        val to = parts.getOrNull(1)?.let(Status::fromJson)
        val updated = parts.getOrNull(2)
        if (from == null || to == null || updated == null) null else id to Hold(from, to, updated)
    }.toMap()

    override fun save(holds: Map<String, Hold>) {
        current = Values(holds.mapValuesTo(LinkedHashMap()) { (_, hold) -> "${hold.from.json} ${hold.to.json} ${hold.updated}" })
    }
}
