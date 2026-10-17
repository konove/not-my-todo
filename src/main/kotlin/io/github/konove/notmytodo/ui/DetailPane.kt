package io.github.konove.notmytodo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import io.github.konove.notmytodo.ide.FixCommits
import io.github.konove.notmytodo.ide.ItemActions
import io.github.konove.notmytodo.ide.StoreView
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.ide.ViewIds
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Effort
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.store.Store
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
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path
import java.util.concurrent.Future
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent

/** A filled button with a white icon, for the one action that is the next step. */
private class PrimaryIconButton(val caption: String, icon: Icon, action: () -> Unit) : JButton() {
    private val white = IconUtil.colorize(icon, Color.WHITE)
    private val disabled = IconLoader.getDisabledIcon(white)

    init {
        toolTipText = caption
        getAccessibleContext().accessibleName = caption
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
            g2.color = if (!isEnabled) JBColor.border() else if (model.isRollover || model.isPressed) fill.darker() else fill
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            val shown = if (isEnabled) white else disabled
            shown.paintIcon(this, g2, (width - shown.iconWidth) / 2, (height - shown.iconHeight) / 2)
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

/** The chips of an item in a line that goes on to the next when the column is narrow. */
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
    private val view get() = StoreView.getInstance(project)
    private var current: TodoItem? = null

    /** What was read of another project's checkout for the item shown: the checkout, and the text of the shown anchor's file. */
    internal data class Away(val checkout: Path?, val text: String?)

    /** Reads another project's checkout and one file of it, on a pooled thread. Replaced in tests. */
    internal var awayRead: (String, String?) -> Away = { key, path ->
        Away(view.checkout(key), path?.let { view.readText(key, it) })
    }

    /** The read for [awayFor], once done; [awayFor] is the item and anchor it was asked for. */
    private var away: Away? = null
    private var awayFor: Pair<TodoItem, Int>? = null

    /** The pooled read last asked for, so a test can wait for it to have posted its result. */
    internal var awayTask: Future<*>? = null

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
    /** The first entry is for an item nobody has sized. */
    internal val effortBox = ComboBox(arrayOf<Any>(NO_EFFORT, *Effort.entries.toTypedArray())).apply { toolTipText = "Effort" }
    private val statusBox = ComboBox(Status.entries.toTypedArray()).apply { toolTipText = "Status" }
    private val tagsField = JBTextField().apply { emptyText.text = "#tags" }
    private val blockedField = JBTextField().apply { emptyText.text = "Blocked by: T-1 infra/T-2" }
    private val duplicateField = JBTextField().apply { emptyText.text = "Duplicate of" }
    private val parentField = JBTextField().apply { emptyText.text = "Part of" }

    private val sourceField = JBTextField().apply { emptyText.text = "From: commit or run" }
    private val fixedInField = JBTextField().apply { emptyText.text = "Fixed in: commit" }
    private val resolutionField = JBTextField().apply { emptyText.text = "Resolution: what was done" }

    /** Source, fixed in, resolution. */
    internal val historyFields get() = listOf(sourceField, fixedInField, resolutionField)

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
    internal val metaStrip = MetaStrip()

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
    private var shownStrip: List<ChipSpec>? = null
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
    private var shownDetails: String? = null
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
    internal val codeText: String get() = codeArea.text
    private var shownCode: Triple<String, Int, Int>? = null
    private var shownTint: Color? = null

    /** Whether the shown item is in the store, and not a TODO comment found in the code. */
    private val tracked: Boolean get() = current?.let { !CodeTodos.isCode(it) } == true

    /** Whether the shown item is of this project: only its anchors are in this IDE project's files. */
    private val own: Boolean get() = current?.let { !ViewIds.isElsewhere(it.id) } == true

    /** The project of the shown item. */
    private val key: String get() = current?.let { ViewIds.split(it.id, view.home)?.project } ?: view.home

    /** Fix starts Claude Code in the item's checkout: an item of a project with none here cannot be fixed from here. */
    internal val fixEnabled: Boolean get() = fixButton.isEnabled
    internal val fixTooltip: String? get() = fixButton.toolTipText

    private fun status(vararg shown: Status): () -> Boolean = { tracked && current?.status in shown }

    private val fixButton = PrimaryIconButton("Fix with Claude", AllIcons.Actions.Lightning) {
        current?.let { if (tracked) ItemActions.fix(project, it.id) else ItemActions.fixComment(project, it) }
    }

    /** Moves the item shown to another project. A part moves only with the item it is a part of. */
    private inner class MoveAction : DumbAwareAction("Move to Project…", null, AllIcons.Actions.Forward) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) = present(e.presentation)

