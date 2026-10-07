package io.github.konove.notmytodo.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import io.github.konove.notmytodo.ide.EditorAnchors

/** Opens the capture dialog, anchored to the editor selection when there is one. */
class AddTodoAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val anchor = e.getData(CommonDataKeys.EDITOR)?.let { EditorAnchors.fromSelection(project, it) }
        CaptureDialog(project, anchor).show()
    }
}
