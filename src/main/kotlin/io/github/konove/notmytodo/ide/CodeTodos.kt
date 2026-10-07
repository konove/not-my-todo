package io.github.konove.notmytodo.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.psi.search.SearchScope
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.TodoItem

/**
 * The TODO comments the IDE finds in the project's sources, shaped as items so the panel can
 * list them. They are not in the store: their id is `path:line`, never `T-n`.
 */
object CodeTodos {
    private const val TODO_PLUGIN = "intellij.todo.plugin"

    fun isCode(item: TodoItem): Boolean = ItemId.parse(item.id) == null

    /** False when the IDE's bundled TODO Comments plugin is disabled; then there is nothing to list. */
    fun isAvailable(): Boolean = Plugins.isEnabled(TODO_PLUGIN)

    /** Call in a read action in smart mode, off the event thread. */
    fun scan(project: Project, scope: SearchScope?): List<TodoItem> {
        if (!isAvailable()) return emptyList()
        val service = TodoService.getInstance(project)
        val helper = PsiTodoSearchHelper.getInstance(project)
        val index = ProjectFileIndex.getInstance(project)
        val documents = PsiDocumentManager.getInstance(project)
        val result = ArrayList<TodoItem>()
        helper.processFilesWithTodoItems { file ->
            val virtualFile = file.virtualFile
            val path = virtualFile?.takeIf { index.isInContent(it) && !underExcluded(index, it) && inScope(scope, it) }?.let { service.relativePath(it) }
            val document = if (path == null) null else documents.getDocument(file)
            if (path != null && document != null) {
                for (todo in helper.findTodoItems(file)) {
                    val range = todo.textRange
                    if (range.endOffset > document.textLength) continue
                    val start = document.getLineNumber(range.startOffset)
                    val end = document.getLineNumber(range.endOffset)
                    val lines = document.getText(TextRange(document.getLineStartOffset(start), document.getLineEndOffset(end)))
                    result += TodoItem(
                        id = "$path:${start + 1}",
                        title = document.getText(range).lines().joinToString(" ") { it.trim() },
                        priority = Priority.P3,
                        anchor = Anchor(path, start + 1, end + 1, lines, emptyList(), emptyList()),
                    )
                }
            }
            true
        }
        return result.sortedWith(compareBy<TodoItem> { it.anchor?.path }.thenBy { it.anchor?.startLine })
    }

    /**
     * Whether a directory above [file] is excluded. A build system can put sources back into the
     * project from inside an excluded directory, as CMake does with fetched dependencies under
     * the build directory; those are not the project's own files.
     */
    private fun underExcluded(index: ProjectFileIndex, file: VirtualFile): Boolean =
        generateSequence(file.parent) { it.parent }.any { index.isExcluded(it) }

    /** Whether [file] is in [scope]; no scope means everywhere. */
    fun inScope(scope: SearchScope?, file: VirtualFile): Boolean = when (scope) {
        null -> true
        is GlobalSearchScope -> scope.contains(file)
        is LocalSearchScope -> scope.isInScope(file)
        else -> true
    }

    fun filter(items: List<TodoItem>, text: String): List<TodoItem> {
        val words = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return items.filter { item -> words.all { item.title.lowercase().contains(it) || item.id.lowercase().contains(it) } }
    }

    /** The comment's words without the leading TODO marker, as a title for a tracked item. */
    fun trackedTitle(item: TodoItem): String = item.title.replace(Regex("^(?i)(todo|fixme)\\b[:\\s]*"), "").ifBlank { item.title }

    /** An anchor with context lines, for tracking [item] in the store. */
    fun trackedAnchor(project: Project, item: TodoItem): Anchor? {
        val anchor = item.anchor ?: return null
        val text = TodoService.getInstance(project).readText(anchor.path) ?: return anchor
        return AnchorResolver.capture(anchor.path, text, anchor.startLine, anchor.endLine)
    }
}
