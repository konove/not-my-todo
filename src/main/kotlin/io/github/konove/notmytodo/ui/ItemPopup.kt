package io.github.konove.notmytodo.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import io.github.konove.notmytodo.ide.ItemActions
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/** The small popup shown when a gutter mark is clicked: the item as the detail pane shows it, and what can be done with it. */
object ItemPopup {
    fun show(project: Project, itemId: String, event: AnActionEvent) {
        val store = TodoService.getInstance(project).store
        val item = store.find(itemId) ?: return
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val panel = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            border = JBUI.Borders.empty(12, 14, 10, 14)
            add(content(item, Links(store.items).isBlocked(item)), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        buttons(project, item) { popup.cancel() }.forEach { buttonRow.add(it) }
        val mouse = event.inputEvent as? MouseEvent
        if (mouse != null) popup.show(RelativePoint(mouse)) else popup.showInFocusCenter()
    }

    /**
     * The title, the chips and the details of [item], one under the other and [WIDTH] wide. Each
     * wraps to that width, and the details scroll when they are longer than [DETAILS_MAX].
     */
    internal fun content(item: TodoItem, blocked: Boolean): JPanel {
        val width = JBUI.scale(WIDTH)
        val title = JBTextArea(item.title).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            font = JBUI.Fonts.label().let { it.deriveFont(Font.BOLD, it.size2D + 1f) }
        }
        val strip = MetaStrip().apply { Chips.strip(item, blocked).forEach { add(Chip(it)) } }
        val parts = ArrayList<JComponent>(listOf(title, strip))
        // What wraps says how high it is only once it knows how wide it is.
        parts.forEach { it.setSize(width, Short.MAX_VALUE.toInt()) }
        if (item.details.isNotBlank()) {
            val details = JEditorPane().apply {
                editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
                isEditable = false
                isOpaque = false
                border = null
                addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
                text = Markdown.html(item.details)
                caretPosition = 0
                setSize(width, Short.MAX_VALUE.toInt())
            }
            parts += ScrollPaneFactory.createScrollPane(details, true).apply {
                isOpaque = false
                viewport.isOpaque = false
                preferredSize = Dimension(width, minOf(details.preferredSize.height, JBUI.scale(DETAILS_MAX)))
            }
        }
        return JPanel(VerticalLayout(JBUI.scale(8))).apply {
            parts.forEach(::add)
            preferredSize = Dimension(width, preferredSize.height)
        }
    }

    private const val WIDTH = 420
    private const val DETAILS_MAX = 280

    fun buttons(project: Project, item: TodoItem, close: () -> Unit): List<JButton> {
        fun button(text: String, action: () -> Unit) = JButton(text).apply {
            addActionListener {
                close()
                action()
            }
        }
        val result = ArrayList<JButton>()
        if (item.status == Status.OPEN || item.status == Status.IN_PROGRESS) {
            // The next step, so the one drawn as the default button.
            result += button("Fix with Claude") { ItemActions.fix(project, item.id) }.apply {
                putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            }
        }
        if (item.status == Status.OPEN) result += button("Start") { ItemActions.setStatus(project, item.id, Status.IN_PROGRESS) }
        if (item.status == Status.FIXED) result += button("Accept") { ItemActions.setStatus(project, item.id, Status.DONE) }
        if (item.status == Status.FIXED) result += button("Reopen") { ItemActions.setStatus(project, item.id, Status.OPEN) }
        if (item.status == Status.OPEN || item.status == Status.IN_PROGRESS) {
            result += button("Done") { ItemActions.setStatus(project, item.id, Status.DONE) }
        }
        result += button("Open in panel") { ItemActions.openInPanel(project, item.id) }
        return result
    }
}
