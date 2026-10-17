package io.github.konove.notmytodo.ide

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.TestStore
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ProjectRecord
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreException
import org.junit.Assert.assertThrows
import java.nio.file.Files
import java.nio.file.Path
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

    fun `test opening writes no project, the first item makes it`() {
        TestStore.reset(project, made = false)
        assertTrue(service.pending)
        assertTrue(StoreService.getInstance().store.projects().isEmpty())
        val made = StoreView.getInstance(project).create(service.store.key, Draft("first"))
        assertFalse(service.pending)
        assertEquals("T-1", made.id)
        assertEquals(listOf("first"), service.store.items.map { it.title })
        assertEquals(1, StoreService.getInstance().store.projects().size)
    }

    fun `test a project made by another writer for this folder is opened`() {
        TestStore.reset(project, made = false)
        val stores = StoreService.getInstance().store
        val key = stores.ensure(stores.resolve(service.base)).record.key
        stores.items(key).create(Draft("from the server"))
        service.reload()
        assertFalse(service.pending)
        assertEquals(listOf("from the server"), service.store.items.map { it.title })
    }

    fun `test a project another folder takes under the pending key moves this folder to a free one`() {
        TestStore.reset(project, made = false)
        val wanted = service.store.key
        val stores = StoreService.getInstance().store
        stores.dir(wanted).writeRecord(ProjectRecord(wanted, checkouts = listOf("/somewhere/else")))
        stores.items(wanted).create(Draft("not this folder's"))
        service.reload()
        assertTrue(service.pending)
        assertEquals("$wanted-2", service.store.key)
        assertTrue(service.store.items.isEmpty())
    }

    fun `test renaming a pending project makes it first, then renames it`() {
        TestStore.reset(project, made = false)
        service.rename("renamed")
        assertFalse(service.pending)
        assertEquals("renamed", service.store.key)
        assertNotNull(StoreService.getInstance().store.project("renamed"))
    }

    fun `test while pending a change anywhere in the store is looked at`() {
        TestStore.reset(project, made = false)
        val projects = StoreService.getInstance().store.root.resolve("projects").toString().replace('\\', '/')
        assertTrue(service.changed(listOf("$projects/other/project.json")))
    }

    fun `test tests that name no store of their own stop before they reach the user's`() {
        val own = System.getProperty(StoreService.ROOT_PROPERTY)
        System.clearProperty(StoreService.ROOT_PROPERTY)
        try {
            StoreService()
            fail("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains(StoreService.ROOT_PROPERTY))
        } finally {
            System.setProperty(StoreService.ROOT_PROPERTY, own)
        }
    }

    fun `test after a rename the listeners still hear changes, and the items are written in the renamed folder`() {
        val told = AtomicInteger()
        val listener: () -> Unit = { told.incrementAndGet() }
        service.addListener(listener)
        try {
            service.store.create(Draft("before"))
            service.rename("renamed")
            assertEquals("renamed", service.store.key)
            assertEquals(listOf("before"), service.store.items.map { it.title })
            val heard = told.get()
            val item = service.store.create(Draft("after"))
            assertTrue(told.get() > heard)
            assertTrue(Files.exists(StoreService.getInstance().store.dir("renamed").itemFile(item.id)))
        } finally {
            service.removeListener(listener)
        }
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
        StoreView.getInstance(project).load()
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

    private fun waitFor(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (done()) return true
            Thread.sleep(20)
        }
        return done()
    }

    fun `test a change another process writes is read when the watcher reports it`() {
        val told = AtomicInteger()
        val listener: () -> Unit = { told.incrementAndGet() }
        service.addListener(listener)
        try {
            // Another Store over the same folder reads and writes as an agent's server would, with its own cache.
            val other = Store(StoreService.getInstance().store.root)
            other.items(service.store.key).create(Draft("from an agent"))
            assertTrue(service.changed(listOf(service.store.dir.itemFile("T-1").toString().replace('\\', '/'))))
            assertTrue(waitFor(5000) { service.store.find("T-1") != null })
            assertTrue(told.get() > 0)
        } finally {
            service.removeListener(listener)
        }
    }

    fun `test a change to another project's files is not read`() {
        val stores = StoreService.getInstance().store
        stores.dir("elsewhere").writeRecord(ProjectRecord("elsewhere"))
        assertFalse(service.changed(listOf(stores.dir("elsewhere").itemFile("T-1").toString().replace('\\', '/'))))
        assertFalse(service.changed(listOf(service.base.resolve("src/a.txt").toString().replace('\\', '/'))))
    }

    fun `test an item file that cannot be read is said once`() {
        val shown = ArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown.add(notification)
            }
        })
        service.store.create(Draft("fine"))
        Files.writeString(service.store.dir.itemFile("T-7"), "{ hand-edited")
        service.reload()
        service.reload()
        assertEquals(listOf("An item file in the store cannot be read"), shown.map { it.title })
        assertTrue(shown.single().content, shown.single().content.contains("T-7.json"))
        assertEquals(listOf("fine"), service.store.items.map { it.title })
    }

    fun `test an item file that cannot be read is said when the project is opened again`() {
        val shown = ArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown.add(notification)
            }
        })
        val dir = StoreService.getInstance().store.dir(service.store.key)
        Files.writeString(dir.path.resolve("project.json"), "{ broken")
        service.reopen()
        assertFalse(service.available)
        Files.createDirectories(dir.itemFile("T-3").parent)
        Files.writeString(dir.itemFile("T-3"), "{ hand-edited")
        dir.writeRecord(ProjectRecord(dir.path.fileName.toString(), checkouts = listOf(service.root.toString())))
        assertTrue(service.reload())
        assertTrue(service.available)
        assertEquals(listOf("An item file in the store cannot be read"), shown.map { it.title })
        assertTrue(shown.single().content, shown.single().content.contains("T-3.json"))
    }

    /** True when the IDE has seen [path], without looking on disk: only then does the watcher report its changes. */
    private fun seen(path: Path) = LocalFileSystem.getInstance().findFileByPathIfCached(path.toString().replace('\\', '/')) != null

    fun `test every file of a project folder is seen once the store is watched`() {
        val made = StoreService.getInstance().store.root.resolve("projects/there-before")
        val item = made.resolve("items/T-1.json")
        Files.createDirectories(item.parent)
        Files.writeString(item, "{}")
        StoreService.getInstance().seeAll(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(made)!!)
        assertTrue(waitFor(5000) { seen(item) })
    }

    fun `test every file of a project folder another process made is seen`() {
        val projects = StoreService.getInstance().store.root.resolve("projects")
        val known = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projects)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, known)
        val item = projects.resolve("made-elsewhere/items/T-1.json")
        Files.createDirectories(item.parent)
        Files.writeString(item, "{}")
        // The refresh that tells the IDE of the new folder, as the watcher would.
        VfsUtil.markDirtyAndRefresh(false, false, true, known)
        assertTrue(waitFor(5000) { seen(item) })
    }
}
