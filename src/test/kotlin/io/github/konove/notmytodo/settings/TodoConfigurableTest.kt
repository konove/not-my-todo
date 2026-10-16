package io.github.konove.notmytodo.settings

import io.github.konove.notmytodo.TestStore
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.channel.ChannelRegistration
import io.github.konove.notmytodo.ide.StoreService
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ProjectRecord
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TodoConfigurableTest : BasePlatformTestCase() {
    private lateinit var page: TodoConfigurable

    override fun setUp() {
        super.setUp()
        TestStore.reset(project)
        // No real `claude` is run from tests: this registration finds no Claude Code.
        page = TodoConfigurable(project, ChannelRegistration(Files.createTempDirectory("nmt-page")) { null })
        page.createComponent()
        page.reset()
    }

    override fun tearDown() {
        try {
            page.disposeUIResources()
            TodoSettings.getInstance().update(TodoSettings.Values())
        } finally {
            super.tearDown()
        }
    }

    private fun countingPage(calls: AtomicInteger): TodoConfigurable {
        page.disposeUIResources()
        return TodoConfigurable(project, ChannelRegistration(Files.createTempDirectory("nmt-page")) { calls.incrementAndGet(); null })
            .also { page = it }
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

    fun `test a page opened with the channel off never asks Claude Code`() {
        val calls = AtomicInteger()
        countingPage(calls).apply { createComponent(); reset() }
        assertFalse(waitFor(500) { calls.get() > 0 })
    }

    fun `test apply refreshes the channel block`() {
        TodoSettings.getInstance().update(TodoSettings.Values(channelEnabled = true))
        val calls = AtomicInteger()
        val p = countingPage(calls)
        p.createComponent()
        p.reset()
        assertTrue(waitFor(5000) { calls.get() > 0 })
        val before = calls.get()
        p.apply()
        assertTrue(waitFor(5000) { calls.get() > before })
    }

    fun `test a draft that differs from the stored settings counts as modified until reset`() {
        page.draft = page.draft.copy(fixAllLimit = 12)
        assertTrue(page.isModified)
        page.reset()
        assertFalse(page.isModified)
    }

    fun `test the page is still modified after a refused apply`() {
        val field = com.intellij.util.ui.UIUtil.findComponentsOfType(page.createComponent(), javax.swing.JTextField::class.java)
            .first { it.text == "claude" }
        field.text = "  "
        try {
            page.apply()
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(page.isModified)
        }
    }

    fun `test a fresh page is not modified and applies cleanly`() {
        assertFalse(page.isModified)
        page.apply()
        assertEquals(TodoSettings.Values(), TodoSettings.getInstance().values)
    }

    fun `test store saves the values`() {
        page.store(TodoSettings.Values(fixAllLimit = 12, defaultPriority = Priority.P1, extraInstructions = "Be brief."), page.key)
        val saved = TodoSettings.getInstance().values
        assertEquals(12, saved.fixAllLimit)
        assertEquals(Priority.P1, saved.defaultPriority)
        assertEquals("Be brief.", saved.extraInstructions)
    }

    fun `test an empty command is refused and nothing is saved`() {
        try {
            page.store(TodoSettings.Values(claudeCommand = "  ", fixAllLimit = 12), page.key)
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage.contains("command"))
        }
        assertEquals(TodoSettings.Values(), TodoSettings.getInstance().values)
    }

    fun `test the running session cannot be the default target while the channel is off`() {
        try {
            page.store(TodoSettings.Values(defaultTarget = FixTarget.SESSION, channelEnabled = false), page.key)
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage.contains("running session"))
        }
        assertEquals(FixTarget.TERMINAL, TodoSettings.getInstance().values.defaultTarget)
    }

    fun `test the running session can be the default target with the channel on`() {
        page.store(TodoSettings.Values(defaultTarget = FixTarget.SESSION, channelEnabled = true), page.key)
        assertEquals(FixTarget.SESSION, TodoSettings.getInstance().values.defaultTarget)
        assertTrue(TodoSettings.getInstance().values.channelEnabled)
    }

    fun `test a new key renames the store project and its items go with it`() {
        val service = TodoService.getInstance(project)
        service.store.create(Draft("travels"))
        page.store(TodoSettings.Values(), "Renamed")
        assertEquals("renamed", service.store.key)
        assertEquals(listOf("travels"), service.store.items.map { it.title })
        assertTrue(Files.isDirectory(StoreService.getInstance().store.dir("renamed").path))
    }

    fun `test the page is not modified once the new key is applied`() {
        val field = com.intellij.util.ui.UIUtil.findComponentsOfType(page.createComponent(), javax.swing.JTextField::class.java)
            .first { it.text == TodoService.getInstance(project).store.key }
        field.text = "Renamed"
        assertTrue(page.isModified)
        page.apply()
        assertEquals("renamed", TodoService.getInstance(project).store.key)
        assertEquals("renamed", field.text)
        assertFalse(page.isModified)
        page.store(TodoSettings.Values(), "Renamed")
        assertEquals("renamed", TodoService.getInstance(project).store.key)
        assertFalse(page.isModified)
    }

    fun `test a page reset while the project is unavailable never renames it, even once it is available again`() {
        val service = TodoService.getInstance(project)
        val stores = StoreService.getInstance().store
        val key = service.store.key
        Files.writeString(stores.dir(key).path.resolve("project.json"), "{ broken")
        service.reopen()
        assertFalse(service.available)
        page.reset()
        stores.dir(key).writeRecord(ProjectRecord(key, checkouts = listOf(service.root.toString())))
        assertTrue(service.reload())
        assertFalse(page.isModified)
        page.store(TodoSettings.Values(), page.key)
        assertEquals(key, service.store.key)
    }

    fun `test a key that cannot be one is refused with the reason and nothing is saved`() {
        val before = TodoService.getInstance(project).store.key
        try {
            page.store(TodoSettings.Values(fixAllLimit = 12), "not a key!")
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage, e.localizedMessage.contains("cannot be a project key"))
        }
        assertEquals(before, TodoService.getInstance(project).store.key)
        assertEquals(50, TodoSettings.getInstance().values.fixAllLimit)
    }

    fun `test the page shows the store's folder and the project's key`() {
        val fields = com.intellij.util.ui.UIUtil.findComponentsOfType(page.createComponent(), javax.swing.JTextField::class.java).map { it.text }
        assertTrue(fields.toString(), StoreService.getInstance().store.root.toString() in fields)
        assertTrue(fields.toString(), TodoService.getInstance(project).store.key in fields)
    }
}
