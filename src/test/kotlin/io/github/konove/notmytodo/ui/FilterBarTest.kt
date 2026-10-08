package io.github.konove.notmytodo.ui

import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterBarTest {
    private val search = box(300)
    private val filters = box(240)
    private val scope = box(340)
    private val views = box(110)
    private val bar = FilterBar(search, filters, scope, views)
    private val parts = listOf(search, filters, scope, views)

    private fun box(width: Int): JComponent = JPanel().apply { preferredSize = Dimension(width, 24) }

    private fun layOut(width: Int) {
        bar.setSize(width, 0)
        bar.setSize(width, bar.preferredSize.height)
        bar.doLayout()
    }

    private fun rows() = parts.groupBy { it.y }.toSortedMap().values.toList()

    @Test
    fun `a wide bar is one row, and the search field takes what is left`() {
        layOut(1000)
        assertEquals(listOf(parts), rows())
        assertEquals(340, scope.width)
        assertTrue(search.width > 120)
        assertEquals(1000 - bar.insets.right, views.x + views.width)
    }

    @Test
    fun `the scope gives way before the search field does`() {
        layOut(700)
        assertEquals(listOf(parts), rows())
        assertTrue(scope.width in 100 until 340)
        assertTrue(search.width >= 120)
    }

    @Test
    fun `a narrow bar wraps, and nothing is squeezed out or cut off`() {
        for (width in listOf(500, 300)) {
            layOut(1000)
            val oneRow = bar.preferredSize.height
            layOut(width)
            assertTrue("$width", rows().size > 1)
            assertTrue("$width", bar.preferredSize.height > oneRow)
            assertTrue("$width", search.width >= 120)
            assertTrue("$width", scope.width >= 100)
            assertEquals("$width", 240, filters.width)
            assertEquals("$width", 110, views.width)
            parts.forEach {
                assertTrue("$width", it.x >= 0 && it.x + it.width <= width)
                assertTrue("$width", it.y >= 0 && it.y + it.height <= bar.height)
            }
            rows().forEach { row ->
                row.zipWithNext { a, b -> assertTrue("$width", a.x + a.width <= b.x) }
            }
        }
    }

    @Test
    fun `the search field has a row to itself when it is all that fits`() {
        layOut(300)
        assertEquals(listOf(search), rows().first())
        assertEquals(300 - bar.insets.left - bar.insets.right, search.width)
    }
}
