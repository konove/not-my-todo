package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewIdsTest {
    private val item = TodoItem(id = "T-3", title = "t", blockedBy = listOf("T-1", "app/T-9", "lib/T-4"), duplicateOf = "T-2", parent = "T-1")

    @Test
    fun `an item of this project is shown as it is`() {
        assertSame(item, ViewIds.shown(item, "app", "app"))
    }

    @Test
    fun `an item of another project gets its qualified id, and so do its links`() {
        val shown = ViewIds.shown(item, "infra", "app")
        assertEquals("infra/T-3", shown.id)
        assertEquals("infra/T-2", shown.duplicateOf)
        assertEquals("infra/T-1", shown.parent)
        assertEquals(listOf("infra/T-1", "T-9", "lib/T-4"), shown.blockedBy)
    }

    @Test
    fun `a link of another project's item to this project's is shown bare`() {
        assertEquals(listOf("T-9"), ViewIds.shown(item.copy(blockedBy = listOf("app/T-9")), "infra", "app").blockedBy)
    }

    @Test
    fun `a ref shown here is qualified for the store, a bare one with this project's key`() {
        assertEquals("app/T-1", ViewIds.stored("T-1", "app"))
        assertEquals("app/T-1", ViewIds.stored("t-1", "app"))
        assertEquals("infra/T-2", ViewIds.stored("infra/T-2", "app"))
        assertEquals("not an id", ViewIds.stored("not an id", "app"))
    }

    @Test
    fun `a view id splits into its project and its id`() {
        assertEquals(ItemRef("app", "T-1"), ViewIds.split("T-1", "app"))
        assertEquals(ItemRef("infra", "T-2"), ViewIds.split("infra/T-2", "app"))
        assertNull(ViewIds.split("src/a.kt:12", "app"))
        assertTrue(ViewIds.isElsewhere("infra/T-2"))
        assertFalse(ViewIds.isElsewhere("T-2"))
        assertFalse(ViewIds.isElsewhere("src/a.kt:12"))
    }

    @Test
    fun `a qualified id is an item, and a code comment's id is not`() {
        assertFalse(CodeTodos.isCode(TodoItem(id = "infra/T-2", title = "t")))
        assertFalse(CodeTodos.isCode(TodoItem(id = "T-2", title = "t")))
        assertTrue(CodeTodos.isCode(TodoItem(id = "src/a.kt:12", title = "t")))
    }
}
