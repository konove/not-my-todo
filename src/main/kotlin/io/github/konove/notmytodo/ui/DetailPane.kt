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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
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
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import io.github.konove.notmytodo.ide.AnchorTracker
import io.github.konove.notmytodo.ide.CodeTodos
import io.github.konove.notmytodo.ide.ItemActions
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.store.StoreException
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.Shape
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
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

/** A tag, as a small rounded label. */
private class Chip(tag: String) : JBLabel(tag) {
    init {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(1, 7)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(0xF0F1F4), Color(0x393B40))
            g2.fillRoundRect(0, 0, width, height, height, height)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/**
 * The row grid never makes a row narrower than its minimum, and what wraps gives the width it last
 * had as its minimum. So what wraps asks for little, takes the width it is given, and has its
 * height asked again once that width is known.
 */
private fun narrowed(size: Dimension): Dimension = size.apply { width = minOf(width, JBUI.scale(100)) }

private class WrappedText : JBTextArea() {
    override fun getPreferredSize(): Dimension = narrowed(super.getPreferredSize())

    override fun getMinimumSize(): Dimension = preferredSize

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val resized = width != this.width
        super.setBounds(x, y, width, height)
        if (resized) SwingUtilities.invokeLater(::revalidate)
    }
}

/** The facts and the tags in a line that goes on to the next when the column is narrow. */
internal class MetaStrip : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))) {
    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = narrowed(super.getPreferredSize())

    override fun getMinimumSize(): Dimension = preferredSize

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val resized = width != this.width
        super.setBounds(x, y, width, height)
        if (resized) SwingUtilities.invokeLater(::revalidate)
    }
}

/** The text beside the code when the pane is wide enough for both, and above it when it is not. */
internal class Columns(
    private val text: JComponent, private val code: JComponent, private val textGrows: () -> Boolean,
) : JPanel(null) {
    init {
        isOpaque = false
        add(text)
        add(code)
    }

    override fun getPreferredSize(): Dimension = JBUI.size(200, 120)

    override fun doLayout() {
        val area = Rectangle(0, 0, width, height).also { JBInsets.removeFrom(it, insets) }
        if (!code.isVisible) {
            text.bounds = area
            return
        }
        val gap = JBUI.scale(16)
        if (area.width >= JBUI.scale(WIDE)) {
            // Lines of text longer than this are hard to read; the code takes what is over.
            val textWidth = minOf((area.width * TEXT_SHARE).toInt(), JBUI.scale(TEXT_MAX))
            text.setBounds(area.x, area.y, textWidth, area.height)
            code.setBounds(area.x + textWidth + gap, area.y, area.width - textWidth - gap, area.height)
        } else {
            val half = (area.height - gap) / 2
            val textHeight = if (textGrows()) half else minOf(text.preferredSize.height, half)
            text.setBounds(area.x, area.y, area.width, textHeight)
            code.setBounds(area.x, area.y + textHeight + gap, area.width, area.height - textHeight - gap)
        }
    }

    private companion object {
        const val WIDE = 900
        const val TEXT_SHARE = 0.55
        const val TEXT_MAX = 760
    }
}

/**
 * The item selected in the panel: icon buttons over its title, facts, anchored code, details and comments.
 * The code stands to the right of the rest, or under it in a narrow pane. It is that of one anchor at a
 * time; an item with several is stepped through with the arrows beside the place.
 * The Edit button swaps the title, facts and details for fields; Save writes them.
 */
class DetailPane(private val project: Project) : JPanel(BorderLayout()) {
    private val store get() = TodoService.getInstance(project).store
    private var current: TodoItem? = null

    /** Which of the item's anchors the code box shows, and Jump, Re-attach and Remove act on. */
    private var anchorIndex = 0
    private val shownAnchor: Anchor? get() = current?.anchors?.getOrNull(anchorIndex)

