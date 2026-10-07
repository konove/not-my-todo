package io.github.konove.notmytodo.handoff

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.handoff.TargetChoice.Availability
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.settings.FixTarget
import io.github.konove.notmytodo.settings.TodoSettings

class HandoffDialogTest : BasePlatformTestCase() {
    private val item = TodoItem(id = "T-3", title = "do the thing")

    private fun target(wanted: FixTarget, a: Availability): FixTarget {
        val dialog = HandoffDialog(project, item, emptyMap(), a, wanted)
        try {
            return dialog.target
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun `test the wanted target is preselected when it is available`() {
        val all = Availability(channelOn = true, sessionConnected = true, terminal = true)
        assertEquals(FixTarget.SESSION, target(FixTarget.SESSION, all))
        assertEquals(FixTarget.TERMINAL, target(FixTarget.TERMINAL, all))
        assertEquals(FixTarget.CLIPBOARD, target(FixTarget.CLIPBOARD, all))
    }

    fun `test an unavailable target falls back`() {
        val noTerminal = Availability(channelOn = false, sessionConnected = false, terminal = false)
        assertEquals(FixTarget.CLIPBOARD, target(FixTarget.TERMINAL, noTerminal))
        val noSession = Availability(channelOn = true, sessionConnected = false, terminal = true)
        assertEquals(FixTarget.TERMINAL, target(FixTarget.SESSION, noSession))
    }

    fun `test the button names what the chosen target does`() {
        val a = Availability(channelOn = false, sessionConnected = false, terminal = true)
        for ((wanted, text) in listOf(FixTarget.TERMINAL to "Open in Terminal", FixTarget.CLIPBOARD to "Copy Prompt")) {
            val dialog = HandoffDialog(project, item, emptyMap(), a, wanted)
            try {
                assertEquals(text, dialog.sendText)
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
        }
    }

    fun `test a phrase goes into the done-when text and the prompt, and comes out again`() {
        val a = Availability(channelOn = false, sessionConnected = false, terminal = true)
        val dialog = HandoffDialog(project, item, emptyMap(), a, FixTarget.TERMINAL)
        try {
            assertEquals(TodoSettings.Values().donePhraseList(), dialog.phrases.keys.toList())
            dialog.toggle("the tests pass")
            dialog.toggle("the project builds")
            assertEquals("the tests pass and the project builds", dialog.doneWhenText)
            assertTrue(dialog.prompt.contains("Done when: the tests pass and the project builds"))
            assertEquals(listOf("the project builds", "the tests pass"), dialog.phrases.filterValues { it }.keys.toList())
            dialog.toggle("the tests pass")
            assertEquals("the project builds", dialog.doneWhenText)
            dialog.doneWhenText = "it is fast and the tests pass"
            assertEquals(listOf("the tests pass"), dialog.phrases.filterValues { it }.keys.toList())
            assertFalse(dialog.skipNextTime)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun `test the prompt follows the options`() {
        val a = Availability(channelOn = false, sessionConnected = false, terminal = true)
        val dialog = HandoffDialog(project, item, emptyMap(), a, FixTarget.TERMINAL, PromptOptions(extra = "Be brief."))
        try {
            assertTrue(dialog.prompt.startsWith("Fix TODO item T-3"))
            assertTrue(dialog.prompt.contains("Be brief."))
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
