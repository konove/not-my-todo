package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.TestStore
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.settings.TodoProjectSettings
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.StoreException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class TodoServiceTest : BasePlatformTestCase() {
    private lateinit var service: TodoService

    override fun setUp() {
        super.setUp()
        service = TodoService.getInstance(project)
        TestStore.reset(project)
    }

    override fun tearDown() {
        try {
            service.useItemsFile(TodoProjectSettings.DEFAULT_ITEMS_FILE, move = false)
        } finally {
            super.tearDown()
        }
    }

    fun `test accepting a fix is kept out of the items file until it is next written`() {
        val store = service.store
        store.create(Draft("fixed by an agent"))
        store.create(Draft("my own"))
        store.update("T-1") { it.copy(status = Status.FIXED) }
        val committed = Files.readString(store.file)

        ItemActions.setStatus(project, "T-1", Status.DONE)
        assertEquals(Status.DONE, store.find("T-1")!!.status)
        assertEquals(committed, Files.readString(store.file))
        assertEquals(setOf("T-1"), project.service<HeldStatuses>().load().keys)

        // What I close myself goes into the file, to be committed with my work, and takes the other along.
        ItemActions.setStatus(project, "T-2", Status.DONE)
        val text = Files.readString(store.file)
        assertEquals(2, Regex("\"status\": \"done\"").findAll(text).count())
        assertTrue(project.service<HeldStatuses>().load().isEmpty())
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

    fun `test listeners added to the service hear every change`() {
        val told = AtomicInteger()
        val listener: () -> Unit = { told.incrementAndGet() }
        service.addListener(listener)
        try {
            service.store.create(Draft("heard"))
            assertEquals(1, told.get())
        } finally {
            service.removeListener(listener)
        }
        service.store.create(Draft("not heard"))
        assertEquals(1, told.get())
    }
}
