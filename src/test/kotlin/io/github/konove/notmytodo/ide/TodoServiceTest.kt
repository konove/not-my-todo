package io.github.konove.notmytodo.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.TestStore
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ProjectRecord
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreException
import org.junit.Assert.assertThrows
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TodoServiceTest : BasePlatformTestCase() {
    private lateinit var service: TodoService

    override fun setUp() {
        super.setUp()
        TestStore.reset(project)
        service = TodoService.getInstance(project)
    }

    fun `test the project is made in the store for the project's folder`() {
        assertTrue(service.available)
        val record = service.store.project!!
        assertEquals(Store.keyFor(service.root, emptySet()), record.key)
        assertEquals(listOf(service.root.toString()), record.checkouts)
    }

    fun `test accepting a fix is written at once`() {
        service.store.create(Draft("fixed by an agent"))
        service.store.update("T-1") { it.copy(status = Status.FIXED) }
        ItemActions.setStatus(project, "T-1", Status.DONE)
        assertEquals(Status.DONE, service.store.dir.read().items.single().status)
    }

    fun `test a broken project file of the key the project would get leaves it unopened, and Reload opens it once fixed`() {
        val stores = StoreService.getInstance().store
        val key = service.store.key
        val record = stores.dir(key).path.resolve("project.json")
        Files.writeString(record, "{ broken")
        service.reopen()
        assertFalse(service.available)
        assertTrue(service.store.error!!, service.store.error!!.contains("cannot be read"))
        assertThrows(StoreException::class.java) { service.store.create(Draft("lost")) }
        // No second project was made beside the broken one.
        assertEquals(listOf(key), Files.list(stores.root.resolve("projects")).use { s -> s.map { it.fileName.toString() }.toList() })

        stores.dir(key).writeRecord(ProjectRecord(key, checkouts = listOf(service.root.toString())))
        assertTrue(service.reload())
        assertTrue(service.available)
        assertEquals("T-1", service.store.create(Draft("kept")).id)
    }

    fun `test listeners stay when the project is opened again`() {
        val told = AtomicInteger()
        val listener: () -> Unit = { told.incrementAndGet() }
        service.addListener(listener)
        try {
            service.reopen()
            val before = told.get()
            service.store.create(Draft("after"))
            assertTrue(told.get() > before)
        } finally {
            service.removeListener(listener)
        }
    }

    fun `test an open item of another project blocks`() {
        val stores = StoreService.getInstance().store
        stores.dir("elsewhere").writeRecord(ProjectRecord("elsewhere"))
        stores.items("elsewhere").create(Draft("there first"))
        val item = service.store.create(Draft("here", blockedBy = listOf("elsewhere/T-1")))
        assertEquals(listOf("there first"), service.links().openBlockers(item).map { it.title })
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
