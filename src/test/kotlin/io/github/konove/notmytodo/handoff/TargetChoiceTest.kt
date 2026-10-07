package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.handoff.TargetChoice.Availability
import io.github.konove.notmytodo.settings.FixTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetChoiceTest {
    private val all = Availability(channelOn = true, sessionConnected = true, terminal = true)

    @Test
    fun `an available target is used as asked`() {
        for (target in FixTarget.entries) {
            val choice = TargetChoice.choose(target, all)
            assertEquals(target, choice.target)
            assertNull(choice.reason)
        }
    }

    @Test
    fun `a session that is not there falls back to the terminal and says why`() {
        val choice = TargetChoice.choose(FixTarget.SESSION, all.copy(sessionConnected = false))
        assertEquals(FixTarget.TERMINAL, choice.target)
        assertEquals("No Claude Code session is connected to this project.", choice.reason)
    }

    @Test
    fun `the session target with the channel switched off says so`() {
        val choice = TargetChoice.choose(FixTarget.SESSION, all.copy(channelOn = false))
        assertEquals(FixTarget.TERMINAL, choice.target)
        assertEquals("Sending to a running session is switched off in the settings.", choice.reason)
    }

    @Test
    fun `with no terminal everything ends at the clipboard`() {
        val none = Availability(channelOn = true, sessionConnected = false, terminal = false)
        assertEquals(FixTarget.CLIPBOARD, TargetChoice.choose(FixTarget.SESSION, none).target)
        val choice = TargetChoice.choose(FixTarget.TERMINAL, none)
        assertEquals(FixTarget.CLIPBOARD, choice.target)
        assertEquals("The Terminal plugin is not available.", choice.reason)
    }

    @Test
    fun `falling back never goes up the order`() {
        val sessionOnly = Availability(channelOn = true, sessionConnected = true, terminal = false)
        assertEquals(FixTarget.CLIPBOARD, TargetChoice.choose(FixTarget.TERMINAL, sessionOnly).target)
        assertEquals(FixTarget.CLIPBOARD, TargetChoice.choose(FixTarget.CLIPBOARD, sessionOnly).target)
    }
}
