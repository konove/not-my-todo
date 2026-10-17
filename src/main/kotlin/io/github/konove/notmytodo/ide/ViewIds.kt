package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.TodoItem

/**
 * Items as an IDE project names them: its own by their ids, those of another project by their
 * qualified ids, `infra/T-3`, so that the same `T-3` of two projects never stands for one item.
 * The links of another project's item are named the same way, so that one list can follow them.
 */
object ViewIds {
    /** [item] of project [key] as project [home] names it. */
    fun shown(item: TodoItem, key: String, home: String): TodoItem {
        if (key == home) return item
        fun ref(r: String) = ItemRef.parse(r)?.let { ItemRef(it.project ?: key, it.id).storedFrom(home) } ?: r
        return item.copy(
            id = "$key/${item.id}",
            blockedBy = item.blockedBy.map(::ref),
            duplicateOf = item.duplicateOf?.let(::ref),
            parent = item.parent?.let(::ref),
        )
    }

    /** [ref] as [home] shows it, where a bare id is one of its own, qualified: a store makes a ref to itself bare again. */
    fun stored(ref: String, home: String): String =
        ItemRef.parse(ref)?.let { ItemRef(it.project ?: home, it.id).toString() } ?: ref

    /** The project and the bare id that [viewId] names; null when it is no item id. */
    fun split(viewId: String, home: String): ItemRef? = ItemRef.parse(viewId)?.let { ItemRef(it.project ?: home, it.id) }

    fun isElsewhere(viewId: String): Boolean = '/' in viewId
}
