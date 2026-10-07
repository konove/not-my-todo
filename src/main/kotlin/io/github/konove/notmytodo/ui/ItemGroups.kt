package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.ide.CodeTodos
import io.github.konove.notmytodo.model.TodoItem
import java.util.TreeMap

enum class GroupBy(val label: String) {
    NONE("Flatten View"), DIRECTORY("Directory Tree"), FILE("File"), STATUS("Status"), PRIORITY("Priority")
}

enum class GroupKind { DIRECTORY, FILE, OTHER }

/** A heading in the item list. [key] names it among the collapsed groups; [depth] is its nesting level. */
data class GroupHeader(
    val key: String, val label: String, val count: Int, val collapsed: Boolean,
    val depth: Int = 0, val kind: GroupKind = GroupKind.OTHER,
)

/** An item in the item list, [depth] levels in. */
data class ItemRow(val item: TodoItem, val depth: Int = 0)

object ItemGroups {
    const val NOTES = "Notes"

    /**
     * The rows of the list: [items] in the order given, under headings. A heading whose key is in
     * [collapsed] hides everything below it. Code comments have no status or priority and are
     * left flat when asked to group by those.
     */
    fun rows(items: List<TodoItem>, by: GroupBy, collapsed: Set<String>): List<Any> {
        if (by == GroupBy.DIRECTORY) return tree(items, collapsed)
        val keys = items.map { key(it, by) ?: return items.map(::ItemRow) }
        val groups = items.indices.groupBy { keys[it] }.toSortedMap(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
        val kind = if (by == GroupBy.FILE) GroupKind.FILE else GroupKind.OTHER
        return groups.flatMap { (key, members) ->
            val label = key.second
            val closed = label in collapsed
            listOf(GroupHeader(label, label, members.size, closed, 0, if (label == NOTES) GroupKind.OTHER else kind)) +
                if (closed) emptyList() else members.map { ItemRow(items[it], 1) }
        }
    }

    /** The key of every heading [rows] can show, for collapsing them all. */
    fun allKeys(items: List<TodoItem>, by: GroupBy): Set<String> =
        rows(items, by, emptySet()).filterIsInstance<GroupHeader>().mapTo(HashSet()) { it.key }

    /** The rank and label of [item]'s group, or null when [by] does not apply to it. */
    private fun key(item: TodoItem, by: GroupBy): Pair<Int, String>? {
        val path = item.anchor?.path
        return when (by) {
            GroupBy.NONE, GroupBy.DIRECTORY -> null
            GroupBy.FILE -> path?.let { 0 to it } ?: (1 to NOTES)
            GroupBy.STATUS -> if (CodeTodos.isCode(item)) null else item.status.ordinal to item.status.label
            GroupBy.PRIORITY -> if (CodeTodos.isCode(item)) null else item.priority.ordinal to item.priority.name
        }
    }

    private class Dir(val path: String) {
        val dirs = TreeMap<String, Dir>()
        val files = TreeMap<String, MutableList<TodoItem>>()
        val count: Int get() = dirs.values.sumOf { it.count } + files.values.sumOf { it.size }
    }

    /** Directories, then their files, then the items; a directory holding only one directory is shown joined to it. */
    private fun tree(items: List<TodoItem>, collapsed: Set<String>): List<Any> {
        val root = Dir("")
        val notes = ArrayList<TodoItem>()
        for (item in items) {
            val path = item.anchor?.path
            if (path == null) {
                notes += item
                continue
            }
            var dir = root
            val parts = path.split('/')
            for (name in parts.dropLast(1)) {
                val parent = dir
                dir = parent.dirs.getOrPut(name) { Dir(if (parent.path.isEmpty()) name else "${parent.path}/$name") }
            }
            dir.files.getOrPut(parts.last()) { ArrayList() } += item
        }
        val out = ArrayList<Any>()
        fun emit(dir: Dir, depth: Int) {
            for ((name, child) in dir.dirs) {
                var shown = child
                var label = name
                while (shown.files.isEmpty() && shown.dirs.size == 1) {
                    shown = shown.dirs.values.first()
                    label = "$label/${shown.path.substringAfterLast('/')}"
                }
                val closed = shown.path in collapsed
                out += GroupHeader(shown.path, label, shown.count, closed, depth, GroupKind.DIRECTORY)
                if (!closed) emit(shown, depth + 1)
            }
            for ((name, members) in dir.files) {
                val key = if (dir.path.isEmpty()) name else "${dir.path}/$name"
                val closed = key in collapsed
                out += GroupHeader(key, name, members.size, closed, depth, GroupKind.FILE)
                if (!closed) members.forEach { out += ItemRow(it, depth + 1) }
            }
        }
        emit(root, 0)
        if (notes.isNotEmpty()) {
            val closed = NOTES in collapsed
            out += GroupHeader(NOTES, NOTES, notes.size, closed)
            if (!closed) notes.forEach { out += ItemRow(it, 1) }
        }
        return out
    }
}
