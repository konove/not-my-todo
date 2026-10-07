package io.github.konove.notmytodo.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.scopeChooser.ScopeChooserCombo
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RowIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.konove.notmytodo.ide.CodeTodos
import io.github.konove.notmytodo.ide.EditorAnchors
import io.github.konove.notmytodo.ide.ItemActions
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.settings.TodoSettingsListener
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.accessibility.AccessibleContext
import javax.swing.DefaultListSelectionModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/** An icon button for a toolbar; shown only while [visible] holds. */
internal class IconAction(
    text: String, icon: Icon, private val visible: () -> Boolean = { true }, private val run: () -> Unit,
) : DumbAwareAction(text, null, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = visible()
    }

    override fun actionPerformed(e: AnActionEvent) = run()
}

/** The tool window, left to right: the action rail, scopes and tags, the item table, and the detail pane. */
class TodoPanel(
    private val project: Project,
    /** Whether the IDE can list code comments; a test passes true where it cannot. */
    private val codeSupported: Boolean = CodeTodos.isAvailable(),
    /** Told the counts, such as "6 open · 1 to review", whenever they change. */
    private val onSummary: (String) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    private val service = TodoService.getInstance(project)
    private val store = service.store
    private val navModel = CollectionListModel<Nav>()
    private val nav = JBList(navModel)
    private val model = CollectionListModel<Any>()
    private val table = JBList(model)
    private val search = SearchTextField(false)
    private val navSearch = SearchTextField(false)
    private var tagsCollapsed = false
    private val errorLabel = JBLabel()
    private val cards = CardLayout()
    private val body = JPanel(cards)
    internal val detail = DetailPane(project)
    private var scope = Scope.ALL
    private var tag: String? = null
    private val codeAvailable: Boolean
        get() = codeSupported && TodoSettings.getInstance().values.codeComments

    /** The labels of the left list, top to bottom. */
    internal val navLabels: List<String> get() = navModel.items.map { it.label }
    private var codeMode = false
    private var codeTodos: List<TodoItem> = emptyList()
    private var listed: List<TodoItem> = emptyList()
    private var showClosed = false
    private val priorities = LinkedHashSet<Priority>()
    private val statuses = LinkedHashSet<Status>()
    private val authors = LinkedHashSet<Author>()
    private var sort = ItemSort.PRIORITY
    private var groupBy = GroupBy.NONE
    private val collapsed = HashSet<String>()
    private val fileScope = ScopeChooserCombo(project, false, false, PropertiesComponent.getInstance(project).getValue(SCOPE_KEY, "Project Files"))
    private var refreshing = false
    private val storeListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
    }

    private val selected: TodoItem? get() = (table.selectedValue as? ItemRow)?.item
    private val shownItems: List<TodoItem> get() = model.items.filterIsInstance<ItemRow>().map { it.item }

    init {
        detail.onOpen = ::select
        nav.cellRenderer = NavRenderer()
        nav.fixedCellHeight = JBUI.scale(ROW_HEIGHT)
        // The Tags heading folds on a click but is never selected; the arrow keys step over it.
        nav.selectionModel = object : DefaultListSelectionModel() {
            override fun setSelectionInterval(from: Int, to: Int) {
                var index = to
                if (navModel.items.getOrNull(index)?.header == true) index += if (index > leadSelectionIndex) 1 else -1
                if (index in 0 until navModel.size && !navModel.getElementAt(index).header) super.setSelectionInterval(index, index)
            }
        }
        nav.selectionMode = ListSelectionModel.SINGLE_SELECTION
        nav.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = nav.locationToIndex(e.point)
                if (index < 0 || !nav.getCellBounds(index, index).contains(e.point)) return
                if (!navModel.getElementAt(index).header) return
                tagsCollapsed = !tagsCollapsed
                refresh()
            }
        })
        nav.addListSelectionListener {
            val picked = nav.selectedValue
            if (!it.valueIsAdjusting && !refreshing && picked != null) {
                scope = picked.scope ?: Scope.ALL
                tag = picked.tag
                codeMode = picked.code
                refresh()
                if (codeMode) rescan()
            }
        }

        table.cellRenderer = RowRenderer()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = table.locationToIndex(e.point)
                if (index < 0 || !table.getCellBounds(index, index).contains(e.point)) return
                val header = model.getElementAt(index) as? GroupHeader ?: return
                if (!collapsed.remove(header.key)) collapsed += header.key
                refresh()
            }
        })
        table.fixedCellHeight = JBUI.scale(ROW_HEIGHT)
        table.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.emptyText.text = "No items. Select code and choose Add TODO."
        table.addListSelectionListener { if (!it.valueIsAdjusting && !refreshing) detail.show(selected) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                selected?.let { ItemActions.navigate(project, it) }
                return true
            }
        }.installOn(table)
        table.registerKeyboardAction(
            { selected?.let { ItemActions.navigate(project, it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
        search.textEditor.emptyText.text = "Words, T-12, #tag, !p1"
        navSearch.textEditor.emptyText.text = "Scope or tag"
        listOf(search, navSearch).forEach {
            it.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = refresh()
            })
        }

        val rail = ActionManager.getInstance().createActionToolbar("NotMyTodoRail", railActions(), false)
        rail.targetComponent = this
        rail.component.border = JBUI.Borders.compound(
            JBUI.Borders.customLineRight(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
            JBUI.Borders.empty(4, 6),
        )
        Disposer.register(this, fileScope)
        fileScope.toolTipText = "Files to show items and code comments from"
        fileScope.comboBox.addActionListener {
            fileScope.selectedScopeName?.let { PropertiesComponent.getInstance(project).setValue(SCOPE_KEY, it) }
            refresh()
            rescan()
        }
        val filters = ActionManager.getInstance().createActionToolbar("NotMyTodoFilters", filterActions(), true)
        filters.targetComponent = this
        val views = ActionManager.getInstance().createActionToolbar("NotMyTodoFilters", viewActions(), true)
        views.targetComponent = this
        val listTop = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(search, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(filters.component)
                add(fileScope)
                add(views.component)
            }, BorderLayout.EAST)
        }
        val navPane = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 4)
                add(navSearch, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(nav, true), BorderLayout.CENTER)
        }
        val listPane = JPanel(BorderLayout()).apply {
            add(listTop, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
        }
        val left = OnePixelSplitter(false, "NotMyTodo.navSplit", 0.2f).apply {
            firstComponent = navPane
            secondComponent = listPane
        }
        val splitter = OnePixelSplitter(false, "NotMyTodo.detailSplit", 0.7f).apply {
            firstComponent = left
            secondComponent = detail
        }
        val listCard = JPanel(BorderLayout()).apply {
            add(rail.component, BorderLayout.WEST)
            add(splitter, BorderLayout.CENTER)
        }
        val errorCard = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(errorLabel)
            add(ActionLink("Open the Items File") { openDataFile() })
        }
        body.add(listCard, LIST_CARD)
        body.add(errorCard, ERROR_CARD)
        add(body, BorderLayout.CENTER)

        store.addListener(storeListener)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    refresh()
                    rescan()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TodoSettingsListener.TOPIC,
            TodoSettingsListener {
                ApplicationManager.getApplication().invokeLater({
                    if (!codeAvailable) {
                        codeMode = false
                        codeTodos = emptyList()
                    }
                    refresh()
                    rescan()
                }, project.disposed)
            },
        )
        refresh()
        rescan()
    }

    private fun railActions() = DefaultActionGroup(
        IconAction("New Note", AllIcons.General.Add) { CaptureDialog(project, null).show() },
        IconAction("Add TODO for the Selection", AllIcons.General.Locate) { addItem() },
        Separator.getInstance(),
        ShowClosedAction(),
        IconAction("Reload", AllIcons.Actions.Refresh) {
            store.reload()
            refresh()
            rescan()
        },
        IconAction("Open the Items File", AllIcons.FileTypes.Json) { openDataFile() },
    )

    private fun filterActions() = DefaultActionGroup(
        FilterGroup("Priority", Priority.entries, priorities) { it.name },
        FilterGroup("Status", Status.entries, statuses) { it.label },
        FilterGroup("Author", Author.entries, authors) { it.name.lowercase().replaceFirstChar(Char::uppercase) },
    )

    private fun viewActions() = DefaultActionGroup(
        SortGroup(),
        GroupByGroup(),
        IconAction("Expand All", AllIcons.Actions.Expandall, { groupBy != GroupBy.NONE }) {
            collapsed.clear()
            refresh()
        },
        IconAction("Collapse All", AllIcons.Actions.Collapseall, { groupBy != GroupBy.NONE }) {
            collapsed += ItemGroups.allKeys(listed, groupBy)
            refresh()
        },
        IconAction("Fix All Shown with Claude", AllIcons.Actions.Lightning) { ItemActions.fixAll(project, listed) },
    )

    /** Whether [item]'s file is in the scope picked in the toolbar. Notes and files that are gone always are. */
    private fun inFileScope(item: TodoItem): Boolean {
        val files = item.anchors.mapNotNull { service.findFile(it.path) }
        return files.isEmpty() || files.any { CodeTodos.inScope(fileScope.selectedScope, it) }
    }

    private fun addItem() {
        val anchor = FileEditorManager.getInstance(project).selectedTextEditor
            ?.let { EditorAnchors.fromSelection(project, it) }
        if (anchor == null) {
            Messages.showInfoMessage(project, "Select the code in the editor first.", "Not My TODO")
            return
        }
        CaptureDialog(project, anchor).show()
    }

    /** Finds the TODO comments again, in the background, and shows them when done. */
    private fun rescan() {
        if (!codeAvailable) return
        val scope = fileScope.selectedScope
        ReadAction.nonBlocking<List<TodoItem>> { CodeTodos.scan(project, scope) }
            .inSmartMode(project)
            .coalesceBy(this)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) {
                codeTodos = it
                refresh()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    fun select(id: String) {
        if (shownItems.none { it.id == id }) {
            collapsed.clear()
            search.text = ""
            scope = Scope.ALL
            tag = null
            codeMode = false
            showClosed = true
            refresh()
        }
        val index = model.items.indexOfFirst { (it as? ItemRow)?.item?.id == id }
        if (index >= 0) {
            table.selectedIndex = index
            table.ensureIndexIsVisible(index)
        }
    }

    private fun refresh() {
        val error = store.error
        if (error != null) {
            errorLabel.text = "<html>${store.file.fileName} cannot be read:<br>$error</html>"
            cards.show(body, ERROR_CARD)
            return
        }
        cards.show(body, LIST_CARD)
        val selectedHeader = (table.selectedValue as? GroupHeader)?.key
        val selectedId = selected?.id ?: detail.currentId
        val currentPath = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            ?.let { service.relativePath(it) }
        // Asking a scope about a file needs a read action, which the event thread no longer holds by itself.
        val all = runReadActionBlocking { store.items.filter(::inFileScope) }
        // Replacing the models clears the selections for a moment; nothing may react to that.
        refreshing = true
        try {
            val shown = ItemFilter.apply(all, ItemQuery(showClosed = showClosed))
            val tags = shown.flatMap { it.tags }.groupingBy { it }.eachCount().toSortedMap()
            if (tag?.let { it in tags } != true) tag = null
            val wanted = navSearch.text.trim().lowercase()
            val scopeRows = Scope.entries.map { s ->
                Nav(s, null, ItemFilter.apply(all, ItemQuery(scope = s, currentPath = currentPath, showClosed = showClosed)).size)
            } + listOfNotNull(Nav(null, null, codeTodos.size).takeIf { codeAvailable })
            val tagRows = tags.map { (name, count) -> Nav(null, name, count) }.filter { wanted in it.label.lowercase() }
            navModel.replaceAll(
                scopeRows.filter { wanted in it.label.lowercase() } +
                    (if (tagRows.isEmpty()) emptyList() else listOf(Nav(null, null, tagRows.size, header = true, collapsed = tagsCollapsed && wanted.isEmpty()))) +
                    // A search shows the tags it finds even when the section is folded.
                    (if (tagsCollapsed && wanted.isEmpty()) emptyList() else tagRows)
            )
            nav.selectedIndex = navModel.items.indexOfFirst {
                !it.header && (if (codeMode) it.code else if (tag != null) it.tag == tag else it.scope == scope)
            }

            listed = if (codeMode) CodeTodos.filter(codeTodos, search.text)
            else ItemFilter.apply(
                all, ItemQuery(search.text, scope, currentPath, showClosed, tag, priorities, statuses, authors, sort),
            )
            model.replaceAll(ItemGroups.rows(listed, groupBy, collapsed, Links(store.items)))
            val index = model.items.indexOfFirst {
                if (selectedHeader != null) (it as? GroupHeader)?.key == selectedHeader
                else (it as? ItemRow)?.item?.id == selectedId
            }
            if (index >= 0) table.selectedIndex = index else table.clearSelection()
        } finally {
            refreshing = false
        }
        val review = all.count { it.status == Status.FIXED }
        onSummary("${all.count { !it.isClosed }} open" + if (review > 0) " · $review to review" else "")
        detail.show(selected)
    }

    private fun openDataFile() {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(store.file) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    override fun dispose() {
        store.removeListener(storeListener)
    }

    private inner class ShowClosedAction : ToggleAction("Show Closed", null, AllIcons.Actions.Show), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = showClosed

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showClosed = state
            refresh()
        }
    }

    /** A toolbar dropdown of checkboxes; with none ticked every value passes. */
    private inner class FilterGroup<T>(
        private val title: String, values: List<T>, private val picked: MutableSet<T>, private val label: (T) -> String,
    ) : DefaultActionGroup(title, true), DumbAware {
        init {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            values.forEach { add(Pick(it)) }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = if (picked.isEmpty()) title else "$title: ${picked.joinToString(", ", transform = label)}"
            e.presentation.isEnabled = !codeMode
        }

        private inner class Pick(private val value: T) : ToggleAction(label(value)), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
            }

            override fun isSelected(e: AnActionEvent): Boolean = value in picked

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) picked += value else picked -= value
                refresh()
            }
        }
    }

    private inner class SortGroup : DefaultActionGroup("Sort By", true), DumbAware {
        init {
            templatePresentation.icon = AllIcons.ObjectBrowser.Sorted
            ItemSort.entries.forEach { add(By(it)) }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !codeMode
        }

        private inner class By(private val value: ItemSort) : ToggleAction(value.label), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun isSelected(e: AnActionEvent): Boolean = sort == value

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                sort = value
                refresh()
            }
        }
    }

    private inner class GroupByGroup : DefaultActionGroup("Group By", true), DumbAware {
        init {
            templatePresentation.icon = AllIcons.Actions.GroupBy
            GroupBy.entries.forEach { add(By(it)) }
        }

        private inner class By(private val value: GroupBy) : ToggleAction(value.label), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun isSelected(e: AnActionEvent): Boolean = groupBy == value

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                groupBy = value
                collapsed.clear()
                refresh()
            }
        }
    }

    /**
     * A row of the left list: one of the scopes, a tag when [tag] is set, the heading over the
     * tags when [header] is set, or else the TODO comments in the code.
     */
    private data class Nav(
        val scope: Scope?, val tag: String?, val count: Int, val header: Boolean = false, val collapsed: Boolean = false,
    ) {
        val code: Boolean get() = scope == null && tag == null && !header
        val label: String get() = scope?.label ?: tag?.let { "#$it" } ?: if (header) "Tags" else "Code comments"
    }

    /** A row that paints its selection as a rounded bar set in from the edges of the list. */
    private class RoundedRow(private val content: JComponent) : JPanel(BorderLayout()) {
        var selectionColor: Color? = null

        init {
            add(content, BorderLayout.CENTER)
        }

        override fun getAccessibleContext(): AccessibleContext = content.accessibleContext

        override fun paintComponent(g: Graphics) {
            g.color = background
            g.fillRect(0, 0, width, height)
            val color = selectionColor ?: return
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                g2.color = color
                val inset = JBUI.scale(6)
                val arc = JBUI.scale(8)
                g2.fillRoundRect(inset, 0, width - 2 * inset, height, arc, arc)
            } finally {
                g2.dispose()
            }
        }
    }

    /** Draws a row with the rounded selection of the IDE's own lists. */
    private abstract class RoundedRenderer<T>(content: JComponent) : ListCellRenderer<T> {
        private val row = RoundedRow(content)

        abstract fun customize(value: T, selected: Boolean)

        open fun depth(value: T): Int = 0

        override fun getListCellRendererComponent(
            list: JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean,
        ): Component = component(list, value, selected, depth(value))

        fun component(list: JList<*>, value: T, selected: Boolean, depth: Int = 0): Component {
            customize(value, selected)
            row.border = JBUI.Borders.empty(0, 14 + depth * 18, 0, 14)
            row.background = list.background
            row.selectionColor = if (selected) UIUtil.getListSelectionBackground(list.hasFocus()) else null
            return row
        }
    }

    /** The label on the left and the count at the right edge. */
    private class NavCells : JPanel(BorderLayout()) {
        val label = cell().also { add(it, BorderLayout.CENTER) }
        val count = cell().also { add(it, BorderLayout.EAST) }

        init {
            isOpaque = false
        }
    }

    private class NavRenderer(private val cells: NavCells = NavCells()) : RoundedRenderer<Nav>(cells) {
        override fun depth(value: Nav): Int = if (value.tag != null) 1 else 0

        override fun customize(value: Nav, selected: Boolean) {
            cells.label.clear()
            cells.count.clear()
            cells.label.icon = when {
                value.header -> if (value.collapsed) AllIcons.General.ChevronRight else AllIcons.General.ChevronDown
                value.tag != null -> null
                value.code -> AllIcons.General.TodoDefault
                value.scope == Scope.ALL -> AllIcons.Actions.ListFiles
                value.scope == Scope.THIS_FILE -> AllIcons.FileTypes.Any_type
                value.scope == Scope.NOTES -> AllIcons.FileTypes.Text
                value.scope == Scope.AGENT_READY -> AllIcons.Actions.Lightning
                value.scope == Scope.NEEDS_DECISION -> AllIcons.General.User
                else -> AllIcons.General.Warning
            }
            cells.label.append(value.label, if (value.header) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (!value.header) cells.count.append(value.count.toString(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /** The cells of an item row: every column but the title has a fixed width, and the title takes what is left. */
    private class Cells : JPanel(null) {
        val all = COLUMN_WIDTHS.map { cell().also(::add) }.also { it.last().setTextAlign(SwingConstants.RIGHT) }

        init {
            isOpaque = false
        }

        override fun doLayout() {
            val widths = COLUMN_WIDTHS.map { JBUI.scale(it) }
            val title = maxOf(width - widths.sum(), JBUI.scale(120))
            var x = 0
            all.forEachIndexed { i, cell ->
                val w = if (widths[i] == 0) title else widths[i]
                cell.setBounds(x, 0, w, height)
                x += w
            }
        }
    }

    /** A row of the item list is a group heading or an item. */
    private class RowRenderer : ListCellRenderer<Any> {
        private val items = ItemRenderer()
        private val headers = HeaderRenderer()

        override fun getListCellRendererComponent(
            list: JList<out Any>, value: Any, index: Int, selected: Boolean, hasFocus: Boolean,
        ): Component =
            if (value is GroupHeader) headers.component(list, value, selected, value.depth)
            else (value as ItemRow).let { items.component(list, it, selected, it.depth) }
    }

    private class HeaderRenderer(private val cell: SimpleColoredComponent = cell()) : RoundedRenderer<GroupHeader>(cell) {
        override fun customize(value: GroupHeader, selected: Boolean) {
            cell.clear()
            val chevron = if (value.collapsed) AllIcons.General.ChevronRight else AllIcons.General.ChevronDown
            cell.icon = when (value.kind) {
                GroupKind.DIRECTORY -> RowIcon(chevron, AllIcons.Nodes.Folder)
                GroupKind.FILE -> RowIcon(chevron, FileTypeManager.getInstance().getFileTypeByFileName(value.label).icon ?: AllIcons.FileTypes.Any_type)
                GroupKind.OTHER -> chevron
            }
            cell.append(value.label)
            cell.append("  ${value.count} ${if (value.count == 1) "item" else "items"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private class ItemRenderer(private val cells: Cells = Cells()) : RoundedRenderer<ItemRow>(cells) {
        override fun customize(row: ItemRow, selected: Boolean) {
            val value = row.item
            val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES
            val code = CodeTodos.isCode(value)
            val (mark, title, tags, place, status) = cells.all
            val id = cells.all[5]
            val updated = cells.all[6]
            cells.all.forEach { it.clear() }
            mark.icon = if (code) AllIcons.General.TodoDefault else PriorityColors.icon(value.priority)
            title.icon = when {
                value.needsDecision -> AllIcons.General.User
                row.blocked -> AllIcons.Nodes.Padlock
                else -> null
            }
            title.append(
                value.title,
                when {
                    value.isClosed -> SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null)
                    selected -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                },
            )
            value.effort?.let { title.append("  ${it.name}", grey) }
            if (row.parts > 0) title.append("  ${row.partsClosed}/${row.parts} closed", grey)
            tags.append(value.tags.joinToString(" ") { "#$it" }, grey)
            val anchor = value.anchor
            when {
                anchor == null -> place.append("Note", grey)
                value.anyLost -> place.append("Anchor lost", SimpleTextAttributes.ERROR_ATTRIBUTES)
                else -> place.append(placeText(anchor, value.anchors.size - 1), grey)
            }
            if (!code) {
                status.append(value.status.label, StatusColors.attributes(value.status))
                id.append(value.id, grey)
                updated.append(updatedText(value.updated), grey)
            }
        }
    }

    private companion object {
        const val LIST_CARD = "list"
        const val ERROR_CARD = "error"
        const val ROW_HEIGHT = 28
        const val SCOPE_KEY = "NotMyTodo.fileScope"

        fun cell() = SimpleColoredComponent().apply { isOpaque = false }

        /** Priority, title, tags, place, status, ID, updated. The title takes what is left. */
        val COLUMN_WIDTHS = listOf(28, 0, 140, 240, 110, 50, 130)

        /** The file with its directory, the lines, and how many [more] anchors the item has: `ui/TodoPanel.kt:124–129 +2`. */
        fun placeText(anchor: Anchor, more: Int = 0): String {
            val file = anchor.path.split('/').takeLast(2).joinToString("/")
            return (if (anchor.isFile) file else "$file:${anchor.linesText("–")}") + if (more > 0) " +$more" else ""
        }

        fun updatedText(timestamp: String): String =
            runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(timestamp).toEpochMilli()) }.getOrDefault("")
    }
}
