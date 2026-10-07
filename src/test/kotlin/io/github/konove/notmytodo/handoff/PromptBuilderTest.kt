package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class PromptBuilderTest {
    private val fileText = (1..60).joinToString("\n") { "line $it" }
    private val files = mapOf("src/unit.txt" to fileText)
    private val anchored = TodoItem(
        id = "T-14", title = "cache findPath", details = "Share one path per cell.", priority = Priority.P1,
        tags = listOf("perf", "pathing"), anchors = listOf(AnchorResolver.capture("src/unit.txt", fileText, 30, 31)),
    )

    @Test
    fun `anchored item includes the code with twenty lines either side`() {
        val p = PromptBuilder.build(anchored, files, "tests pass")
        assertTrue(p.startsWith("Fix TODO item T-14 (priority p1, tags: perf, pathing)."))
        assertTrue(p.contains("Title: cache findPath"))
        assertTrue(p.contains("Details:\nShare one path per cell."))
        assertTrue(p.contains("Location: src/unit.txt, lines 30-31."))
        assertTrue(p.contains("    10  line 10"))
        assertTrue(p.contains(">   30  line 30"))
        assertTrue(p.contains(">   31  line 31"))
        assertTrue(p.contains("    51  line 51"))
        assertFalse(p.contains("line 9\n"))
        assertFalse(p.contains("line 52"))
        assertTrue(p.contains("Done when: tests pass"))
        assertTrue(p.contains("todo_update tool with id \"T-14\" and status \"fixed\""))
    }

    @Test
    fun `note without code has no location and optional parts are left out`() {
        val note = TodoItem(id = "T-2", title = "decide save versioning")
        val p = PromptBuilder.build(note, emptyMap(), "  ")
        assertTrue(p.startsWith("Fix TODO item T-2 (priority p2)."))
        assertFalse(p.contains("Location:"))
        assertFalse(p.contains("Details:"))
        assertFalse(p.contains("Done when:"))
    }

    @Test
    fun `lost anchor shows the last known code`() {
        val lost = anchored.copy(anchors = listOf(anchored.anchor!!.copy(lost = true)))
        val p = PromptBuilder.build(lost, files, "")
        assertTrue(p.contains("Location: src/unit.txt. The exact lines could not be located"))
        assertTrue(p.contains("line 30\nline 31"))
        assertFalse(p.contains(">   30"))
    }

    @Test
    fun `command reads the prompt from a quoted file`() {
        assertEquals("claude \"\$(cat '/tmp/a b/p.md')\"", ClaudeLauncher.command(Path.of("/tmp/a b/p.md")))
        assertEquals("claude \"\$(cat '/tmp/it'\\''s/p.md')\"", ClaudeLauncher.command(Path.of("/tmp/it's/p.md")))
    }

    @Test
    fun `command keeps a custom start and adds the channel flag once`() {
        val file = Path.of("/tmp/p.md")
        assertEquals(
            "claude --model 'x y' \"\$(cat '/tmp/p.md')\"",
            ClaudeLauncher.command(file, "  claude --model 'x y' "),
        )
        assertEquals(
            "claude --dangerously-load-development-channels server:notmytodo -- \"\$(cat '/tmp/p.md')\"",
            ClaudeLauncher.command(file, "claude", channel = true),
        )
        val own = "claude --dangerously-load-development-channels server:notmytodo --model opus"
        assertEquals("$own -- \"\$(cat '/tmp/p.md')\"", ClaudeLauncher.command(file, own, channel = true))
        assertEquals("$own -- \"\$(cat '/tmp/p.md')\"", ClaudeLauncher.command(file, own, channel = false))
        assertEquals("claude \"\$(cat '/tmp/p.md')\"", ClaudeLauncher.command(file, "   "))
    }

    @Test
    fun `a TODO comment from the code asks for the comment to be removed, not for a status`() {
        val anchor = io.github.konove.notmytodo.model.Anchor("a.c", 2, 2, "// TODO: tidy", emptyList(), emptyList())
        val prompt = PromptBuilder.build(TodoItem("a.c:2", "TODO: tidy", anchors = listOf(anchor)), mapOf("a.c" to "one\n// TODO: tidy\nthree\n"), "")
        org.junit.Assert.assertTrue(prompt.startsWith("Resolve this TODO comment in the code.\n\nComment: TODO: tidy\n"))
        org.junit.Assert.assertTrue(prompt.contains("Location: a.c, lines 2-2."))
        org.junit.Assert.assertTrue(prompt.contains("remove the TODO comment"))
        org.junit.Assert.assertFalse(prompt.contains("todo_update"))
    }

    @Test
    fun `context lines follow the options, down to none`() {
        val two = PromptBuilder.build(anchored, files, "", PromptOptions(contextLines = 2))
        assertTrue(two.contains("    28  line 28"))
        assertTrue(two.contains("    33  line 33"))
        assertFalse(two.contains("line 27\n"))
        assertFalse(two.contains("line 34"))
        val none = PromptBuilder.build(anchored, files, "", PromptOptions(contextLines = 0))
        assertTrue(none.contains(">   30  line 30"))
        assertFalse(none.contains("line 29"))
        assertFalse(none.contains("line 32"))
    }

    @Test
    fun `short prompt points at todo_get and embeds no code`() {
        val p = PromptBuilder.build(anchored, files, "tests pass", PromptOptions(short = true))
        assertTrue(p.startsWith("Fix TODO item T-14 (priority p1, tags: perf, pathing)."))
        assertTrue(p.contains("Title: cache findPath"))
        assertTrue(p.contains("todo_get tool, id \"T-14\""))
        assertFalse(p.contains("Location:"))
        assertFalse(p.contains("Details:"))
        assertFalse(p.contains("line 30"))
        assertTrue(p.contains("Done when: tests pass"))
        assertTrue(p.contains("todo_update tool with id \"T-14\" and status \"fixed\""))
    }

    @Test
    fun `a TODO comment gets the full prompt even when short is asked for`() {
        val anchor = io.github.konove.notmytodo.model.Anchor("a.c", 2, 2, "// TODO: tidy", emptyList(), emptyList())
        val p = PromptBuilder.build(TodoItem("a.c:2", "TODO: tidy", anchors = listOf(anchor)), mapOf("a.c" to "one\n// TODO: tidy\nthree\n"), "", PromptOptions(short = true))
        assertTrue(p.contains("Location: a.c, lines 2-2."))
        assertFalse(p.contains("todo_get"))
    }

    @Test
    fun `extra instructions come after done when, and blank ones add nothing`() {
        val p = PromptBuilder.build(anchored, files, "tests pass", PromptOptions(extra = "  Run the linter.\n"))
        assertTrue(p.contains("Done when: tests pass\n\nRun the linter.\n"))
        assertTrue(p.indexOf("Run the linter.") < p.indexOf("When you have finished"))
        assertEquals(
            PromptBuilder.build(anchored, files, "tests pass"),
            PromptBuilder.build(anchored, files, "tests pass", PromptOptions(extra = " \n\t ")),
        )
    }

    @Test
    fun `a prompt for several items carries the extra instructions once`() {
        val note = TodoItem(id = "T-2", title = "decide save versioning")
        val p = PromptBuilder.buildAll(listOf(anchored, note), files, PromptOptions(extra = "Run the linter."))
        assertTrue(p.startsWith("Fix the following 2 TODO items, one at a time.\n\nRun the linter.\n\n"))
        assertEquals(1, Regex("Run the linter\\.").findAll(p).count())
        assertTrue(p.contains("Fix TODO item T-14"))
        assertTrue(p.contains("Fix TODO item T-2"))
    }

    @Test
    fun `the fallback names the items file in use`() {
        val p = PromptBuilder.build(anchored, files, "", PromptOptions(itemsFile = "notes/todo.json"))
        assertTrue(p.contains("for this item in notes/todo.json."))
        assertFalse(p.contains(".todos/items.json"))
    }

    @Test
    fun `an item that needs my decision asks for options and a choice before any work`() {
        val waiting = anchored.copy(tags = listOf("perf", "needs-decision"))
        val p = PromptBuilder.build(waiting, files, "tests pass", PromptOptions(itemsFile = "notes/todo.json"))
        assertTrue(p.startsWith("TODO item T-14 waits for a decision that is the user's to make (priority p1, tags: perf, needs-decision)."))
        assertTrue(p.contains("Details:\nShare one path per cell."))
        assertTrue(p.contains(">   30  line 30"))
        assertTrue(p.contains("Done when: tests pass"))
        assertTrue(p.contains("Do not change anything yet."))
        assertTrue(p.contains("give the user the options"))
        assertTrue(p.contains("ask the user to choose and wait for the answer"))
        assertTrue(p.indexOf("Do not change anything yet.") < p.indexOf("When the user has chosen"))
        assertTrue(p.contains("todo_update tool with id \"T-14\", tags \"perf\" and status \"fixed\""))
        assertTrue(p.contains("remove the \"needs-decision\" tag and set \"status\": \"fixed\" for this item in notes/todo.json."))
        assertFalse(p.contains("do not guess"))
        val only = PromptBuilder.build(TodoItem("T-3", "pick a license", tags = listOf("needs-decision")), emptyMap(), "")
        assertTrue(only.contains("id \"T-3\", tags \"\" and status \"fixed\""))
    }

    @Test
    fun `an agent that needs my decision is told to say what has to be decided on the item`() {
        val p = PromptBuilder.build(anchored, files, "")
        assertTrue(p, p.contains("do not guess and do not change the code. Call todo_update with id \"T-14\" and toDecide, "))
        assertTrue(p, p.contains("which says what has to be decided; that marks the item as waiting for the user. Leave the status and the details alone.\n"))
        assertFalse(p.contains("todo_comment"))
    }

    @Test
    fun `comments follow the details, oldest first`() {
        val item = anchored.copy(comments = listOf(
            Comment(Author.AGENT, "2026-10-06T09:00:00Z", "the cache is per unit"),
            Comment(Author.USER, "2026-10-06T09:05:00Z", "keep it that way"),
        ))
        val p = PromptBuilder.build(item, files, "")
        val expected = "Comments:\n- agent, 2026-10-06T09:00:00Z: the cache is per unit\n" +
            "- user, 2026-10-06T09:05:00Z: keep it that way\n"
        assertTrue(p, p.contains(expected))
        assertTrue(p.indexOf("Comments:") in p.indexOf("Details:")..p.indexOf("Location:"))
        assertFalse(PromptBuilder.build(anchored, files, "").contains("Comments:"))
    }

    @Test
    fun `several anchors are numbered, and one on a whole file has no code`() {
        val other = (1..9).joinToString("\n") { "other $it" }
        val item = anchored.copy(anchors = anchored.anchors + listOf(
            AnchorResolver.capture("src/other.txt", other, 4, 4),
            io.github.konove.notmytodo.model.Anchor.file("src/whole.txt"),
            io.github.konove.notmytodo.model.Anchor.file("src/gone.txt"),
        ))
        val p = PromptBuilder.build(item, files + ("src/other.txt" to other) + ("src/whole.txt" to "x"), "", PromptOptions(contextLines = 1))
        assertTrue(p, p.contains("The item is attached to 4 places."))
        assertTrue(p, p.contains("Location 1: src/unit.txt, lines 30-31."))
        assertTrue(p, p.contains("Location 2: src/other.txt, lines 4-4."))
        assertTrue(p, p.contains(">    4  other 4"))
        assertTrue(p, p.contains("Location 3: src/whole.txt, the whole file.\n"))
        assertTrue(p, p.contains("Location 4: src/gone.txt, the whole file. The file could not be found."))
        val one = PromptBuilder.build(anchored.copy(anchors = listOf(io.github.konove.notmytodo.model.Anchor.file("src/unit.txt"))), files, "")
        assertTrue(one, one.contains("\nLocation: src/unit.txt, the whole file.\n"))
        assertFalse(one.contains("```"))
    }

    @Test
    fun `the agent is asked to say what it did and in which commit`() {
        val p = PromptBuilder.build(TodoItem("T-5", "a"), emptyMap(), "")
        assertTrue(p, p.contains("status \"fixed\". Pass a resolution too") && p.contains("fixedIn"))
        val waiting = PromptBuilder.build(TodoItem("T-5", "a", tags = listOf("needs-decision")), emptyMap(), "")
        assertTrue(waiting, waiting.contains("status \"fixed\". Pass a resolution too"))
    }

    @Test
    fun `an item that waits says what is to be decided and has the answers recorded before any work`() {
        val waiting = anchored.copy(tags = listOf("perf", "needs-decision"), toDecide = "Per unit or per cell?")
        val p = PromptBuilder.build(waiting, files, "")
        assertTrue(p, p.contains("\nTo decide:\nPer unit or per cell?\n"))
        assertTrue(p.indexOf("Details:") < p.indexOf("To decide:") && p.indexOf("To decide:") < p.indexOf("Location:"))
        assertTrue(p, p.contains("before you change anything, call the todo_decided tool with id \"T-14\""))
        assertTrue(p.indexOf("wait for the answer") < p.indexOf("todo_decided") && p.indexOf("todo_decided") < p.indexOf("When the user has chosen"))
        assertFalse(PromptBuilder.build(anchored, files, "").contains("To decide:"))
        assertFalse(PromptBuilder.build(waiting, files, "", PromptOptions(short = true)).contains("To decide:"))
    }

    @Test
    fun `what was decided before is listed`() {
        val decided = anchored.copy(
            decisions = listOf(
                Decision("Per unit or per cell?", listOf("Per unit", "Per cell"), "Per cell", "2026-10-06T09:00:00Z"),
                Decision("Evict when?", emptyList(), "never", "2026-10-06T09:00:00Z"),
            ),
        )
        val p = PromptBuilder.build(decided, files, "")
        assertTrue(p, p.contains("\nAlready decided by the user:\n- Per unit or per cell? Answer: Per cell\n- Evict when? Answer: never\n"))
        assertFalse(PromptBuilder.build(anchored, files, "").contains("Already decided"))
    }

    @Test
    fun `the effort is said after the priority`() {
        val p = PromptBuilder.build(anchored.copy(effort = Effort.S), files, "")
        assertTrue(p, p.startsWith("Fix TODO item T-14 (priority p1, effort s, tags: perf, pathing)."))
    }
}
