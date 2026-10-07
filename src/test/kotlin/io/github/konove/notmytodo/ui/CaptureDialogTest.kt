package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureDialogTest {
    @Test
    fun `chips show what was parsed`() {
        assertEquals("P1   #perf   #pathing", CaptureDialog.chipText("!p1 fix it #perf #pathing"))
        assertEquals("P2 (default)", CaptureDialog.chipText("fix it"))
        assertEquals("P2 (default)", CaptureDialog.chipText(""))
    }

    @Test
    fun `anchor text is file name and line range`() {
        val a = Anchor("src/sim/unit.cpp", 206, 209, "x", emptyList(), emptyList())
        assertEquals("unit.cpp:206–209", CaptureDialog.anchorText(a))
        assertEquals("unit.cpp:206", CaptureDialog.anchorText(a.copy(endLine = 206)))
    }

    @Test
    fun `the default chip names the priority from the settings`() {
        assertEquals("P1 (default)", CaptureDialog.chipText("fix it", Priority.P1))
        assertEquals("P3   #perf", CaptureDialog.chipText("!p3 fix it #perf", Priority.P1))
    }
}
