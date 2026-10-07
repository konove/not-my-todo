package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HandBacksTest {
    private val asked = Comment(Author.AGENT, "2026-10-06T09:00:00Z", "per unit or per cell?")
    private val sent = TodoItem("T-1", "cache findPath", status = Status.IN_PROGRESS)
    private val waiting = TodoItem("T-2", "pick a license", tags = listOf("needs-decision"))

    private fun ids(before: List<TodoItem>, after: List<TodoItem>) = HandBacks.between(before, after).map { it.item.id }

    @Test
    fun `an agent's comment on an item that was sent off or that waits for me is a hand-back`() {
        val after = listOf(sent.copy(comments = listOf(asked)), waiting.copy(comments = listOf(asked)))
        val found = HandBacks.between(listOf(sent, waiting), after)
        assertEquals(listOf("T-1", "T-2"), found.map { it.item.id })
        assertEquals(listOf(asked.text, asked.text), found.map { it.said })
    }

    @Test
    fun `the tag and the comment may come in one write`() {
        val open = TodoItem("T-3", "a")
        assertEquals(listOf("T-3"), ids(listOf(open), listOf(open.copy(tags = listOf("needs-decision"), comments = listOf(asked)))))
    }

    @Test
    fun `my own comments and the tag alone are not hand-backs`() {
        val mine = Comment(Author.USER, "2026-10-06T09:05:00Z", "per cell")
        assertEquals(emptyList<String>(), ids(listOf(sent), listOf(sent.copy(comments = listOf(mine)))))
        assertEquals(emptyList<String>(), ids(listOf(sent), listOf(sent.copy(tags = listOf("needs-decision")))))
    }

    @Test
    fun `a comment that was already there is not reported again`() {
        val before = sent.copy(comments = listOf(asked))
        assertEquals(emptyList<String>(), ids(listOf(before), listOf(before.copy(title = "cache it"))))
        assertEquals(emptyList<String>(), ids(listOf(before), listOf(before.copy(tags = listOf("needs-decision")))))
    }

    @Test
    fun `a note on an item nobody waits for, or on a new item, is left alone`() {
        val open = TodoItem("T-3", "a")
        val fixed = sent.copy(status = Status.FIXED)
        assertEquals(emptyList<String>(), ids(listOf(open, sent), listOf(open.copy(comments = listOf(asked)), fixed.copy(comments = listOf(asked)))))
        assertEquals(emptyList<String>(), ids(emptyList(), listOf(waiting.copy(comments = listOf(asked)))))
    }

    @Test
    fun `the newest of several new comments by the agent is the one shown`() {
        val later = Comment(Author.AGENT, "2026-10-06T09:10:00Z", "going with per cell unless you say otherwise")
        val found = HandBacks.between(listOf(sent), listOf(sent.copy(comments = listOf(asked, later))))
        assertEquals(listOf(later.text), found.map { it.said })
    }

    @Test
    fun `what an agent says is to be decided is a hand-back, once`() {
        val open = TodoItem("T-3", "a")
        val asking = open.copy(tags = listOf("needs-decision"), toDecide = "MIT or Apache?")
        val found = HandBacks.between(listOf(open), listOf(asking))
        assertEquals(listOf("T-3" to "MIT or Apache?"), found.map { it.item.id to it.said })
        assertEquals(emptyList<String>(), ids(listOf(asking), listOf(asking.copy(title = "b"))))
        assertEquals(emptyList<String>(), ids(listOf(asking), listOf(asking.copy(tags = emptyList(), toDecide = null))))
    }
}
