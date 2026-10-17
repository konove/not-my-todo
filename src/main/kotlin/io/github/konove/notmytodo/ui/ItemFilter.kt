package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem

enum class Scope(val label: String) {
    ALL("All"), THIS_FILE("This file"), NOTES("Notes"), ANCHOR_LOST("Anchor lost"),
    AGENT_READY("Agent can fix"), NEEDS_DECISION("Needs my decision")
}

enum class ItemSort(val label: String) {
    PRIORITY("Priority"), UPDATED("Last Updated"), FILE("File"), ID("ID")
}

/**
 * Words in [text] are looked for in the id, title, details and tags; `!p1`..`!p3` asks for a priority and `!s`, `!m` or `!l` for an effort. A priority is the one [Links.priority] gives: a parent has that of its open parts. An empty set of priorities, statuses or authors means any. A status asked for by name is shown even when closed.
 */
data class ItemQuery(
    val text: String = "",
    val scope: Scope = Scope.ALL,
    val currentPath: String? = null,
    val showClosed: Boolean = false,
    val tag: String? = null,
    val priorities: Set<Priority> = emptySet(),
    val statuses: Set<Status> = emptySet(),
    val authors: Set<Author> = emptySet(),
    val sort: ItemSort = ItemSort.PRIORITY,
)

object ItemFilter {
    private val priorityToken = Regex("!p[123]")
    private val effortToken = Regex("![sml]")

    fun apply(items: List<TodoItem>, query: ItemQuery): List<TodoItem> {
        val words = query.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val links = Links(items)
        return items
            // An item asked for by its id is shown even when closed.
            .filter { query.showClosed || !it.isClosed || it.status in query.statuses || it.id.lowercase() in words }
            .filter { query.priorities.isEmpty() || links.priority(it) in query.priorities }
            .filter { query.statuses.isEmpty() || it.status in query.statuses }
            .filter { query.authors.isEmpty() || it.author in query.authors }
            .filter { inScope(it, query, links) }
            .filter { query.tag == null || query.tag in it.tags }
            .filter { item -> words.all { matches(item, it, links) } }
            .sortedWith(order(query.sort, links))
    }

    /** The number of [item]'s id, whatever project it is of; 0 for what has no item id. */
    private fun number(item: TodoItem): Int = ItemId.parse(item.id.substringAfterLast('/')) ?: 0

    private fun order(sort: ItemSort, links: Links): Comparator<TodoItem> {
        // Within a priority the quick ones come first; an item nobody has sized goes after the large ones. The same number in two projects goes by project.
        val byPriority = compareBy<TodoItem> { links.priority(it) }.thenBy { it.effort?.ordinal ?: Effort.entries.size }.thenBy(::number).thenBy { it.id }
        return when (sort) {
            ItemSort.PRIORITY -> byPriority
            ItemSort.UPDATED -> compareByDescending<TodoItem> { it.updated }.then(byPriority)
            // Notes have no file and go last.
            ItemSort.FILE -> compareBy<TodoItem>({ it.anchor == null }, { it.anchor?.path }, { it.anchor?.startLine }).then(byPriority)
            ItemSort.ID -> compareBy(::number).thenBy { it.id }
        }
    }

    private fun inScope(item: TodoItem, query: ItemQuery, links: Links): Boolean = when (query.scope) {
        Scope.ALL -> true
        // The path of another project's item is in another checkout, whatever it says.
        Scope.THIS_FILE -> query.currentPath != null && '/' !in item.id && item.anchors.any { it.path == query.currentPath }
        Scope.NOTES -> item.anchors.isEmpty()
        Scope.ANCHOR_LOST -> item.anyLost
        Scope.AGENT_READY ->
            !item.needsDecision && !links.isBlocked(item) && (item.status == Status.OPEN || item.status == Status.IN_PROGRESS)
        Scope.NEEDS_DECISION -> item.needsDecision
    }

    private fun matches(item: TodoItem, word: String, links: Links): Boolean = when {
        priorityToken.matches(word) -> links.priority(item).json == word.drop(1)
        effortToken.matches(word) -> item.effort?.json == word.drop(1)
        word.startsWith("#") && word.length > 1 -> word.drop(1) in item.tags
        else -> item.id.lowercase().contains(word) ||
            item.title.lowercase().contains(word) ||
            item.details.lowercase().contains(word) ||
            item.tags.any { it.contains(word) }
    }
}
