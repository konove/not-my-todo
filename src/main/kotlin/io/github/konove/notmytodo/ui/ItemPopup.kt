package io.github.konove.notmytodo.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.konove.notmytodo.ide.ItemActions
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel

/** The small popup shown when a gutter mark is clicked. */
object ItemPopup {
    fun show(project: Project, itemId: String, event: AnActionEvent) {
        val item = TodoService.getInstance(project).store.find(itemId) ?: return
        val tags = item.tags.joinToString(" ") { "#$it" }
        val details = if (item.details.isBlank()) "" else "<br><br>" + StringUtil.escapeXmlEntities(item.details).replace("\n", "<br>")
        val label = JBLabel(
            "<html><body style='width: 320px'><b>${item.id} · ${item.priority.json.uppercase()} · " +
                "${StringUtil.escapeXmlEntities(item.title)}</b>$details<br><br>" +
                "<span style='color: gray'>${item.status.label} $tags</span></body></html>"
        )
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(label, BorderLayout.CENTER)
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

    fun buttons(project: Project, item: TodoItem, close: () -> Unit): List<JButton> {
        fun button(text: String, action: () -> Unit) = JButton(text).apply {
            addActionListener {
                close()
                action()
            }
        }
        val result = ArrayList<JButton>()
        if (item.status == Status.OPEN || item.status == Status.IN_PROGRESS) {
            result += button("Fix with Claude") { ItemActions.fix(project, item.id) }
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
