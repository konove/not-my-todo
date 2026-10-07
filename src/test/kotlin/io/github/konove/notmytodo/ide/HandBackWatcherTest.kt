package io.github.konove.notmytodo.ide

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.settings.TodoProjectSettings
import io.github.konove.notmytodo.store.Draft
import java.nio.file.Files

class HandBackWatcherTest : BasePlatformTestCase() {
    private val shown = ArrayList<Notification>()
    private val store get() = TodoService.getInstance(project).store

    override fun setUp() {
        super.setUp()
        Files.deleteIfExists(store.file)
        store.reload()
        project.service<HandBackWatcher>().start()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown.add(notification)
            }
        })
    }

    fun `test an agent's question on an item that waits for me shows a balloon`() {
        val item = store.create(Draft("pick a <license>", tags = listOf("needs-decision")))
        store.comment(item.id, Author.AGENT, "MIT or <Apache>?\nBoth fit.")
        assertEquals(listOf("${item.id} needs your decision"), shown.map { it.title })
        assertEquals("pick a &lt;license&gt;<br>MIT or &lt;Apache&gt;? Both fit.", shown.single().content)
        assertEquals(listOf("Show"), shown.single().actions.map { it.templateText })
    }

    fun `test what an agent says is to be decided shows a balloon`() {
        val item = store.create(Draft("pick a license"))
        store.update(item.id) { it.copy(tags = listOf("needs-decision"), toDecide = "MIT or Apache?") }
        assertEquals(listOf("${item.id} needs your decision"), shown.map { it.title })
        assertEquals("pick a license<br>MIT or Apache?", shown.single().content)
    }

    fun `test a note on an item in progress shows a balloon, and my own comment none`() {
        val item = store.create(Draft("cache findPath"))
        store.comment(item.id, Author.AGENT, "not sent off yet")
        store.update(item.id) { it.copy(status = Status.IN_PROGRESS) }
        store.comment(item.id, Author.USER, "go on")
        assertEquals(emptyList<String>(), shown.map { it.title })
        store.comment(item.id, Author.AGENT, "half way")
        assertEquals(listOf("The agent left a comment on ${item.id}"), shown.map { it.title })
    }

    fun `test several hand-backs in one write show one balloon`() {
        val a = store.create(Draft("pick a license", tags = listOf("needs-decision")))
        val b = store.create(Draft("cache findPath"))
        store.update(b.id) { it.copy(status = Status.IN_PROGRESS) }
        store.batch { batch ->
            batch.comment(b.id, Author.AGENT, "half way")
            batch.comment(a.id, Author.AGENT, "MIT or Apache?")
        }
        assertEquals(listOf("The agent left comments on 2 items; 1 needs your decision"), shown.map { it.title })
        assertEquals("${a.id} pick a license<br>${b.id} cache findPath", shown.single().content)
        assertEquals(listOf("Show"), shown.single().actions.map { it.templateText })
    }

    fun `test many hand-backs are counted, and the first few named`() {
        val items = (1..7).map { store.create(Draft("item $it", tags = listOf("needs-decision"))) }
        store.batch { batch -> items.forEach { batch.comment(it.id, Author.AGENT, "which?") } }
        assertEquals(listOf("7 items need your decision"), shown.map { it.title })
        assertEquals((1..5).joinToString("<br>") { "${items[it - 1].id} item $it" } + "<br>and 2 more", shown.single().content)
    }

    fun `test switching to another items file reports nothing`() {
        val item = store.create(Draft("a", tags = listOf("needs-decision")))
        val service = TodoService.getInstance(project)
        val other = service.base.resolve("notes/todo.json")
        Files.createDirectories(other.parent)
        Files.copy(store.file, other)
        try {
            service.useItemsFile("notes/todo.json", move = false)
            store.comment(item.id, Author.AGENT, "which?")
            assertEquals(1, shown.size)
            service.useItemsFile(TodoProjectSettings.DEFAULT_ITEMS_FILE, move = false)
            // The same id in the other file has a comment this one lacks, but nobody has just written it.
            service.useItemsFile("notes/todo.json", move = false)
            assertEquals(1, shown.size)
        } finally {
            service.useItemsFile(TodoProjectSettings.DEFAULT_ITEMS_FILE, move = false)
            Files.deleteIfExists(other)
        }
    }
}
