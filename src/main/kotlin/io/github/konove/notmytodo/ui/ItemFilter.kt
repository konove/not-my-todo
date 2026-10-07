package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem

enum class Scope(val label: String) {
    ALL("All"), THIS_FILE("This file"), NOTES("Notes"), ANCHOR_LOST("Anchor lost"),
    AGENT_READY("Agent can fix"), NEEDS_DECISION("Needs my decision")
}

enum class ItemSort(val label: String) {
    PRIORITY("Priority"), UPDATED("Last Updated"), FILE("File")
}

/**
 * Words in [text] are looked for in the id, title, details and tags. An empty set of priorities, statuses or authors means any. A status asked for by name is shown even when closed.
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

    fun apply(items: List<TodoItem>, query: ItemQuery): List<TodoItem> {
        val words = query.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return items
            // An item asked for by its id is shown even when closed.
            .filter { query.showClosed || !it.isClosed || it.status in query.statuses || it.id.lowercase() in words }
            .filter { query.priorities.isEmpty() || it.priority in query.priorities }
            .filter { query.statuses.isEmpty() || it.status in query.statuses }
            .filter { query.authors.isEmpty() || it.author in query.authors }
            .filter { inScope(it, query) }
            .filter { query.tag == null || query.tag in it.tags }
            .filter { item -> words.all { matches(item, it) } }
            .sortedWith(order(query.sort))
    }

    private fun order(sort: ItemSort): Comparator<TodoItem> {
        val byPriority = compareBy<TodoItem> { it.priority }.thenBy { it.number }
        return when (sort) {
            ItemSort.PRIORITY -> byPriority
            ItemSort.UPDATED -> compareByDescending<TodoItem> { it.updated }.then(byPriority)
            // Notes have no file and go last.
            ItemSort.FILE -> compareBy<TodoItem>({ it.anchor == null }, { it.anchor?.path }, { it.anchor?.startLine }).then(byPriority)
        }
    }

    private fun inScope(item: TodoItem, query: ItemQuery): Boolean = when (query.scope) {
        Scope.ALL -> true
        Scope.THIS_FILE -> query.currentPath != null && item.anchor?.path == query.currentPath
        Scope.NOTES -> item.anchor == null
        Scope.ANCHOR_LOST -> item.anchor?.lost == true
        Scope.AGENT_READY -> !item.needsDecision && (item.status == Status.OPEN || item.status == Status.IN_PROGRESS)
        Scope.NEEDS_DECISION -> item.needsDecision
    }

    private fun matches(item: TodoItem, word: String): Boolean = when {
        priorityToken.matches(word) -> item.priority.json == word.drop(1)
        word.startsWith("#") && word.length > 1 -> word.drop(1) in item.tags
        else -> item.id.lowercase().contains(word) ||
            item.title.lowercase().contains(word) ||
            item.details.lowercase().contains(word) ||
            item.tags.any { it.contains(word) }
    }
}
