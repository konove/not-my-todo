package io.github.konove.notmytodo.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.channel.ChannelRegistration
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Priority
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TodoConfigurableTest : BasePlatformTestCase() {
    private lateinit var page: TodoConfigurable

    override fun setUp() {
        super.setUp()
        // No items file on disk, so changing its path never has to ask about moving it.
        val store = TodoService.getInstance(project).store
        java.nio.file.Files.deleteIfExists(store.file)
        store.reload()
        // No real `claude` is run from tests: this registration finds no Claude Code.
        page = TodoConfigurable(project, ChannelRegistration(Files.createTempDirectory("nmt-page")) { null })
        page.createComponent()
        page.reset()
    }

    override fun tearDown() {
        try {
            page.disposeUIResources()
            TodoSettings.getInstance().update(TodoSettings.Values())
            TodoService.getInstance(project).useItemsFile(TodoProjectSettings.DEFAULT_ITEMS_FILE, move = false)
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
            .first { it.text == defaultFile }
        field.text = "../outside.json"
        try {
            page.apply()
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(page.isModified)
        }
    }

    private val defaultFile = TodoProjectSettings.DEFAULT_ITEMS_FILE

    fun `test a fresh page is not modified and applies cleanly`() {
        assertFalse(page.isModified)
        page.apply()
        assertEquals(TodoSettings.Values(), TodoSettings.getInstance().values)
    }

    fun `test store saves the values`() {
        page.store(TodoSettings.Values(fixAllLimit = 12, defaultPriority = Priority.P1, extraInstructions = "Be brief."), defaultFile)
        val saved = TodoSettings.getInstance().values
        assertEquals(12, saved.fixAllLimit)
        assertEquals(Priority.P1, saved.defaultPriority)
        assertEquals("Be brief.", saved.extraInstructions)
    }

    fun `test an empty command is refused and nothing is saved`() {
        try {
            page.store(TodoSettings.Values(claudeCommand = "  ", fixAllLimit = 12), defaultFile)
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage.contains("command"))
        }
        assertEquals(TodoSettings.Values(), TodoSettings.getInstance().values)
    }

    fun `test a bad items file is refused with the reason and nothing is saved`() {
        try {
            page.store(TodoSettings.Values(fixAllLimit = 12), "../outside.json")
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage.contains("inside the project"))
        }
        assertEquals(defaultFile, TodoProjectSettings.getInstance(project).itemsFile)
        assertEquals(50, TodoSettings.getInstance().values.fixAllLimit)
    }

    fun `test a new items file with nothing to move is taken into use`() {
        page.store(TodoSettings.Values(), "notes/todo.json")
        assertEquals("notes/todo.json", TodoProjectSettings.getInstance(project).itemsFile)
        assertTrue(TodoService.getInstance(project).store.file.endsWith("notes/todo.json"))
    }

    fun `test the running session cannot be the default target while the channel is off`() {
        try {
            page.store(TodoSettings.Values(defaultTarget = FixTarget.SESSION, channelEnabled = false), defaultFile)
            fail("expected a ConfigurationException")
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage.contains("running session"))
        }
        assertEquals(FixTarget.TERMINAL, TodoSettings.getInstance().values.defaultTarget)
    }

    fun `test the running session can be the default target with the channel on`() {
        page.store(TodoSettings.Values(defaultTarget = FixTarget.SESSION, channelEnabled = true), defaultFile)
        assertEquals(FixTarget.SESSION, TodoSettings.getInstance().values.defaultTarget)
        assertTrue(TodoSettings.getInstance().values.channelEnabled)
    }
}
