package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class PromptBuilderTest {
    private val fileText = (1..60).joinToString("\n") { "line $it" }
    private val anchored = TodoItem(
        id = "T-14", title = "cache findPath", details = "Share one path per cell.", priority = Priority.P1,
        tags = listOf("perf", "pathing"), anchor = AnchorResolver.capture("src/unit.txt", fileText, 30, 31),
    )

    @Test
    fun `anchored item includes the code with twenty lines either side`() {
        val p = PromptBuilder.build(anchored, fileText, "tests pass")
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
        val p = PromptBuilder.build(note, null, "  ")
        assertTrue(p.startsWith("Fix TODO item T-2 (priority p2)."))
        assertFalse(p.contains("Location:"))
        assertFalse(p.contains("Details:"))
        assertFalse(p.contains("Done when:"))
    }

    @Test
    fun `lost anchor shows the last known code`() {
        val lost = anchored.copy(anchor = anchored.anchor!!.copy(lost = true))
        val p = PromptBuilder.build(lost, fileText, "")
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
        val prompt = PromptBuilder.build(TodoItem("a.c:2", "TODO: tidy", anchor = anchor), "one\n// TODO: tidy\nthree\n", "")
        org.junit.Assert.assertTrue(prompt.startsWith("Resolve this TODO comment in the code.\n\nComment: TODO: tidy\n"))
        org.junit.Assert.assertTrue(prompt.contains("Location: a.c, lines 2-2."))
        org.junit.Assert.assertTrue(prompt.contains("remove the TODO comment"))
        org.junit.Assert.assertFalse(prompt.contains("todo_update"))
    }

    @Test
    fun `context lines follow the options, down to none`() {
        val two = PromptBuilder.build(anchored, fileText, "", PromptOptions(contextLines = 2))
        assertTrue(two.contains("    28  line 28"))
        assertTrue(two.contains("    33  line 33"))
        assertFalse(two.contains("line 27\n"))
        assertFalse(two.contains("line 34"))
        val none = PromptBuilder.build(anchored, fileText, "", PromptOptions(contextLines = 0))
        assertTrue(none.contains(">   30  line 30"))
        assertFalse(none.contains("line 29"))
        assertFalse(none.contains("line 32"))
    }

    @Test
    fun `short prompt points at todo_get and embeds no code`() {
        val p = PromptBuilder.build(anchored, fileText, "tests pass", PromptOptions(short = true))
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
        val p = PromptBuilder.build(TodoItem("a.c:2", "TODO: tidy", anchor = anchor), "one\n// TODO: tidy\nthree\n", "", PromptOptions(short = true))
        assertTrue(p.contains("Location: a.c, lines 2-2."))
        assertFalse(p.contains("todo_get"))
    }

    @Test
    fun `extra instructions come after done when, and blank ones add nothing`() {
        val p = PromptBuilder.build(anchored, fileText, "tests pass", PromptOptions(extra = "  Run the linter.\n"))
        assertTrue(p.contains("Done when: tests pass\n\nRun the linter.\n"))
        assertTrue(p.indexOf("Run the linter.") < p.indexOf("When you have finished"))
        assertEquals(
            PromptBuilder.build(anchored, fileText, "tests pass"),
            PromptBuilder.build(anchored, fileText, "tests pass", PromptOptions(extra = " \n\t ")),
        )
    }

    @Test
    fun `a prompt for several items carries the extra instructions once`() {
        val note = TodoItem(id = "T-2", title = "decide save versioning")
        val p = PromptBuilder.buildAll(listOf(anchored to fileText, note to null), PromptOptions(extra = "Run the linter."))
        assertTrue(p.startsWith("Fix the following 2 TODO items, one at a time.\n\nRun the linter.\n\n"))
        assertEquals(1, Regex("Run the linter\\.").findAll(p).count())
        assertTrue(p.contains("Fix TODO item T-14"))
        assertTrue(p.contains("Fix TODO item T-2"))
    }

    @Test
    fun `the fallback names the items file in use`() {
        val p = PromptBuilder.build(anchored, fileText, "", PromptOptions(itemsFile = "notes/todo.json"))
        assertTrue(p.contains("for this item in notes/todo.json."))
        assertFalse(p.contains(".todos/items.json"))
    }

    @Test
    fun `an item that needs my decision asks for options and a choice before any work`() {
        val waiting = anchored.copy(tags = listOf("perf", "needs-decision"))
        val p = PromptBuilder.build(waiting, fileText, "tests pass", PromptOptions(itemsFile = "notes/todo.json"))
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
        val only = PromptBuilder.build(TodoItem("T-3", "pick a license", tags = listOf("needs-decision")), null, "")
        assertTrue(only.contains("id \"T-3\", tags \"\" and status \"fixed\""))
    }

    @Test
    fun `comments follow the details, oldest first`() {
        val item = anchored.copy(comments = listOf(
            Comment(Author.AGENT, "2026-10-06T09:00:00Z", "the cache is per unit"),
            Comment(Author.USER, "2026-10-06T09:05:00Z", "keep it that way"),
        ))
        val p = PromptBuilder.build(item, fileText, "")
        val expected = "Comments:\n- agent, 2026-10-06T09:00:00Z: the cache is per unit\n" +
            "- user, 2026-10-06T09:05:00Z: keep it that way\n"
        assertTrue(p, p.contains(expected))
        assertTrue(p.indexOf("Comments:") in p.indexOf("Details:")..p.indexOf("Location:"))
        assertFalse(PromptBuilder.build(anchored, fileText, "").contains("Comments:"))
    }
}
