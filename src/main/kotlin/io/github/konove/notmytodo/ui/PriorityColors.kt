package io.github.konove.notmytodo.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** In progress is blue and waiting for review is green; the other statuses use the normal text colour. */
object StatusColors {
    fun attributes(status: Status): SimpleTextAttributes = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_BOLD,
        when (status) {
            Status.IN_PROGRESS -> JBColor(Color(0x2457C5), Color(0x6AA8FF))
            Status.FIXED -> JBColor(Color(0x0B6E5F), Color(0x5FBFA8))
            Status.OPEN -> null
            Status.DONE, Status.WONT_FIX -> JBColor.GRAY
        },
    )
}

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

    fun icon(p: Priority): Icon = Dot(solid(p))

    private class Dot(private val color: Color) : Icon {
        private val size = JBUI.scale(10)

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
