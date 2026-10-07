package io.github.konove.notmytodo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IconUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.konove.notmytodo.ide.AnchorTracker
import io.github.konove.notmytodo.ide.CodeTodos
import io.github.konove.notmytodo.ide.ItemActions
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.store.StoreException
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent

/** A filled button with a white icon, for the one action that is the next step. */
private class PrimaryIconButton(text: String, icon: Icon, action: () -> Unit) : JButton() {
    private val white = IconUtil.colorize(icon, Color.WHITE)

    init {
        toolTipText = text
        getAccessibleContext().accessibleName = text
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        border = null
        preferredSize = JBUI.size(28, 28)
        addActionListener { action() }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fill = JBColor(Color(0x3574F0), Color(0x3574F0))
            g2.color = if (model.isRollover || model.isPressed) fill.darker() else fill
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            white.paintIcon(this, g2, (width - white.iconWidth) / 2, (height - white.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}

/** A bordered box with round corners, which also cuts what is inside it to them. */
private class RoundedBox : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1)
    }

    // A child repainting itself would otherwise paint over the corners.
    override fun isPaintingOrigin(): Boolean = true

    override fun paintChildren(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(12).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            val inside = g2.create() as Graphics2D
            try {
                inside.clip(shape)
                super.paintChildren(inside)
            } finally {
                inside.dispose()
            }
            g2.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * The item selected in the panel: icon buttons over its title, facts, anchored code and details.
 * The Edit button swaps the title, facts and details for fields; Save writes them.
 */
class DetailPane(private val project: Project) : JPanel(BorderLayout()) {
    private val store get() = TodoService.getInstance(project).store
    private var current: TodoItem? = null

    internal val titleField = JBTextField().apply { emptyText.text = "Title" }
    private val detailsArea = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Details"
    }
    private val priorityBox = ComboBox(Priority.entries.toTypedArray()).apply { toolTipText = "Priority" }
    private val statusBox = ComboBox(Status.entries.toTypedArray()).apply { toolTipText = "Status" }
    private val tagsField = JBTextField().apply { emptyText.text = "#tags" }
    private val whereLabel = JBLabel().apply {
        isOpaque = true
        background = JBColor(Color(0xF7F8FA), Color(0x393B40))
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.merge(
            JBUI.Borders.empty(4, 10),
            JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
            true,
        )
    }
    private val viewTitle = readOnlyText().apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }
    private val viewMeta = SimpleColoredComponent().apply { isOpaque = false }
    private val viewDetails = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = null
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }
    private val viewDetailsScroll = ScrollPaneFactory.createScrollPane(viewDetails, true).apply {
        isOpaque = false
        viewport.isOpaque = false
        // The row takes the height the pane has left; without this it asks for the whole text's.
        preferredSize = JBUI.size(200, 60)
    }
    private var shownDetails: String? = null
    private var editing = false
    private lateinit var viewRows: List<Row>
    private lateinit var editRows: List<Row>
    private lateinit var viewDetailsRow: Row
    private val codeArea = JBTextArea(4, 20).apply {
        isEditable = false
        font = EditorUtil.getEditorFont()
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }
    private lateinit var codeRow: Row
    private var shownCode: Triple<String, Int, Int>? = null
    private var shownTint: Color? = null

    /** Whether the shown item is in the store, and not a TODO comment found in the code. */
    private val tracked: Boolean get() = current?.let { !CodeTodos.isCode(it) } == true

    private fun status(vararg shown: Status): () -> Boolean = { tracked && current?.status in shown }

    private val fixButton = PrimaryIconButton("Fix with Claude", AllIcons.Actions.Lightning) {
        current?.let { if (tracked) ItemActions.fix(project, it.id) else ItemActions.fixComment(project, it) }
    }

    private val toolbar = ActionManager.getInstance().createActionToolbar(
        "NotMyTodoDetail",
        DefaultActionGroup(
            IconAction("Track as an Item", AllIcons.General.Add, { current != null && !tracked }) { track() },
            IconAction("Start", AllIcons.Actions.Execute, status(Status.OPEN)) { setStatus(Status.IN_PROGRESS) },
            IconAction("Done", AllIcons.Actions.Checked, status(Status.OPEN, Status.IN_PROGRESS)) { setStatus(Status.DONE) },
            IconAction("Accept", AllIcons.RunConfigurations.TestPassed, status(Status.FIXED)) { setStatus(Status.DONE) },
            IconAction("Reopen", AllIcons.Actions.Rollback, status(Status.FIXED, Status.DONE, Status.WONT_FIX)) {
                setStatus(Status.OPEN)
            },
            Separator.getInstance(),
            IconAction("Jump to Code", AllIcons.Actions.EditSource, { current?.anchor != null }) {
                current?.let { ItemActions.navigate(project, it) }
            },
            IconAction("Re-attach to Selection", AllIcons.General.Locate, { tracked }) { reattach() },
            Separator.getInstance(),
            DecisionAction(),
            EditAction(),
            IconAction("Save", AllIcons.Actions.MenuSaveall, { tracked && editing }) { save() },
        ),
        true,
    )
    private val deleteToolbar = ActionManager.getInstance().createActionToolbar(
        "NotMyTodoDetail", DefaultActionGroup(IconAction("Delete", AllIcons.Actions.GC, { tracked }) { delete() }), true,
    )
    private val toolbarRow = JPanel(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = JBUI.Borders.empty(3, 8, 3, 4)
            add(fixButton)
        }, BorderLayout.WEST)
        add(toolbar.component, BorderLayout.CENTER)
        add(deleteToolbar.component, BorderLayout.EAST)
    }
    private val codeBox = RoundedBox().apply {
        add(whereLabel, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(codeArea, true), BorderLayout.CENTER)
    }

    private val form = panel {
        val title = row { cell(viewTitle).align(AlignX.FILL) }
        val meta = row { cell(viewMeta) }
        val titleEdit = row { cell(titleField).align(AlignX.FILL) }
        val factsEdit = row {
            cell(priorityBox)
            cell(statusBox)
            cell(tagsField).align(AlignX.FILL).resizableColumn()
        }
        codeRow = row { cell(codeBox).align(Align.FILL) }.resizableRow()
        viewDetailsRow = row { cell(viewDetailsScroll).align(Align.FILL) }.resizableRow()
        val detailsEdit = row { cell(JBScrollPane(detailsArea)).align(Align.FILL) }
        viewRows = listOf(title, meta)
        editRows = listOf(titleEdit, factsEdit, detailsEdit)
    }

    init {
        toolbar.targetComponent = this
        deleteToolbar.targetComponent = this
        form.border = JBUI.Borders.empty(6, 12)
        add(toolbarRow, BorderLayout.NORTH)
        add(form, BorderLayout.CENTER)
        show(null)
    }

    private fun readOnlyText() = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        font = JBUI.Fonts.label()
    }

    /** Marks the item as waiting for my decision, by its tag, so agents leave it alone. */
    private inner class DecisionAction : ToggleAction("Needs My Decision", null, AllIcons.General.User), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = tracked
        }

        override fun isSelected(e: AnActionEvent): Boolean = current?.needsDecision == true

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val item = current ?: return
            try {
                val saved = store.update(item.id) {
                    it.copy(tags = if (state) (it.tags + Tags.NEEDS_DECISION).distinct() else it.tags - Tags.NEEDS_DECISION)
                }
                show(saved)
            } catch (e: StoreException) {
                Messages.showErrorDialog(project, e.message, "Not My TODO")
            }
        }
    }

    private inner class EditAction : ToggleAction("Edit", null, AllIcons.Actions.Edit), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = tracked
        }

        override fun isSelected(e: AnActionEvent): Boolean = editing

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            editing = state
            // Leaving edit mode without saving drops what was typed.
            if (!state) current?.let { item ->
                current = null
                show(item)
            }
            showMode()
        }
    }

    /** Shows the read-only rows or the fields, whichever [editing] asks for. */
    private fun showMode() {
        val item = current
        viewRows.forEach { it.visible(!editing) }
        editRows.forEach { it.visible(editing) }
        viewDetailsRow.visible(!editing && item?.details?.isNotBlank() == true)
        toolbar.updateActionsAsync()
        revalidate()
        repaint()
    }

    val currentId: String? get() = current?.id

    /**
     * Shows [item]. When it is the item already shown, only fields I have not touched are
     * refreshed, so a change made elsewhere never wipes what I am typing, and my stale copy of
     * an untouched field is never what gets saved.
     */
    fun show(item: TodoItem?) {
        val shown = current?.takeIf { item != null && it.id == item.id }
        current = item
        form.isVisible = item != null
        toolbarRow.isVisible = item != null
        if (item == null) return
        if (shown == null) editing = false
        if (shown == null || titleField.text == shown.title) titleField.text = item.title
        if (shown == null || detailsArea.text == shown.details) detailsArea.text = item.details
        if (shown == null || priorityBox.selectedItem == shown.priority) priorityBox.selectedItem = item.priority
        if (shown == null || statusBox.selectedItem == shown.status) statusBox.selectedItem = item.status
        if (shown == null || Tags.parseList(tagsField.text) == shown.tags) {
            tagsField.text = item.tags.joinToString(" ") { "#$it" }
        }
        val code = CodeTodos.isCode(item)
        val anchor = item.anchor
        viewTitle.text = item.title
        // The panel refreshes often; leave the text and the scroll position alone when nothing changed.
        if (item.details != shownDetails) {
            shownDetails = item.details
            viewDetails.text = Markdown.html(item.details)
            viewDetails.caretPosition = 0
        }
        val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES
        viewMeta.clear()
        if (code) {
            viewMeta.icon = AllIcons.General.TodoDefault
            viewMeta.append("Comment in the code", grey)
        } else {
            viewMeta.icon = PriorityColors.icon(item.priority)
            viewMeta.append("${item.priority.name}  ·  ", grey)
            viewMeta.append(item.status.label, StatusColors.attributes(item.status))
            if (item.tags.isNotEmpty()) viewMeta.append("  ·  " + item.tags.joinToString(" ") { "#$it" }, grey)
            viewMeta.append("  ·  ${item.id}", grey)
            if (anchor == null) viewMeta.append("  ·  note", grey)
            viewMeta.append("  ·  ", grey)
            if (item.needsDecision) viewMeta.append("needs my decision", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            else if (!item.isClosed && item.status != Status.FIXED) viewMeta.append("agent can fix", grey)
        }
        whereLabel.text = when {
            anchor == null -> ""
            anchor.lost -> "Anchor lost, was ${anchor.path}"
            anchor.startLine == anchor.endLine -> "${anchor.path}:${anchor.startLine}"
            else -> "${anchor.path}:${anchor.startLine}–${anchor.endLine}"
        }
        fixButton.isVisible = code || item.status == Status.OPEN || item.status == Status.IN_PROGRESS
        codeRow.visible(anchor != null)
        if (anchor != null) showCode(anchor, PriorityColors.tint(item.priority))
        deleteToolbar.updateActionsAsync()
        showMode()
    }

    /**
     * Shows the anchored lines with [CONTEXT_LINES] of the file either side, numbered, and the
     * anchored ones tinted. A lost anchor shows only the text it was saved with.
     */
    private fun showCode(anchor: Anchor, tint: Color) {
        val fileLines = if (anchor.lost) null else TodoService.getInstance(project).readText(anchor.path)?.lines()
        val first: Int
        val shownLines: List<String>
        if (fileLines == null || anchor.endLine > fileLines.size) {
            first = anchor.startLine
            shownLines = anchor.text.lines()
        } else {
            first = maxOf(1, anchor.startLine - CONTEXT_LINES)
            shownLines = fileLines.subList(first - 1, minOf(fileLines.size, anchor.endLine + CONTEXT_LINES))
        }
        val width = (first + shownLines.size).toString().length
        val numbered = shownLines.mapIndexed { i, line -> (first + i).toString().padStart(width) + "  " + line }
        val shown = Triple(numbered.joinToString("\n"), anchor.startLine - first, anchor.endLine - first)
        // The panel refreshes often; leave the text and the scroll position alone when nothing changed.
        if (shown == shownCode && tint == shownTint) return
        shownCode = shown
        shownTint = tint
        codeArea.text = shown.first
        val start = codeArea.getLineStartOffset(shown.second)
        val end = codeArea.getLineEndOffset(minOf(shown.third, codeArea.lineCount - 1))
        codeArea.highlighter.removeAllHighlights()
        codeArea.highlighter.addHighlight(start, end, LinePainter(tint))
        codeArea.caretPosition = start
        SwingUtilities.invokeLater {
            val from = codeArea.modelToView2D(start)?.bounds ?: return@invokeLater
            val to = codeArea.modelToView2D(maxOf(start, end - 1))?.bounds ?: from
            val margin = maxOf(0, (codeArea.visibleRect.height - (to.y + to.height - from.y)) / 2)
            codeArea.scrollRectToVisible(Rectangle(0, from.y - margin, 1, to.y + to.height - from.y + 2 * margin))
        }
    }

    /** Tints whole lines, edge to edge. */
    private class LinePainter(private val color: Color) : Highlighter.HighlightPainter {
        override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: JTextComponent) {
            val from = c.modelToView2D(p0)?.bounds ?: return
            val to = c.modelToView2D(maxOf(p0, p1 - 1))?.bounds ?: from
            g.color = color
            g.fillRect(0, from.y, c.width, to.y + to.height - from.y)
        }
    }

    private fun track() {
        val item = current ?: return
        CaptureDialog(project, CodeTodos.trackedAnchor(project, item), CodeTodos.trackedTitle(item)).show()
    }

    internal fun save() {
        val item = current?.takeIf { tracked } ?: return
        try {
            val title = titleField.text
            val details = detailsArea.text
            val priority = priorityBox.selectedItem as Priority
            val status = statusBox.selectedItem as Status
            val tags = Tags.parseList(tagsField.text)
            // Only the fields I edited are written; the rest keep whatever is in the file now.
            val saved = store.update(item.id) {
                it.copy(
                    title = if (title != item.title) title else it.title,
                    details = if (details != item.details) details else it.details,
                    priority = if (priority != item.priority) priority else it.priority,
                    status = if (status != item.status) status else it.status,
                    tags = if (tags != item.tags) tags else it.tags,
                )
            }
            current = null
            show(saved)
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        }
    }

    internal fun setStatus(status: Status) {
        val item = current ?: return
        ItemActions.setStatus(project, item.id, status)
        show(store.find(item.id))
    }

    private fun reattach() {
        val item = current ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null || !project.service<AnchorTracker>().reattach(item.id, editor)) {
            Messages.showInfoMessage(project, "Select the code in the editor first.", "Not My TODO")
        }
    }

    private fun delete() {
        val item = current ?: return
        val answer = Messages.showYesNoDialog(project, "Delete ${item.id}: ${item.title}?", "Not My TODO", null)
        if (answer != Messages.YES) return
        try {
            store.delete(item.id)
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        }
    }

    private companion object {
        const val CONTEXT_LINES = 15
    }
}
