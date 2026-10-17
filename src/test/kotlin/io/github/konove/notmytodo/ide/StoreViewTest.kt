package io.github.konove.notmytodo.ide

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
import java.util.concurrent.atomic.AtomicInteger

class StoreViewTest : BasePlatformTestCase() {
    private lateinit var view: StoreView
    private val stores get() = StoreService.getInstance().store

    override fun setUp() {
        super.setUp()
        TestStore.reset(project)
        view = StoreView.getInstance(project)
    }

    /** A project of the store other than this one, with a checkout when [checkout] is given. */
    private fun other(key: String, checkout: java.nio.file.Path? = null): io.github.konove.notmytodo.store.ItemStore {
        stores.dir(key).writeRecord(ProjectRecord(key, checkouts = listOfNotNull(checkout?.toString())))
        return stores.items(key)
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

    fun `test the same id in two projects names two items`() {
        val home = TodoService.getInstance(project).store
        home.create(Draft("mine"))
        other("infra").create(Draft("theirs"))
        view.load()
        assertEquals(listOf("mine"), view.items(ProjectScope.THIS_PROJECT).map { it.title })
        assertEquals(setOf("T-1" to "mine", "infra/T-1" to "theirs"), view.items(ProjectScope.ALL).map { it.id to it.title }.toSet())
        assertEquals("theirs", view.find("infra/T-1")!!.title)
        assertEquals("mine", view.find("T-1")!!.title)

        assertEquals("infra/T-1", view.update("infra/T-1") { it.copy(status = Status.DONE) }.id)
        assertEquals(Status.DONE, stores.items("infra").find("T-1")!!.status)
        assertEquals(Status.OPEN, home.find("T-1")!!.status)
        assertEquals(Status.DONE, view.find("infra/T-1")!!.status)
    }

    fun `test Inbox lists the inbox, and a note made there makes it`() {
        assertTrue(view.items(ProjectScope.INBOX).isEmpty())
        val made = view.create(Store.INBOX, Draft("a note"))
        assertEquals("inbox/T-1", made.id)
        assertNotNull(stores.project(Store.INBOX))
        assertEquals(listOf("inbox/T-1"), view.items(ProjectScope.INBOX).map { it.id })
        assertEquals(listOf(view.home, Store.INBOX), view.keys())
    }

    fun `test a link between projects is followed both ways`() {
        val home = TodoService.getInstance(project).store
        val mine = home.create(Draft("mine"))
        val theirs = other("infra").create(Draft("theirs", blockedBy = listOf("${view.home}/${mine.id}")))
        view.load()
        val shown = view.find("infra/${theirs.id}")!!
        assertEquals(listOf(mine.id), shown.blockedBy)
        assertTrue(view.links().isBlocked(shown))
        home.update(mine.id) { it.copy(status = Status.DONE) }
        assertFalse(view.links().isBlocked(view.find("infra/${theirs.id}")!!))

        val waits = home.create(Draft("waits", blockedBy = listOf("infra/${theirs.id}")))
        assertTrue(TodoService.getInstance(project).links().isBlocked(waits))
    }

    fun `test an id of a project that is not there finds nothing and changes nothing`() {
        assertNull(view.find("gone/T-1"))
        assertNull(view.target("gone/T-1"))
        assertThrows(StoreException::class.java) { view.update("gone/T-1") { it } }
        assertThrows(StoreException::class.java) { view.create("gone", Draft("x")) }
        assertFalse(Files.exists(stores.root.resolve("projects/gone")))
    }

    fun `test a checkout is a folder that is there, and a file of it is read from it`() {
        val folder = Files.createTempDirectory("nmt-checkout")
        Files.createDirectories(folder.resolve("src"))
        Files.writeString(folder.resolve("src/a.txt"), "one\n")
        other("infra", folder)
        other("gone", folder.resolve("no-such-folder"))
        view.load()
        assertEquals(folder, view.checkout("infra"))
        assertNull(view.checkout("gone"))
        assertNull(view.checkout(Store.INBOX))
        assertEquals(TodoService.getInstance(project).root, view.checkout(view.home))
        assertEquals("one\n", view.readText("infra", "src/a.txt"))
        assertNull(view.readText("infra", "src/none.txt"))
        assertNull(view.readText("gone", "src/a.txt"))
    }

    fun `test a change to another project is read when the watcher reports it`() {
        val told = AtomicInteger()
        val listener: () -> Unit = { told.incrementAndGet() }
        val infra = other("infra")
        view.load()
        view.addListener(listener)
        try {
            infra.create(Draft("from an agent"))
            val service = TodoService.getInstance(project)
            assertFalse(service.changed(listOf(stores.dir("infra").itemFile("T-1").toString().replace('\\', '/'))))
            assertTrue(waitFor(5000) { view.find("infra/T-1") != null })
            assertTrue(told.get() > 0)
        } finally {
            view.removeListener(listener)
        }
    }

    fun `test reads asked for while one is waiting are one read`() {
        val told = AtomicInteger()
        val listener: () -> Unit = { told.incrementAndGet() }
        view.addListener(listener)
        try {
            repeat(20) { view.loadLater() }
            assertTrue(waitFor(5000) { told.get() > 0 })
            Thread.sleep(200)
            // A read may start while the asking goes on, and a later ask then waits for one more: never one each.
            assertTrue("read ${told.get()} times", told.get() < 20)
        } finally {
            view.removeListener(listener)
        }
    }
}
