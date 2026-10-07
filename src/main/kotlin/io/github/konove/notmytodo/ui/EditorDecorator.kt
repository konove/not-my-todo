package io.github.konove.notmytodo.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.settings.TodoSettingsListener
import javax.swing.Icon

class TodoGutterRenderer(private val project: Project, val item: TodoItem) : GutterIconRenderer() {
    override fun getIcon(): Icon = PriorityColors.icon(item.priority)
    override fun getTooltipText(): String = "${item.id}: ${item.title}"
    override fun getAccessibleName(): String = "TODO ${item.id}: ${item.title}"
    override fun isNavigateAction(): Boolean = true
    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = ItemPopup.show(project, item.id, e)
    }

    override fun equals(other: Any?): Boolean = other is TodoGutterRenderer && other.item == item
    override fun hashCode(): Int = item.hashCode()
}

/** Draws a gutter mark, a tinted background and a mark on the scrollbar for the anchored lines of every open item in an open file. */
@Service(Service.Level.PROJECT)
class EditorDecorator(private val project: Project) : Disposable {
    private val service get() = TodoService.getInstance(project)
    private val highlighters = ArrayList<RangeHighlighter>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        service.store.addListener {
            ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed)
        }
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refresh()
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TodoSettingsListener.TOPIC,
            TodoSettingsListener { ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) },
        )
        refresh()
    }

    fun refresh() {
        if (project.isDisposed) return
        clear()
        if (!TodoSettings.getInstance().values.editorMarks) return
        val items = service.store.items.filter { item -> !item.isClosed && item.anchors.any(::marked) }
        if (items.isEmpty()) return
        for (file in FileEditorManager.getInstance(project).openFiles) {
            val path = service.relativePath(file) ?: continue
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: continue
            val markup = DocumentMarkupModel.forDocument(document, project, true)
            for ((item, anchor) in items.flatMap { item -> item.anchors.filter(::marked).map { item to it } }) {
                if (anchor.path != path || anchor.endLine > document.lineCount) continue
                val highlighter = markup.addRangeHighlighter(
                    document.getLineStartOffset(anchor.startLine - 1),
                    document.getLineEndOffset(anchor.endLine - 1),
                    HighlighterLayer.SELECTION - 1,
                    TextAttributes().apply { backgroundColor = PriorityColors.tint(item.priority) },
                    HighlighterTargetArea.LINES_IN_RANGE,
                )
                highlighter.gutterIconRenderer = TodoGutterRenderer(project, item)
                highlighter.setErrorStripeMarkColor(PriorityColors.solid(item.priority))
                highlighter.errorStripeTooltip = "${item.id}: ${item.title}"
                highlighters += highlighter
            }
        }
    }

    /** An anchor on a whole file has no lines to mark. */
    private fun marked(anchor: Anchor): Boolean = !anchor.lost && !anchor.isFile

    fun decoratedIds(document: Document): List<String> =
        highlighters.filter { it.isValid && it.document === document }
            .mapNotNull { (it.gutterIconRenderer as? TodoGutterRenderer)?.item?.id }

    private fun clear() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    override fun dispose() = clear()
}
