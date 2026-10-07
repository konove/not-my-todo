package io.github.konove.notmytodo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.model.Priority

class TodoSettingsTest : BasePlatformTestCase() {
    private val settings get() = TodoSettings.getInstance()

    override fun tearDown() {
        try {
            settings.update(TodoSettings.Values())
            TodoProjectSettings.getInstance(project).itemsFile = TodoProjectSettings.DEFAULT_ITEMS_FILE
        } finally {
            super.tearDown()
        }
    }

    fun `test defaults match the behaviour before there were settings`() {
        val v = TodoSettings.Values()
        assertFalse(v.channelEnabled)
        assertEquals(FixTarget.TERMINAL, v.defaultTarget)
        assertEquals("claude", v.claudeCommand)
        assertFalse(v.skipFixDialog)
        assertEquals(50, v.fixAllLimit)
        assertEquals(20, v.contextLines)
        assertFalse(v.shortPrompt)
        assertEquals("", v.extraInstructions)
        assertEquals(6, v.donePhraseList().size)
        assertEquals(listOf("a", "b"), v.copy(donePhrases = " a \n\nb\na").donePhraseList())
        assertEquals(Priority.P2, v.defaultPriority)
        assertTrue(v.editorMarks)
        assertTrue(v.codeComments)
        assertEquals(".todos/items.json", TodoProjectSettings.getInstance(project).itemsFile)
    }

    fun `test update stores a copy and tells listeners`() {
        var calls = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(TodoSettingsListener.TOPIC, TodoSettingsListener { calls++ })
        val next = TodoSettings.Values(fixAllLimit = 7, defaultTarget = FixTarget.CLIPBOARD)
        settings.update(next)
        next.fixAllLimit = 99
        assertEquals(1, calls)
        assertEquals(7, settings.values.fixAllLimit)
        assertEquals(FixTarget.CLIPBOARD, settings.values.defaultTarget)
        settings.values.fixAllLimit = 3
        assertEquals(7, settings.values.fixAllLimit)
    }

    fun `test loaded state survives a round trip and out of range numbers are pulled in`() {
        settings.loadState(TodoSettings.Values(fixAllLimit = 0, contextLines = 9000, claudeCommand = "  ", extraInstructions = "be brief"))
        assertEquals(1, settings.values.fixAllLimit)
        assertEquals(200, settings.values.contextLines)
        assertEquals("claude", settings.values.claudeCommand)
        assertEquals("be brief", settings.state.extraInstructions)
    }

    fun `test the default target is stored by its name, not its label`() {
        val xml = com.intellij.util.xmlb.XmlSerializer.serialize(TodoSettings.Values(defaultTarget = FixTarget.CLIPBOARD))
        val text = com.intellij.openapi.util.JDOMUtil.write(xml)
        assertTrue(text, text.contains("CLIPBOARD"))
        assertFalse(text, text.contains("Copy the prompt"))
        val back = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, TodoSettings.Values::class.java)
        assertEquals(FixTarget.CLIPBOARD, back.defaultTarget)
    }

    fun `test items file is kept per project`() {
        val project = TodoProjectSettings.getInstance(project)
        project.itemsFile = "notes/todo.json"
        assertEquals("notes/todo.json", project.state.itemsFile)
        project.loadState(TodoProjectSettings.Values("other.json"))
        assertEquals("other.json", project.itemsFile)
    }

    fun `test prompt options come from the values and short needs the MCP tools`() {
        val v = TodoSettings.Values(contextLines = 5, shortPrompt = true, extraInstructions = "Be brief.")
        val with = v.promptOptions("notes/todo.json", mcpAvailable = true)
        assertEquals(io.github.konove.notmytodo.handoff.PromptOptions(5, true, "Be brief.", "notes/todo.json"), with)
        assertFalse(v.promptOptions("notes/todo.json", mcpAvailable = false).short)
    }
}
