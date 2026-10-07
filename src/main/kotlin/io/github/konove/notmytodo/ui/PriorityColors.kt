package io.github.konove.notmytodo.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.konove.notmytodo.model.Priority
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Orange, blue, grey: told apart by lightness as well as hue. */
object PriorityColors {
    fun solid(p: Priority): Color = when (p) {
        Priority.P1 -> JBColor(Color(0xD9822B), Color(0xF2A65A))
        Priority.P2 -> JBColor(Color(0x2F6FD0), Color(0x6AA8FF))
        Priority.P3 -> JBColor(Color(0x8A8F99), Color(0x6F7684))
    }

    fun tint(p: Priority): Color = when (p) {
        Priority.P1 -> JBColor(Color(0xFCE9D2), Color(0x4A3A26))
        Priority.P2 -> JBColor(Color(0xDCE9FB), Color(0x27364D))
        Priority.P3 -> JBColor(Color(0xECEDEF), Color(0x30333A))
    }

    fun icon(p: Priority): Icon = dot(p, 10)

    /** The dot [size] across, before scaling. */
    fun dot(p: Priority, size: Int): Icon = Dot(solid(p), JBUI.scale(size))

    private class Dot(private val color: Color, private val size: Int) : Icon {
        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x, y, size, size)
            } finally {
                g2.dispose()
            }
        }
    }
}
