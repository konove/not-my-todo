package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem

/** Word from an agent about [item] that I should see: [comment], the newest it has just added. */
data class HandBack(val item: TodoItem, val comment: Comment)

/**
 * Finds what agents have sent back. The store is the way back from an agent, whatever the prompt
 * went out through: an agent says what it found or asks its question in a comment.
 */
object HandBacks {
    /**
     * The items in [after] an agent has commented on since [before], of those that wait for my
     * decision or that are in progress. An item that is not in [before] is new, and is not reported.
     */
    fun between(before: List<TodoItem>, after: List<TodoItem>): List<HandBack> {
        val old = before.associateBy { it.id }
        return after.mapNotNull { item ->
            val was = old[item.id] ?: return@mapNotNull null
            if (!item.needsDecision && item.status != Status.IN_PROGRESS) return@mapNotNull null
            // Comments are only ever added to, so the new ones are those past the old count.
            item.comments.drop(was.comments.size).lastOrNull { it.author == Author.AGENT }?.let { HandBack(item, it) }
        }
    }
}
