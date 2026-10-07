package io.github.konove.notmytodo.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.Anchor

object EditorAnchors {
    /** Builds an anchor covering the whole lines touched by the editor's selection. */
    fun fromSelection(project: Project, editor: Editor): Anchor? {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val path = TodoService.getInstance(project).relativePath(file) ?: return null
        val start = selection.selectionStart
        var end = selection.selectionEnd
        // A selection that stops at the start of a line does not include that line.
        if (end > start && document.getLineStartOffset(document.getLineNumber(end)) == end) end--
        return AnchorResolver.capture(path, document.text, document.getLineNumber(start) + 1, document.getLineNumber(end) + 1)
    }
}
