package io.github.konove.notmytodo.capture

import io.github.konove.notmytodo.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureParserTest {
    @Test
    fun `plain text is the title`() {
        assertEquals(Captured("fix the loop", null, emptyList()), CaptureParser.parse("  fix   the loop "))
    }

    @Test
    fun `priority and tags are pulled out wherever they appear`() {
        assertEquals(
            Captured("cache findPath per cell", Priority.P1, listOf("perf", "pathing")),
            CaptureParser.parse("!p1 cache #perf findPath per cell #Pathing"),
        )
    }

    @Test
    fun `the last priority wins and tags are not repeated`() {
        assertEquals(Captured("x", Priority.P3, listOf("a")), CaptureParser.parse("!P1 x #a !p3 #a"))
    }

    @Test
    fun `code-like tokens stay in the title`() {
        assertEquals(
            Captured("a#b !x !p4 # ! is odd", null, emptyList()),
            CaptureParser.parse("a#b !x !p4 # ! is odd"),
        )
    }

    @Test
    fun `nothing but tokens is rejected`() {
        assertNull(CaptureParser.parse(""))
        assertNull(CaptureParser.parse("   "))
        assertNull(CaptureParser.parse("!p1 #perf"))
    }
}
