package io.github.konove.notmytodo.handoff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.util.ui.UIUtil
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.IOException

/**
 * Lets a dragged row land in a classic terminal tab, such as the one the Claude Code plugin opens. The Terminal
 * tool window types a dropped text only into a tab of the reworked terminal; a classic tab has no drop target
 * of its own, so the drop reaches the tool window, which keeps nothing of it but files. While a row is dragged,
 * each classic tab gets a drop target that types the text in. The targets come off when the drag ends, so that
 * anything else dropped there still reaches the tool window.
 */
object TerminalDrop {
    private const val TOOL_WINDOW_ID = "Terminal"

    /** Gives the classic terminal tabs of the open projects a drop target; the returned function takes them off again. */
    fun attach(): () -> Unit {
        if (GraphicsEnvironment.isHeadless()) return {}
        val attached = ProjectManager.getInstance().openProjects
            .mapNotNull { ToolWindowManager.getInstance(it).getToolWindow(TOOL_WINDOW_ID) }
            .flatMap { window -> window.contentManager.contents.toList() }
            .flatMap { UIUtil.findComponentsOfType(it.component, JBTerminalWidget::class.java) }
            .map { widget ->
                val before = widget.dropTarget
                val target = DropTarget(widget, DnDConstants.ACTION_COPY, Typist(widget), true)
                Triple(widget, before, target)
            }
        return {
            attached.forEach { (widget, before, target) -> if (widget.dropTarget === target) widget.dropTarget = before }
        }
    }

    /**
     * [text] as a paste: lines end in carriage returns, as the terminal's own paste sends them, and bracketed so that
     * Claude Code takes it whole rather than sending its first line. A classic tab does not tell whether the program
     * in it asked for bracketed paste; Claude Code always does. Escape characters are dropped, so that an item's text
     * cannot end the paste early and type a command of its own.
     */
    internal fun pasted(text: String): String =
        "\u001b[200~" + text.replace("\u001b", "").replace("\r\n", "\n").replace('\n', '\r') + "\u001b[201~"

    private class Typist(private val widget: JBTerminalWidget) : DropTargetAdapter() {
        override fun dragEnter(e: DropTargetDragEvent) = check(e)

        override fun dragOver(e: DropTargetDragEvent) = check(e)

        private fun check(e: DropTargetDragEvent) =
            if (e.isDataFlavorSupported(DataFlavor.stringFlavor)) e.acceptDrag(DnDConstants.ACTION_COPY) else e.rejectDrag()

        override fun drop(e: DropTargetDropEvent) {
            val tty = widget.ttyConnector
            if (tty == null || !e.isDataFlavorSupported(DataFlavor.stringFlavor)) return e.rejectDrop()
            e.acceptDrop(DnDConstants.ACTION_COPY)
            val text = e.transferable.getTransferData(DataFlavor.stringFlavor) as? String
            if (text != null) {
                // A long prompt can fill the pty's buffer, and the write then waits for the program to read.
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        tty.write(pasted(text))
                    } catch (failure: IOException) {
                        thisLogger().warn("The dropped prompt could not be sent to the terminal", failure)
                    }
                }
                IdeFocusManager.getGlobalInstance().requestFocus(widget.terminalPanel, true)
            }
            e.dropComplete(text != null)
        }
    }
}
