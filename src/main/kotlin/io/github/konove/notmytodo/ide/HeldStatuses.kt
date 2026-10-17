package io.github.konove.notmytodo.ide

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import io.github.konove.notmytodo.model.Status
import java.time.Instant

/**
 * The statuses 0.2 showed in place of what its items file said and had not yet written, kept in
 * the workspace file. They are read once, when that file is imported into the store, and then cleared.
 */
@Service(Service.Level.PROJECT)
@State(name = "NotMyTodoHeld", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HeldStatuses : PersistentStateComponent<HeldStatuses.Values> {
    /** By item id: the status the file has, the status that is shown and the time it was set, with spaces between. */
    data class Values(var statuses: MutableMap<String, String> = LinkedHashMap())

    @Volatile
    private var current = Values()

    override fun getState(): Values = current

    override fun loadState(state: Values) {
        current = state
    }

    /** What 0.2 held for an item: the status the file has, the one shown in its place, and when it was set, when that can be read. */
    data class Held(val from: Status, val to: Status, val time: String?)

    /** By item id: what was held for it. */
    fun held(): Map<String, Held> = current.statuses.mapNotNull { (id, text) ->
        val parts = text.split(' ')
        val from = parts.getOrNull(0)?.let(Status::fromJson)
        val to = parts.getOrNull(1)?.let(Status::fromJson)
        // A time that cannot be read is dropped: the item then keeps the updated its file has.
        val time = parts.getOrNull(2)?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
        if (from == null || to == null) null else id to Held(from, to, time)
    }.toMap()

    fun clear() {
        current = Values()
    }
}