        /** Sets [p] to how the action stands for the item shown. */
        fun present(p: Presentation) {
            val part = current?.parent != null
            // Shown for a code comment too, so the toolbar keeps its place; there is nothing in the store to move.
            p.isVisible = current != null
            p.isEnabled = tracked && !part
            p.text = if (part) "Move the item it is a part of" else "Move to Project…"
        }

        override fun actionPerformed(e: AnActionEvent) = move()
    }

    private val moveAction = MoveAction()
    /** How Move to Project stands for the item shown: shown, enabled, and its text; for the tests. */
    internal val moveVisible: Boolean get() = Presentation().also(moveAction::present).isVisible
    internal val moveEnabled: Boolean get() = Presentation().also(moveAction::present).isEnabled
    internal val moveText: String get() = Presentation().also(moveAction::present).text

    private fun move() {
        val item = current ?: return
        val from = key
        // This project's entry is told apart by identity, not by its key: a pending folder may be keyed again while the popup is open.
        val here = view.home
        // Inbox is offered before it has a record: moving there makes it.
        val keys = (listOf(here, Store.INBOX) + view.keys()).distinct().filter { it != from }
        if (keys.isEmpty()) {
            Messages.showInfoMessage(project, "There is no other project to move ${item.id} to.", "Not My TODO")
            return
        }
        // The chooser dialog of Messages is deprecated; a popup answers through a callback.
        JBPopupFactory.getInstance().createPopupChooserBuilder(keys)
            .setTitle("Move ${item.id} to")
            .setItemChosenCallback { to -> moveTo(item, if (to === here) null else to) }
            .createPopup()
            .showInFocusCenter()
    }

    /**
     * Asks to confirm, then moves [item] to project [chosen] under a modal progress and says its new id in a balloon. Null
     * is this project, whose key is read when the move is written: a pending folder's may have changed since the popup opened.
     */
    internal fun moveTo(item: TodoItem, chosen: String?) {
        val to = chosen ?: view.home
        val links = if (!ViewIds.isElsewhere(item.id)) TodoService.getInstance(project).links() else view.links()
        val parts = links.children(item.id)
        val text = buildString {
            append("Move ${item.id} to $to? It gets a new id there.")
            if (parts.isNotEmpty()) append(" Its ${parts.size} ${if (parts.size == 1) "part moves" else "parts move"} with it.")
            if (item.anchors.isNotEmpty() || parts.any { it.anchors.isNotEmpty() }) append(" Its places in the code are dropped: the target project has its own checkout.")
        }
        val ok = if (parts.isEmpty()) "Move" else "Move All"
        if (Messages.showOkCancelDialog(project, text, "Move to Project", ok, "Cancel", null) != Messages.OK) return
        val moved = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, StoreException> { view.move(item.id, chosen ?: view.home, parts.isNotEmpty()) },
                "Moving ${item.id} to $to", false, project,
            )
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
            return
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
            return
        } catch (e: UncheckedIOException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
            return
        }
        // The key the item went to: making this project may have given it another than the one asked for.
        val into = ViewIds.split(moved, view.home)?.project ?: to
        NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
            .createNotification("Moved ${item.id} to $into as $moved.", NotificationType.INFORMATION).notify(project)
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
            IconAction("Re-attach to Selection", AllIcons.General.Locate, { tracked && own }) { reattach() },
            IconAction("Add an Anchor: the Selection, or the Whole File", AllIcons.General.Add, { tracked && own }) { addAnchor() },
            IconAction("Remove This Anchor", AllIcons.General.Remove, { tracked && own && shownAnchor != null }) { removeAnchor() },
            Separator.getInstance(),
            DecisionAction(),
            EditAction(),
            moveAction,
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
            cell(effortBox)
            cell(statusBox)
            cell(tagsField).align(AlignX.FILL).resizableColumn()
        }
        val linksEdit = row {
            cell(blockedField).align(AlignX.FILL).resizableColumn()
            cell(duplicateField)
            cell(parentField)
        }
        val historyEdit = row {
            cell(sourceField)
            cell(fixedInField)
            cell(resolutionField).align(AlignX.FILL).resizableColumn()
        }
        viewDetailsRow = row { cell(viewDetailsScroll).align(Align.FILL) }.resizableRow()
        val detailsEdit = row { cell(JBScrollPane(detailsArea)).align(Align.FILL) }.resizableRow()
        viewRows = listOf(title, meta)
        editRows = listOf(titleEdit, factsEdit, linksEdit, historyEdit, detailsEdit)
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
                val saved = view.update(item.id) {
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
        val item = current?.let { if (own) project.service<FixCommits>().shown(it) else it }
        viewRows.forEach { it.visible(!editing) }
        editRows.forEach { it.visible(editing) }
        viewLinksRow.visible(!editing && viewLinks.iterator().hasNext())
        viewDetailsRow.visible(!editing && item != null && (item.details.isNotBlank() || item.comments.isNotEmpty() || item.source != null || item.fixedIn != null || item.resolution != null || item.movedFrom != null))
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
        if (shown == null || effortBox.selectedItem as? Effort == shown.effort) effortBox.selectedItem = item.effort ?: NO_EFFORT
        if (shown == null || statusBox.selectedItem == shown.status) statusBox.selectedItem = item.status
        if (shown == null || Tags.parseList(tagsField.text) == shown.tags) {
            tagsField.text = item.tags.joinToString(" ") { "#$it" }
        }
        if (shown == null || blockedField.text == shown.blockedBy.joinToString(" ")) blockedField.text = item.blockedBy.joinToString(" ")
        if (shown == null || duplicateField.text == shown.duplicateOf.orEmpty()) duplicateField.text = item.duplicateOf.orEmpty()
        if (shown == null || parentField.text == shown.parent.orEmpty()) parentField.text = item.parent.orEmpty()
        if (shown == null || sourceField.text == oneLine(shown.source)) sourceField.text = oneLine(item.source)
        if (shown == null || fixedInField.text == oneLine(shown.fixedIn)) fixedInField.text = oneLine(item.fixedIn)
        if (shown == null || resolutionField.text == oneLine(shown.resolution)) resolutionField.text = oneLine(item.resolution)
        val code = CodeTodos.isCode(item)
        val links = if (code) Links.NONE else if (ViewIds.isElsewhere(item.id)) view.links() else TodoService.getInstance(project).links()
        showLinks(item, links)
        // A parent's priority is that of its open parts, so there is nothing to choose while it has any.
        val fromParts = links.hasOpenParts(item)
        val priority = links.priority(item)
        priorityBox.isEnabled = !fromParts
        priorityBox.toolTipText = if (fromParts) "Priority: ${priority.name}, the highest among the open parts" else "Priority"
        val anchor = item.anchors.getOrNull(anchorIndex)
        viewTitle.text = item.title
        // The panel refreshes often; leave the text and the scroll position alone when nothing changed.
        val fixes = project.service<FixCommits>()
        // The fixing commit is looked for in this checkout's git, which knows nothing of another project's commits.
        val text = ItemText.html(if (own) fixes.shown(item) else item, grey = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()))
        // The commit that fixed it is looked up in the background and shown when it is found.
        if (own && fixes.derives(item)) fixes.refreshLater { if (current === item) show(item) }
        if (text != shownDetails) {
            shownDetails = text
            viewDetails.text = text
            viewDetails.caretPosition = 0
        }
        val strip = if (code) listOf(Chips.codeComment) else Chips.strip(item, links.isBlocked(item), priority)
        if (strip != shownStrip) {
            shownStrip = strip
            metaStrip.removeAll()
            strip.forEach { metaStrip.add(Chip(it)) }
        }
        whereLabel.text = anchor?.let { where(it, item.anchors.size) }.orEmpty()
        whereLabel.toolTipText = anchor?.path
        anchorSteps.isVisible = item.anchors.size > 1
        fixButton.isVisible = code || item.status == Status.OPEN || item.status == Status.IN_PROGRESS
        // Another project's checkout is looked at on a pooled thread; the pane is filled in again when it has been.
        // A read already asked for this item and anchor is waited for, not asked again: the pane is shown often.
        val asked = !code && !own && awayFor?.let { it.first == item && it.second == anchorIndex } == true
        if (!code && !own && !asked) readAway(item, anchorIndex, anchor?.takeUnless { it.lost }?.path)
        val read = away
        val here = code || own || read?.checkout != null
        fixButton.isEnabled = here
        fixButton.toolTipText = when {
            here -> fixButton.caption
            read == null -> "Looking for the checkout of $key"
            else -> "$key has no checkout on this machine, so there is no folder to start Claude Code in"
        }
        codeBox.isVisible = anchor != null
        if (anchor != null) showCode(anchor, PriorityColors.tint(priority), if (own || code) null else read?.text, own || code)
        deleteToolbar.updateActionsAsync()
        showMode()
    }

    /** Asks for the checkout of [key] and the file at [path] on a pooled thread, and shows [item] again when they are read. */
    private fun readAway(item: TodoItem, index: Int, path: String?) {
        val asked = item to index
        // The panel shows equal copies of an item often. A read for the same item and anchor as the one shown
        // keeps its result on show until the new one lands, so the pane does not flicker.
        if (awayFor?.let { it.first.id == item.id && it.second == index } != true) away = null
        awayFor = asked
        val key = this.key
        awayTask = ApplicationManager.getApplication().executeOnPooledThread {
            val got = runCatching { awayRead(key, path) }.getOrElse { Away(null, null) }
            ApplicationManager.getApplication().invokeLater({
                // The user may have chosen another item in the meantime. The read is kept for the item it was asked for even so:
                // coming back to it asks for no other read, and would otherwise wait for this one for ever.
                if (awayFor === asked) {
                    away = got
                    if (current == item) show(item)
                }
            }, project.disposed)
        }
    }

    /** Fills [viewLinks]: what the item waits for, duplicates and is a part of, and the parts it has. A closed item's id is struck out. */
    private fun showLinks(item: TodoItem, links: Links) {
        val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES
        viewLinks.clear()
        fun ids(label: String, ids: List<String>) {
            if (ids.isEmpty()) return
            viewLinks.append((if (viewLinks.iterator().hasNext()) "  ·  " else "") + label, grey)
            ids.forEach { id ->
                val closed = view.find(id)?.isClosed != false
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
     * whole file shows how the file starts, with nothing tinted. This project's file is read here, from the
     * IDE's documents; another project's arrives as [awayText], read off the event thread.
     */
    private fun showCode(anchor: Anchor, tint: Color, awayText: String?, local: Boolean) {
        val fileLines = if (anchor.lost) null else (if (local) view.readText(key, anchor.path) else awayText)?.lines()
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

    /** [text] as a one-line field shows it, to tell whether I changed what the field was given. */
    private fun oneLine(text: String?): String = text.orEmpty().replace('\n', ' ')

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
            val effort = effortBox.selectedItem as? Effort
            val status = statusBox.selectedItem as Status
            val tags = Tags.parseList(tagsField.text)
            val blockedBy = ItemRef.parseList(blockedField.text).map { it.toString() }
            val duplicateOf = ItemRef.parseList(duplicateField.text).also { require(it.size < 2) { "an item is a duplicate of one item" } }.firstOrNull()?.toString()
            val parent = ItemRef.parseList(parentField.text).also { require(it.size < 2) { "an item is a part of one item" } }.firstOrNull()?.toString()
            val source = sourceField.text
            val fixedIn = fixedInField.text
            val resolution = resolutionField.text
            // Only the fields I edited are written; the rest keep whatever is in the file now.
            val home = view.home
            val saved = view.update(item.id) {
                it.copy(
                    title = if (title != item.title) title else it.title,
                    details = if (details != item.details) details else it.details,
                    priority = if (priority != item.priority) priority else it.priority,
                    effort = if (effort != item.effort) effort else it.effort,
                    status = if (status != item.status) status else it.status,
                    tags = if (tags != item.tags) tags else it.tags,
                    blockedBy = if (blockedBy != item.blockedBy) blockedBy.map { ViewIds.stored(it, home) } else it.blockedBy,
                    duplicateOf = if (duplicateOf != item.duplicateOf) duplicateOf?.let { ViewIds.stored(it, home) } else it.duplicateOf,
                    parent = if (parent != item.parent) parent?.let { ViewIds.stored(it, home) } else it.parent,
                    source = if (source != oneLine(item.source)) source else it.source,
                    fixedIn = if (fixedIn != oneLine(item.fixedIn)) fixedIn else it.fixedIn,
                    resolution = if (resolution != oneLine(item.resolution)) resolution else it.resolution,
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
        show(view.find(item.id))
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
        show(view.find(item.id))
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
        show(view.find(item.id))
    }

    internal fun removeAnchor() {
        val item = current ?: return
        if (project.service<AnchorTracker>().remove(item.id, anchorIndex)) show(view.find(item.id))
    }

    private fun delete() {
        val item = current ?: return
        val answer = Messages.showYesNoDialog(project, "Delete ${item.id}: ${item.title}?", "Not My TODO", null)
        if (answer != Messages.YES) return
        try {
            view.delete(item.id)
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        }
    }

    private companion object {
        const val CONTEXT_LINES = 15
        const val NO_EFFORT = "No effort"
    }
}
