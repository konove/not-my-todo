package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem

/** Word from an agent about [item] that I should see: [said], what it has just written there. */
data class HandBack(val item: TodoItem, val said: String)

/**
 * Finds what agents have sent back. The store is the way back from an agent, whatever the prompt
 * went out through: an agent says what it found in a comment, and what I have to decide on the item.
 */
object HandBacks {
    /**
     * The items in [after] an agent has commented on since [before], of those that wait for my
     * decision or that are in progress, and those it has since said I have to decide something on.
     * An item that is not in [before] is new, and is not reported.
     */
    fun between(before: List<TodoItem>, after: List<TodoItem>): List<HandBack> {
        val old = before.associateBy { it.id }
        return after.mapNotNull { item ->
            val was = old[item.id] ?: return@mapNotNull null
            if (!item.needsDecision && item.status != Status.IN_PROGRESS) return@mapNotNull null
            // Only an agent writes what is to be decided, so a new text is its question.
            item.toDecide?.takeIf { it != was.toDecide }?.let { return@mapNotNull HandBack(item, it) }
            // Comments are only ever added to, so the new ones are those past the old count.
            item.comments.drop(was.comments.size).lastOrNull { it.author == Author.AGENT }?.let { HandBack(item, it.text) }
        }
    }
}