    internal val titleField = JBTextField().apply { emptyText.text = "Title" }
    private val detailsArea = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Details"
    }
    private val priorityBox = ComboBox(Priority.entries.toTypedArray()).apply { toolTipText = "Priority" }
    private val statusBox = ComboBox(Status.entries.toTypedArray()).apply { toolTipText = "Status" }
    private val tagsField = JBTextField().apply { emptyText.text = "#tags" }
    private val blockedField = JBTextField().apply { emptyText.text = "Blocked by: T-1 T-2" }
    private val duplicateField = JBTextField().apply { emptyText.text = "Duplicate of" }
    private val parentField = JBTextField().apply { emptyText.text = "Part of" }

    /** Blocked by, duplicate of, part of. */
    internal val linkFields get() = listOf(blockedField, duplicateField, parentField)

    /** Shows the item with this id; set by the panel that holds the pane. */
    var onOpen: (String) -> Unit = {}
    internal val whereLabel = JBLabel().apply { font = JBUI.Fonts.smallFont() }

    /** The arrows that step through the anchors, shown beside the place when the item has several. */
    internal val anchorSteps = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(InplaceButton("Previous Anchor", AllIcons.Actions.Back) { step(-1) })
        add(InplaceButton("Next Anchor", AllIcons.Actions.Forward) { step(1) })
    }
    private val whereStrip = JPanel(BorderLayout()).apply {
        background = JBColor(Color(0xF7F8FA), Color(0x393B40))
        border = JBUI.Borders.merge(
            JBUI.Borders.empty(4, 10),
            JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
            true,
        )
        add(whereLabel, BorderLayout.CENTER)
        add(anchorSteps, BorderLayout.EAST)
    }
    internal val viewTitle: JBTextArea = readOnlyText().apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }
    internal val viewMeta = SimpleColoredComponent().apply { isOpaque = false }
    internal val metaStrip = MetaStrip().apply { add(viewMeta) }

    /** The item's links to other items; an id is clicked to go to that item. */
    internal val viewLinks = SimpleColoredComponent().apply {
        isOpaque = false
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                (getFragmentTagAt(e.x) as? String)?.let { onOpen(it) }
            }

            override fun mouseMoved(e: MouseEvent) {
                cursor = if (getFragmentTagAt(e.x) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }
    private var shownTags: List<String>? = null
    internal val viewDetails = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = null
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }
    internal val viewDetailsScroll = ScrollPaneFactory.createScrollPane(viewDetails, true).apply {
        isOpaque = false
        viewport.isOpaque = false
        // The row takes the height the pane has left; without this it asks for the whole text's.
        preferredSize = JBUI.size(200, 60)
    }
    private var shownDetails: Pair<String, List<Comment>>? = null
    private var editing = false
    private lateinit var viewRows: List<Row>
    private lateinit var editRows: List<Row>
    private lateinit var viewDetailsRow: Row
    private lateinit var viewLinksRow: Row
    private val codeArea = JBTextArea(4, 20).apply {
        isEditable = false
        font = EditorUtil.getEditorFont()
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }
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
            IconAction("Jump to Code", AllIcons.Actions.EditSource, { shownAnchor != null }) {
                current?.let { ItemActions.navigate(project, it, shownAnchor) }
            },
            IconAction("Re-attach to Selection", AllIcons.General.Locate, { tracked }) { reattach() },
            IconAction("Add an Anchor: the Selection, or the Whole File", AllIcons.General.Add, { tracked }) { addAnchor() },
            IconAction("Remove This Anchor", AllIcons.General.Remove, { tracked && shownAnchor != null }) { removeAnchor() },
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
    internal val codeBox: JPanel = RoundedBox().apply {
        add(whereStrip, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(codeArea, true), BorderLayout.CENTER)
    }

    internal val textColumn = panel {
        val title = row { cell(viewTitle).align(AlignX.FILL) }
        val meta = row { cell(metaStrip).align(AlignX.FILL) }
        viewLinksRow = row { cell(viewLinks).align(AlignX.FILL) }
        val titleEdit = row { cell(titleField).align(AlignX.FILL) }
        val factsEdit = row {
            cell(priorityBox)
            cell(statusBox)
            cell(tagsField).align(AlignX.FILL).resizableColumn()
        }
        val linksEdit = row {
            cell(blockedField).align(AlignX.FILL).resizableColumn()
            cell(duplicateField)
            cell(parentField)
        }
        viewDetailsRow = row { cell(viewDetailsScroll).align(Align.FILL) }.resizableRow()
        val detailsEdit = row { cell(JBScrollPane(detailsArea)).align(Align.FILL) }.resizableRow()
        viewRows = listOf(title, meta)
        editRows = listOf(titleEdit, factsEdit, linksEdit, detailsEdit)
    }
    internal val columns = Columns(textColumn, codeBox) { editing || viewDetailsScroll.isVisible }

    init {
        toolbar.targetComponent = this
        deleteToolbar.targetComponent = this
        columns.border = JBUI.Borders.empty(6, 12)
        add(toolbarRow, BorderLayout.NORTH)
        add(columns, BorderLayout.CENTER)
        show(null)
    }

    private fun readOnlyText() = WrappedText().apply {
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
        viewLinksRow.visible(!editing && viewLinks.iterator().hasNext())
        viewDetailsRow.visible(!editing && item != null && (item.details.isNotBlank() || item.comments.isNotEmpty()))
        toolbar.updateActionsAsync()
        columns.revalidate()
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
        columns.isVisible = item != null
        toolbarRow.isVisible = item != null
        if (item == null) return
        if (shown == null) {
            editing = false
            anchorIndex = 0
        }
        anchorIndex = anchorIndex.coerceIn(0, maxOf(0, item.anchors.lastIndex))
        if (shown == null || titleField.text == shown.title) titleField.text = item.title
        if (shown == null || detailsArea.text == shown.details) detailsArea.text = item.details
        if (shown == null || priorityBox.selectedItem == shown.priority) priorityBox.selectedItem = item.priority
        if (shown == null || statusBox.selectedItem == shown.status) statusBox.selectedItem = item.status
        if (shown == null || Tags.parseList(tagsField.text) == shown.tags) {
            tagsField.text = item.tags.joinToString(" ") { "#$it" }
        }
        if (shown == null || blockedField.text == shown.blockedBy.joinToString(" ")) blockedField.text = item.blockedBy.joinToString(" ")
        if (shown == null || duplicateField.text == shown.duplicateOf.orEmpty()) duplicateField.text = item.duplicateOf.orEmpty()
        if (shown == null || parentField.text == shown.parent.orEmpty()) parentField.text = item.parent.orEmpty()
        val code = CodeTodos.isCode(item)
        val links = if (code) Links.NONE else Links(store.items)
        showLinks(item, links)
        val anchor = item.anchors.getOrNull(anchorIndex)
        viewTitle.text = item.title
        // The panel refreshes often; leave the text and the scroll position alone when nothing changed.
        val text = item.details to item.comments
        if (text != shownDetails) {
            shownDetails = text
            viewDetails.text = ItemText.html(item, grey = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()))
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
            viewMeta.append("  ·  ${item.id}", grey)
            if (anchor == null) viewMeta.append("  ·  note", grey)
            if (item.needsDecision) {
                viewMeta.append("  ·  ", grey)
                viewMeta.append("needs my decision", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            } else if (links.isBlocked(item)) {
                viewMeta.append("  ·  blocked", grey)
            } else if (!item.isClosed && item.status != Status.FIXED) {
                viewMeta.append("  ·  agent can fix", grey)
            }
        }
        val tags = if (code) emptyList() else item.tags
        if (tags != shownTags) {
            shownTags = tags
            metaStrip.components.filterIsInstance<Chip>().forEach(metaStrip::remove)
            tags.forEach { metaStrip.add(Chip(it)) }
        }
        whereLabel.text = anchor?.let { where(it, item.anchors.size) }.orEmpty()
        whereLabel.toolTipText = anchor?.path
        anchorSteps.isVisible = item.anchors.size > 1
        fixButton.isVisible = code || item.status == Status.OPEN || item.status == Status.IN_PROGRESS
        codeBox.isVisible = anchor != null
        if (anchor != null) showCode(anchor, PriorityColors.tint(item.priority))
        deleteToolbar.updateActionsAsync()
        showMode()
    }

    /** Fills [viewLinks]: what the item waits for, duplicates and is a part of, and the parts it has. A closed item's id is struck out. */
    private fun showLinks(item: TodoItem, links: Links) {
        val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES
        viewLinks.clear()
        fun ids(label: String, ids: List<String>) {
            if (ids.isEmpty()) return
            viewLinks.append((if (viewLinks.iterator().hasNext()) "  ·  " else "") + label, grey)
            ids.forEach { id ->
                val closed = store.find(id)?.isClosed != false
                val style = if (closed) SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBUI.CurrentTheme.Link.Foreground.ENABLED) else SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                viewLinks.append(" ")
                viewLinks.append(id, style, id)
            }
        }
        val parts = links.children(item.id)
        ids("Blocked by", item.blockedBy)
        ids("Duplicate of", listOfNotNull(item.duplicateOf))
        ids("Part of", listOfNotNull(item.parent))
        ids("Parts, ${parts.count { it.isClosed }}/${parts.size} closed:", parts.map { it.id })
    }

    /**
     * The anchor's place, with the directories greyed and shortened so the file name and lines stand out.
     * When the item has several anchors, [of] of them, it starts with which one this is.
     */
    private fun where(anchor: Anchor, of: Int): String {
        val (dirs, file) = PathText.split(anchor.path)
        val lines = if (anchor.lost || anchor.isFile) "" else ":${anchor.linesText("–")}"
        val grey = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val count = if (of > 1) "${anchorIndex + 1} of $of  ·  " else ""
        val before = count + (if (anchor.lost) "Anchor lost, was " else "") + dirs
        val after = if (anchor.isFile) "  ·  whole file" else ""
        return "<html><font color=\"$grey\">${StringUtil.escapeXmlEntities(before)}</font>" +
            "<span>${StringUtil.escapeXmlEntities(file + lines)}</span>" +
            "<font color=\"$grey\">${StringUtil.escapeXmlEntities(after)}</font></html>"
    }

    /**
     * Shows the anchored lines with [CONTEXT_LINES] of the file either side, numbered, and the
     * anchored ones tinted. A lost anchor shows only the text it was saved with. An anchor on a
     * whole file shows how the file starts, with nothing tinted.
     */
    private fun showCode(anchor: Anchor, tint: Color) {
        val fileLines = if (anchor.lost) null else TodoService.getInstance(project).readText(anchor.path)?.lines()
        val first: Int
        val shownLines: List<String>
        if (anchor.isFile) {
            first = 1
            shownLines = fileLines.orEmpty().take(2 * CONTEXT_LINES)
        } else if (fileLines == null || anchor.endLine > fileLines.size) {
            first = anchor.startLine
            shownLines = anchor.text.lines()
        } else {
            first = maxOf(1, anchor.startLine - CONTEXT_LINES)
            shownLines = fileLines.subList(first - 1, minOf(fileLines.size, anchor.endLine + CONTEXT_LINES))
        }
        val width = (first + shownLines.size).toString().length
        val numbered = shownLines.mapIndexed { i, line -> (first + i).toString().padStart(width) + "  " + line }
        val shown = if (anchor.isFile) Triple(numbered.joinToString("\n"), -1, -1)
        else Triple(numbered.joinToString("\n"), anchor.startLine - first, anchor.endLine - first)
        // The panel refreshes often; leave the text and the scroll position alone when nothing changed.
        if (shown == shownCode && tint == shownTint) return
        shownCode = shown
        shownTint = tint
        codeArea.text = shown.first
        codeArea.highlighter.removeAllHighlights()
        if (shown.second < 0) {
            codeArea.caretPosition = 0
            return
        }
        val start = codeArea.getLineStartOffset(shown.second)
        val end = codeArea.getLineEndOffset(minOf(shown.third, codeArea.lineCount - 1))
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
            val blockedBy = ItemId.parseList(blockedField.text)
            val duplicateOf = ItemId.parseList(duplicateField.text).also { require(it.size < 2) { "an item is a duplicate of one item" } }.firstOrNull()
            val parent = ItemId.parseList(parentField.text).also { require(it.size < 2) { "an item is a part of one item" } }.firstOrNull()
            // Only the fields I edited are written; the rest keep whatever is in the file now.
            val saved = store.update(item.id) {
                it.copy(
                    title = if (title != item.title) title else it.title,
                    details = if (details != item.details) details else it.details,
                    priority = if (priority != item.priority) priority else it.priority,
                    status = if (status != item.status) status else it.status,
                    tags = if (tags != item.tags) tags else it.tags,
                    blockedBy = if (blockedBy != item.blockedBy) blockedBy else it.blockedBy,
                    duplicateOf = if (duplicateOf != item.duplicateOf) duplicateOf else it.duplicateOf,
                    parent = if (parent != item.parent) parent else it.parent,
                )
            }
            current = null
            show(saved)
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        } catch (e: IllegalArgumentException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        }
    }

    internal fun setStatus(status: Status) {
        val item = current ?: return
        ItemActions.setStatus(project, item.id, status)
        show(store.find(item.id))
    }

    internal fun step(by: Int) {
        val item = current?.takeIf { it.anchors.isNotEmpty() } ?: return
        anchorIndex = Math.floorMod(anchorIndex + by, item.anchors.size)
        show(item)
    }

    /** Points the shown anchor at the selection. */
    private fun reattach() {
        val item = current ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null || !project.service<AnchorTracker>().reattach(item.id, editor, anchorIndex)) {
            Messages.showInfoMessage(project, "Select the code in the editor first.", "Not My TODO")
            return
        }
        show(store.find(item.id))
    }

    private fun addAnchor() {
        val item = current ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null || !project.service<AnchorTracker>().add(item.id, editor)) {
            Messages.showInfoMessage(project, "Open the file in the editor first, and select the code if it is only a part of it.", "Not My TODO")
            return
        }
        // Show the one just added; it is the last.
        anchorIndex = Int.MAX_VALUE
        show(store.find(item.id))
    }

    internal fun removeAnchor() {
        val item = current ?: return
        if (project.service<AnchorTracker>().remove(item.id, anchorIndex)) show(store.find(item.id))
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
