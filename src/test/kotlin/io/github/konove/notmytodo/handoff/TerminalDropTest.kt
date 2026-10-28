package io.github.konove.notmytodo.handoff

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalDropTest {
    @Test
    fun `a dropped prompt is a bracketed paste whose lines end in carriage returns`() {
        assertEquals("\u001b[200~Fix T-1\rTitle: a\rb\u001b[201~", TerminalDrop.pasted("Fix T-1\nTitle: a\r\nb"))
    }

    @Test
    fun `an item's text cannot end the paste and type a command`() {
        assertEquals("\u001b[200~x[201~rm -rf ~\r\u001b[201~", TerminalDrop.pasted("x\u001b[201~rm -rf ~\n"))
    }
}
