package io.github.konove.notmytodo.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.settings.TodoProjectSettings
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.StoreException
import java.nio.file.Files
import java.nio.file.Path

class TodoServiceTest : BasePlatformTestCase() {
    private lateinit var service: TodoService

    override fun setUp() {
        super.setUp()
        service = TodoService.getInstance(project)
        Files.deleteIfExists(service.store.file)
        service.store.reload()
    }

    override fun tearDown() {
        try {
            service.useItemsFile(TodoProjectSettings.DEFAULT_ITEMS_FILE, move = false)
        } finally {
            super.tearDown()
        }
    }

    fun `test the items file can be moved and the setting follows`() {
        service.store.create(Draft("travels"))
        service.useItemsFile("notes/todo.json", move = true)
        try {
            assertTrue(service.store.file.endsWith("notes/todo.json"))
            assertEquals(listOf("travels"), service.store.items.map { it.title })
            assertEquals("notes/todo.json", TodoProjectSettings.getInstance(project).itemsFile)
        } finally {
            Files.deleteIfExists(service.store.file)
        }
    }

    fun `test a path outside the project is refused and the old file stays in use`() {
        val before = service.store.file
        try {
            service.useItemsFile("../elsewhere.json", move = false)
            fail("expected a StoreException")
        } catch (e: StoreException) {
            assertTrue(e.message!!.contains("inside the project"))
        }
        assertEquals(before, service.store.file)
        assertEquals(TodoProjectSettings.DEFAULT_ITEMS_FILE, TodoProjectSettings.getInstance(project).itemsFile)
    }

    fun `test a path under a regular file is refused and the old file stays in use`() {
        val before = service.store.file
        val blocker = Files.writeString(Path.of(project.basePath!!).resolve("nmt-blocker.txt"), "x")
        try {
            service.useItemsFile("nmt-blocker.txt/items.json", move = false)
            fail("expected a StoreException")
        } catch (e: StoreException) {
            assertTrue(e.message!!.contains("cannot be written"))
        } finally {
            Files.deleteIfExists(blocker)
        }
        assertEquals(before, service.store.file)
        assertEquals(TodoProjectSettings.DEFAULT_ITEMS_FILE, TodoProjectSettings.getInstance(project).itemsFile)
    }

    fun `test store file lives under dot todos`() {
        assertTrue(service.store.file.endsWith(".todos/items.json"))
    }

    fun `test paths map both ways`() {
        val file = myFixture.addFileToProject("dir/a.txt", "one\ntwo\n").virtualFile
        val relative = service.relativePath(file)
        assertNotNull(relative)
        assertTrue(relative!!.endsWith("dir/a.txt"))
        assertFalse(relative.startsWith("/"))
        assertEquals(file, service.findFile(relative))
        assertEquals(relative, service.relativePath(file.path))
        assertNull(service.findFile("no/such/file.txt"))
    }

    fun `test readText prefers the open document`() {
        val psi = myFixture.configureByText("b.txt", "disk\n")
        val relative = service.relativePath(psi.virtualFile)!!
        assertEquals("disk\n", service.readText(relative))
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "typed ") }
        assertEquals("typed disk\n", service.readText(relative))
        assertNull(service.readText("no/such/file.txt"))
    }
}
