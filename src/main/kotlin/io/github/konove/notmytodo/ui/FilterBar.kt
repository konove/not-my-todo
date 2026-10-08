package io.github.konove.notmytodo.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The bar over the item list: the search field, the filters, the file scope and the view buttons.
 * They share a row while there is room. As the bar narrows the scope gives way first, then the search
 * field, and after that the bar wraps onto more rows, so that nothing is squeezed out or cut off.
 */
internal class FilterBar(
    private val search: JComponent, filters: JComponent, private val scope: JComponent, views: JComponent,
) : JPanel(null) {
    private val parts = listOf(search, filters, scope, views).onEach(::add)

    init {
        border = JBUI.Borders.empty(2, 4)
    }

    /** The least width a part is laid out at: the search field and the scope shrink, the buttons do not. */
    private fun least(part: JComponent): Int = when (part) {
        search -> JBUI.scale(SEARCH_MIN)
        scope -> minOf(JBUI.scale(SCOPE_MIN), part.preferredSize.width)
        else -> part.preferredSize.width
    }

    /** The parts row by row, each row holding as many as fit in [width] at their least widths. */
    private fun rows(width: Int): List<List<JComponent>> {
        val rows = mutableListOf(mutableListOf<JComponent>())
        var used = 0
        for (part in parts) {
            val w = least(part)
            if (rows.last().isNotEmpty() && used + JBUI.scale(GAP) + w > width) {
                rows += mutableListOf<JComponent>()
                used = 0
            }
            if (rows.last().isNotEmpty()) used += JBUI.scale(GAP)
            rows.last() += part
            used += w
        }
        return rows
    }

    private fun height(row: List<JComponent>): Int = row.maxOf { it.preferredSize.height }

    override fun doLayout() {
        val inner = width - insets.left - insets.right
        var y = insets.top
        for (row in rows(inner)) {
            val widths = row.associateWithTo(LinkedHashMap()) { minOf(least(it), inner) }
            var spare = maxOf(inner - widths.values.sum() - JBUI.scale(GAP) * (row.size - 1), 0)
            if (scope in row) {
                val more = minOf(spare, scope.preferredSize.width - widths.getValue(scope))
                widths[scope] = widths.getValue(scope) + more
                spare -= more
            }
            if (search in row) widths[search] = widths.getValue(search) + spare
            val h = height(row)
            var x = insets.left
            for ((part, w) in widths) {
                part.setBounds(x, y, w, h)
                x += w + JBUI.scale(GAP)
            }
            y += h + JBUI.scale(ROW_GAP)
        }
    }

    /** As tall as the rows that the width of the parent calls for: the bar spans it, and it is set before the bar is asked. */
    override fun getPreferredSize(): Dimension {
        val across = insets.left + insets.right
        val given = parent?.width?.takeIf { it > 0 } ?: width
        val rows = rows(if (given > 0) given - across else Int.MAX_VALUE)
        val wide = parts.sumOf { it.preferredSize.width } + JBUI.scale(GAP) * (parts.size - 1) + across
        val high = rows.sumOf(::height) + JBUI.scale(ROW_GAP) * (rows.size - 1) + insets.top + insets.bottom
        return Dimension(wide, high)
    }

    override fun getMinimumSize(): Dimension =
        Dimension(JBUI.scale(SEARCH_MIN) + insets.left + insets.right, preferredSize.height)

    private companion object {
        const val SEARCH_MIN = 120
        const val SCOPE_MIN = 100
        const val GAP = 8
        const val ROW_GAP = 4
    }
}
