package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemFilterTest {
    private fun anchor(path: String, lost: Boolean = false) = Anchor(path, 1, 1, "x", emptyList(), emptyList(), lost)
    private val items = listOf(
        TodoItem("T-1", "Cache findPath", "squad wide", Priority.P2, listOf("perf"), anchors = listOf(anchor("src/unit.cpp"))),
        TodoItem("T-2", "Decide save versioning", priority = Priority.P1, tags = listOf("design")),
        TodoItem("T-10", "Rename costAt", priority = Priority.P1, tags = listOf("cleanup"), anchors = listOf(anchor("src/grid.h", lost = true))),
        TodoItem("T-9", "Old bug", priority = Priority.P1, status = Status.DONE, anchors = listOf(anchor("src/unit.cpp"))),
        TodoItem("T-3", "Skipped", priority = Priority.P3, status = Status.WONT_FIX),
    )
    private fun ids(q: ItemQuery) = ItemFilter.apply(items, q).map { it.id }

    @Test
    fun `sorted by priority then by number and closed items hidden`() {
        assertEquals(listOf("T-2", "T-10", "T-1"), ids(ItemQuery()))
    }

    @Test
    fun `search finds an item by its id, closed or not`() {
        assertEquals(listOf("T-10", "T-1"), ids(ItemQuery("t-1")))
        assertEquals(listOf("T-10"), ids(ItemQuery("T-10")))
        assertEquals(listOf("T-9"), ids(ItemQuery("t-9")))
        assertEquals(listOf("T-2"), ids(ItemQuery("2")))
    }

    @Test
    fun `show closed includes done and wont fix`() {
        assertEquals(listOf("T-2", "T-9", "T-10", "T-1", "T-3"), ids(ItemQuery(showClosed = true)))
    }

    @Test
    fun `scopes`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(scope = Scope.THIS_FILE, currentPath = "src/unit.cpp")))
        assertEquals(emptyList<String>(), ids(ItemQuery(scope = Scope.THIS_FILE, currentPath = null)))
        assertEquals(listOf("T-2"), ids(ItemQuery(scope = Scope.NOTES)))
        assertEquals(listOf("T-10"), ids(ItemQuery(scope = Scope.ANCHOR_LOST)))
    }

    @Test
    fun `search words match title, details and tags, ignoring case`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(text = "FINDPATH")))
        assertEquals(listOf("T-1"), ids(ItemQuery(text = "squad")))
        assertEquals(listOf("T-10"), ids(ItemQuery(text = "clean")))
        assertEquals(emptyList<String>(), ids(ItemQuery(text = "cache versioning")))
    }

    @Test
    fun `tag and priority tokens filter exactly`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(text = "#perf")))
        assertEquals(emptyList<String>(), ids(ItemQuery(text = "#per")))
        assertEquals(listOf("T-2", "T-10"), ids(ItemQuery(text = "!p1")))
        assertEquals(listOf("T-10"), ids(ItemQuery(text = "!P1 rename")))
    }

    @Test
    fun `tag of the query narrows the scope`() {
        assertEquals(listOf("T-10"), ids(ItemQuery(tag = "cleanup")))
        assertEquals(emptyList<String>(), ids(ItemQuery(tag = "cleanup", scope = Scope.NOTES)))
    }

    @Test
    fun `priority, status and author filters`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(priorities = setOf(Priority.P2, Priority.P3))))
        assertEquals(listOf("T-9"), ids(ItemQuery(statuses = setOf(Status.DONE))))
        assertEquals(emptyList<String>(), ids(ItemQuery(authors = setOf(io.github.konove.notmytodo.model.Author.AGENT))))
    }

    @Test
    fun `sort by file puts notes last`() {
        assertEquals(listOf("T-10", "T-1", "T-2"), ids(ItemQuery(sort = ItemSort.FILE)))
    }

    @Test
    fun `sort by id goes by number whatever the priority`() {
        assertEquals(listOf("T-1", "T-2", "T-10"), ids(ItemQuery(sort = ItemSort.ID)))
        assertEquals(listOf("T-1", "T-2", "T-3", "T-9", "T-10"), ids(ItemQuery(showClosed = true, sort = ItemSort.ID)))
    }

    @Test
    fun `sorted by id a part still follows its parent`() {
        val early = TodoItem("T-1", "early", parent = "T-3")
        val between = TodoItem("T-2", "between")
        val whole = TodoItem("T-3", "whole")
        val late = TodoItem("T-4", "late", parent = "T-3")
        val all = listOf(early, between, whole, late)
        val shown = ItemFilter.apply(all, ItemQuery(sort = ItemSort.ID))
        assertEquals(listOf("T-1", "T-2", "T-3", "T-4"), shown.map { it.id })
        assertEquals(
            listOf("T-2" to 0, "T-3" to 0, "T-1" to 1, "T-4" to 1),
            ItemGroups.rows(shown, GroupBy.NONE, emptySet(), Links(all)).map { (it as ItemRow).item.id to it.depth },
        )
    }

    @Test
    fun `rows are grouped under headings and collapsed groups keep only the heading`() {
        val shown = ItemFilter.apply(items, ItemQuery())
        assertEquals(shown.map { ItemRow(it) }, ItemGroups.rows(shown, GroupBy.NONE, emptySet()))
        assertEquals(
            listOf<Any>(
                GroupHeader("src/grid.h", "src/grid.h", 1, false, 0, GroupKind.FILE), ItemRow(shown[1], 1),
                GroupHeader("src/unit.cpp", "src/unit.cpp", 1, true, 0, GroupKind.FILE),
                GroupHeader("Notes", "Notes", 1, false), ItemRow(shown[0], 1),
            ),
            ItemGroups.rows(shown, GroupBy.FILE, setOf("src/unit.cpp")),
        )
    }

    @Test
    fun `directory tree nests files in directories and joins single-child directories`() {
        fun at(path: String, id: String) = TodoItem(id, id, anchors = listOf(anchor(path)))
        val a = at("lib/core/io/read.c", "T-1")
        val b = at("lib/core/io/write.c", "T-2")
        val c = at("top.c", "T-3")
        val note = TodoItem("T-4", "note")
        assertEquals(
            listOf<Any>(
                GroupHeader("lib/core/io", "lib/core/io", 2, false, 0, GroupKind.DIRECTORY),
                GroupHeader("lib/core/io/read.c", "read.c", 1, false, 1, GroupKind.FILE), ItemRow(a, 2),
                GroupHeader("lib/core/io/write.c", "write.c", 1, true, 1, GroupKind.FILE),
                GroupHeader("top.c", "top.c", 1, false, 0, GroupKind.FILE), ItemRow(c, 1),
                GroupHeader("Notes", "Notes", 1, false), ItemRow(note, 1),
            ),
            ItemGroups.rows(listOf(a, b, c, note), GroupBy.DIRECTORY, setOf("lib/core/io/write.c")),
        )
        assertEquals(
            listOf<Any>(GroupHeader("lib/core/io", "lib/core/io", 2, true, 0, GroupKind.DIRECTORY)),
            ItemGroups.rows(listOf(a, b), GroupBy.DIRECTORY, ItemGroups.allKeys(listOf(a, b), GroupBy.DIRECTORY)),
        )
    }

    @Test
    fun `needs-decision tag splits what an agent can fix from what waits for me`() {
        val waiting = TodoItem("T-20", "Pick a format", tags = listOf("needs-decision"))
        val all = items + waiting
        assertEquals(listOf("T-20"), ItemFilter.apply(all, ItemQuery(scope = Scope.NEEDS_DECISION)).map { it.id })
        assertEquals(
            listOf("T-2", "T-10", "T-1"),
            ItemFilter.apply(all, ItemQuery(scope = Scope.AGENT_READY, showClosed = true)).map { it.id },
        )
    }

    @Test
    fun `details render as markdown and keep their line breaks`() {
        val html = Markdown.html("**Needs** `code`\n1. one\n   a. first\n   b. second\n```\nx\ny\n```")
        assertTrue(html, "<strong>Needs</strong>" in html && "<code>code</code>" in html && "<ol>" in html)
        assertTrue(html, Regex("a\\. first\\s*<br").containsMatchIn(html))
        assertFalse(html, Regex("x\\s*<br").containsMatchIn(html))
    }

    @Test
    fun `a data-policy block folds into one line numbered through the page and opens when asked`() {
        val policy = "```text\n<dataSource:chat>\n<dataPolicy:chat>\nUse it only here.\n</dataPolicy:chat>\n</dataSource:chat>\n```"
        val text = "from Chat\n```\nplain code\n```\n$policy\nafter"
        val folded = Markdown.body(text, Markdown.Folds(emptySet(), "#999999"))
        assertTrue(folded, "<a href=\"notmytodo-policy:0\"><font color=\"#999999\">▸ Chat data policy</font></a>" in folded)
        assertTrue(folded, "plain code" in folded && "from Chat" in folded && "after" in folded)
        assertFalse(folded, "Use it only here." in folded)
        val folds = Markdown.Folds(setOf(1), "#999999")
        val first = Markdown.body(policy, folds)
        val second = Markdown.body(policy.replace("chat", "tracker"), folds)
        assertFalse(first, "Use it only here." in first)
        assertTrue(second, "▾ Tracker data policy" in second && "notmytodo-policy:1" in second && "Use it only here." in second)
        assertTrue(Markdown.body(policy).contains("Use it only here."))
        assertEquals(1, Markdown.Folds.index("notmytodo-policy:1"))
        assertEquals(null, Markdown.Folds.index("https://example.com"))
    }

    @Test
    fun `an item is in a file, or lost, by any of its anchors`() {
        val two = TodoItem("T-20", "two places", anchors = listOf(anchor("src/a.cpp"), anchor("src/b.cpp", lost = true)))
        val whole = TodoItem("T-21", "whole file", anchors = listOf(Anchor.file("src/b.cpp")))
        val all = listOf(two, whole, TodoItem("T-22", "note"))
        fun ids(scope: Scope, path: String? = null) = ItemFilter.apply(all, ItemQuery(scope = scope, currentPath = path)).map { it.id }
        assertEquals(listOf("T-20", "T-21"), ids(Scope.THIS_FILE, "src/b.cpp"))
        assertEquals(listOf("T-20"), ids(Scope.THIS_FILE, "src/a.cpp"))
        assertEquals(listOf("T-20"), ids(Scope.ANCHOR_LOST))
        assertEquals(listOf("T-22"), ids(Scope.NOTES))
    }

    @Test
    fun `a blocked item is not one an agent can fix`() {
        val all = listOf(
            TodoItem("T-1", "first"), TodoItem("T-2", "waits", blockedBy = listOf("T-1")),
            TodoItem("T-3", "closed", status = Status.DONE), TodoItem("T-4", "free again", blockedBy = listOf("T-3")),
        )
        assertEquals(listOf("T-1", "T-4"), ItemFilter.apply(all, ItemQuery(scope = Scope.AGENT_READY)).map { it.id })
    }

    @Test
    fun `an item blocked by an open item of another project is not one an agent can fix`() {
        val blocker = TodoItem("infra/T-9", "theirs")
        val all = listOf(TodoItem("T-1", "waits", blockedBy = listOf("infra/T-9")), TodoItem("T-2", "free"))
        val links = Links(all) { if (it == blocker.id) blocker else null }
        assertEquals(listOf("T-2"), ItemFilter.apply(all, ItemQuery(scope = Scope.AGENT_READY), links).map { it.id })
    }

    @Test
    fun `a part stands under its parent when both are under the same heading`() {
        val whole = TodoItem("T-1", "whole", priority = Priority.P3)
        val first = TodoItem("T-2", "first", priority = Priority.P1, parent = "T-1", blockedBy = listOf("T-3"))
        val other = TodoItem("T-3", "other", priority = Priority.P2)
        val closed = TodoItem("T-4", "closed", status = Status.DONE, parent = "T-1")
        val all = listOf(whole, first, other, closed)
        val links = Links(all)
        val shown = ItemFilter.apply(all, ItemQuery())
        // The parent has the priority of its open part, and so comes before it.
        assertEquals(listOf("T-1", "T-2", "T-3"), shown.map { it.id })
        assertEquals(
            listOf(ItemRow(whole, 0, false, 1, 2, Priority.P1), ItemRow(first, 1, true), ItemRow(other)),
            ItemGroups.rows(shown, GroupBy.NONE, emptySet(), links),
        )
        assertEquals(
            listOf<Any>(
                GroupHeader("P1", "P1", 2, false), ItemRow(whole, 1, false, 1, 2, Priority.P1), ItemRow(first, 2, true),
                GroupHeader("P2", "P2", 1, false), ItemRow(other, 1),
            ),
            ItemGroups.rows(shown, GroupBy.PRIORITY, emptySet(), links),
        )
    }

    @Test
    fun `a parent has the highest priority among its open parts, and its own when it has none`() {
        val whole = TodoItem("T-1", "whole", priority = Priority.P1)
        val low = TodoItem("T-2", "low", priority = Priority.P3, parent = "T-1")
        val middle = TodoItem("T-3", "middle", priority = Priority.P2, parent = "T-1")
        val closed = TodoItem("T-4", "closed", priority = Priority.P1, status = Status.DONE, parent = "T-1")
        fun of(vararg parts: TodoItem) = Links(listOf(whole) + parts).priority(whole)
        assertEquals(Priority.P1, of())
        assertEquals(Priority.P3, of(low))
        assertEquals(Priority.P2, of(low, middle, closed))
        assertEquals(Priority.P2, of(middle.copy(status = Status.FIXED)))
        assertEquals(Priority.P1, of(closed, low.copy(status = Status.WONT_FIX)))
        assertEquals(Priority.P3, Links(listOf(whole, low)).priority(low))
    }

    @Test
    fun `a parent is filtered by the priority of its parts, listed or not`() {
        val all = listOf(
            TodoItem("T-1", "whole", priority = Priority.P3),
            TodoItem("T-2", "part", priority = Priority.P2, parent = "T-1", tags = listOf("bugs")),
            TodoItem("T-3", "other", priority = Priority.P3),
        )
        fun ids(q: ItemQuery) = ItemFilter.apply(all, q).map { it.id }
        assertEquals(listOf("T-1", "T-2"), ids(ItemQuery(priorities = setOf(Priority.P2))))
        assertEquals(listOf("T-3"), ids(ItemQuery("!p3")))
        assertEquals(listOf("T-1"), ids(ItemQuery("!p2 whole")))
    }

    @Test
    fun `within a priority less effort comes first and no effort last`() {
        val sized = listOf(
            TodoItem("T-1", "none"),
            TodoItem("T-2", "large", effort = Effort.L),
            TodoItem("T-3", "small", effort = Effort.S),
            TodoItem("T-4", "small too", effort = Effort.S),
            TodoItem("T-5", "urgent and large", priority = Priority.P1, effort = Effort.L),
        )
        assertEquals(listOf("T-5", "T-3", "T-4", "T-2", "T-1"), ItemFilter.apply(sized, ItemQuery()).map { it.id })
    }

    @Test
    fun `search finds items by effort`() {
        val sized = listOf(TodoItem("T-1", "none"), TodoItem("T-2", "large", effort = Effort.L), TodoItem("T-3", "small", effort = Effort.S))
        assertEquals(listOf("T-3"), ItemFilter.apply(sized, ItemQuery("!s")).map { it.id })
        assertEquals(listOf("T-2"), ItemFilter.apply(sized, ItemQuery("!L")).map { it.id })
    }

    @Test
    fun `items of several projects sort by number, and the same number by project`() {
        val items = listOf(
            TodoItem(id = "infra/T-2", title = "b"), TodoItem(id = "T-2", title = "a"), TodoItem(id = "infra/T-1", title = "c"),
        )
        assertEquals(listOf("infra/T-1", "T-2", "infra/T-2"), ItemFilter.apply(items, ItemQuery(sort = ItemSort.ID)).map { it.id })
        assertEquals(listOf("infra/T-1", "T-2", "infra/T-2"), ItemFilter.apply(items, ItemQuery()).map { it.id })
    }

    @Test
    fun `a qualified id is found by the search`() {
        val items = listOf(TodoItem(id = "infra/T-2", title = "b"), TodoItem(id = "T-2", title = "a"))
        assertEquals(listOf("infra/T-2"), ItemFilter.apply(items, ItemQuery("infra/t-2")).map { it.id })
    }

    @Test
    fun `This file holds only this project's items`() {
        val anchor = Anchor("src/a.kt", 1, 1, "x", emptyList(), emptyList())
        val items = listOf(TodoItem(id = "infra/T-2", title = "b", anchors = listOf(anchor)), TodoItem(id = "T-2", title = "a", anchors = listOf(anchor)))
        assertEquals(listOf("T-2"), ItemFilter.apply(items, ItemQuery(scope = Scope.THIS_FILE, currentPath = "src/a.kt")).map { it.id })
    }

    @Test
    fun `This file keeps a code comment of a subdirectory`() {
        val comment = TodoItem(id = "src/a.kt:1", title = "x", anchors = listOf(Anchor("src/a.kt", 1, 1, "x", emptyList(), emptyList())))
        assertEquals(listOf("src/a.kt:1"), ItemFilter.apply(listOf(comment), ItemQuery(scope = Scope.THIS_FILE, currentPath = "src/a.kt")).map { it.id })
    }

    @Test
    fun `grouped by project, this project comes first`() {
        val items = listOf(TodoItem(id = "infra/T-1", title = "c"), TodoItem(id = "T-2", title = "a"), TodoItem(id = "app2/T-1", title = "d"))
        val headings = ItemGroups.rows(items, GroupBy.PROJECT, emptySet()).filterIsInstance<GroupHeader>().map { it.label }
        assertEquals(listOf(ItemGroups.THIS_PROJECT, "app2", "infra"), headings)
    }
}
