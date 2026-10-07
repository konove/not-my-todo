package io.github.konove.notmytodo.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import io.github.konove.notmytodo.channel.ChannelServer
import io.github.konove.notmytodo.handoff.ClaudeLauncher
import io.github.konove.notmytodo.handoff.HandoffDialog
import io.github.konove.notmytodo.handoff.PromptBuilder
import io.github.konove.notmytodo.handoff.PromptOptions
import io.github.konove.notmytodo.handoff.TargetChoice
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.settings.FixTarget
import io.github.konove.notmytodo.settings.TodoProjectSettings
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.store.StoreException
import io.github.konove.notmytodo.ui.TodoPanel
import java.awt.datatransfer.StringSelection

/** Item actions shared by the panel and the gutter popup. Call on the event thread. */
object ItemActions {
    const val TOOL_WINDOW_ID = "Not My TODO"

    /** Closing a parent that still has open parts is asked about first: it is allowed, but seldom meant. */
    fun setStatus(project: Project, id: String, status: Status) {
        val store = TodoService.getInstance(project).store
        if (status == Status.DONE || status == Status.WONT_FIX) {
            val open = Links(store.items).children(id).count { !it.isClosed }
            val parts = if (open == 1) "1 open part" else "$open open parts"
            if (open > 0 && Messages.showYesNoDialog(project, "$id still has $parts. Close it anyway?", "Not My TODO", null) != Messages.YES) return
        }
        try {
            store.update(id) { it.copy(status = status) }
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        }
    }

    /** Opens the item's first anchor in the editor, or [anchor] when one is named. */
    fun navigate(project: Project, item: TodoItem, anchor: Anchor? = item.anchor) {
        if (anchor == null) return
        val file = TodoService.getInstance(project).findFile(anchor.path) ?: return
        OpenFileDescriptor(project, file, maxOf(0, anchor.startLine - 1), 0).navigate(true)
    }

    /** The text of every file [items] are anchored in and that can still be read, by path. */
    private fun fileTexts(project: Project, items: List<TodoItem>): Map<String, String> {
        val service = TodoService.getInstance(project)
        return items.flatMap { it.anchors }.filterNot { it.lost }.map { it.path }.distinct()
            .mapNotNull { path -> service.readText(path)?.let { path to it } }.toMap()
    }

    fun openInPanel(project: Project, id: String) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        window.activate {
            (window.contentManager.contents.firstOrNull()?.component as? TodoPanel)?.select(id)
        }
    }

    /** Shows the handoff dialog and sends the prompt for a TODO comment found in the code. Nothing is stored. */
    fun fixComment(project: Project, item: TodoItem) = handOff(project, item)

    /** Shows the handoff dialog, sends the prompt, and marks the item in progress. */
    fun fix(project: Project, id: String) {
        project.service<AnchorTracker>().flushAll()
        val item = TodoService.getInstance(project).store.find(id) ?: return
        if (handOff(project, item)) setStatus(project, id, Status.IN_PROGRESS)
    }

    private fun handOff(project: Project, item: TodoItem): Boolean {
        val values = TodoSettings.getInstance().values
        val files = fileTexts(project, listOf(item))
        val options = promptOptions(project)
        val available = availability(project)
        val target: FixTarget
        val prompt: String
        if (values.skipFixDialog) {
            target = TargetChoice.choose(values.defaultTarget, available).target
            prompt = PromptBuilder.build(item, files, "", options)
        } else {
            val dialog = HandoffDialog(project, item, files, available, values.defaultTarget, options)
            if (!dialog.showAndGet()) return false
            target = dialog.target
            prompt = dialog.prompt
            if (dialog.skipNextTime) {
                // Sending to a session can only have been chosen with the channel on, so the pair is one the settings accept.
                TodoSettings.getInstance().update(values.apply {
                    skipFixDialog = true
                    defaultTarget = target
                })
            }
        }
        deliver(project, target, prompt, listOf(item.id))
        return true
    }

    private fun promptOptions(project: Project): PromptOptions = TodoSettings.getInstance().values
        .promptOptions(TodoProjectSettings.getInstance(project).itemsFile, ClaudeLauncher.isMcpAvailable())

    /** What can take a prompt now. */
    private fun availability(project: Project): TargetChoice.Availability {
        val channelOn = TodoSettings.getInstance().values.channelEnabled
        return TargetChoice.Availability(
            channelOn = channelOn,
            sessionConnected = channelOn && project.service<ChannelServer>().connected > 0,
            terminal = ClaudeLauncher.isTerminalAvailable(),
        )
    }

    private fun deliver(project: Project, target: FixTarget, prompt: String, itemIds: List<String>) {
        when (target) {
            // The send writes to a socket with no timeout, so a stalled session must not block the event thread.
            FixTarget.SESSION -> ApplicationManager.getApplication().executeOnPooledThread {
                val sent = project.service<ChannelServer>().send(prompt, itemIds)
                ApplicationManager.getApplication().invokeLater({
                    if (sent) {
                        // Claude Code does not confirm; if the prompt did not arrive, a new tab is one click away.
                        notify(project, "Sent to the running Claude Code session.", prompt)
                    } else {
                        // The session went away after the target was chosen.
                        val next = TargetChoice.choose(FixTarget.TERMINAL, availability(project)).target
                        deliver(project, next, prompt, itemIds)
                        val where = if (next == FixTarget.TERMINAL) "a new terminal tab" else "the clipboard"
                        notify(project, "The Claude Code session is no longer connected. The prompt went to $where instead.", null)
                    }
                }, project.disposed)
            }
            FixTarget.TERMINAL -> ClaudeLauncher.launch(project, prompt)
            FixTarget.CLIPBOARD -> CopyPasteManager.getInstance().setContents(StringSelection(prompt))
        }
    }

    /** Shows a balloon. With [retryPrompt], it offers to open that prompt in a new terminal tab. */
    private fun notify(project: Project, text: String, retryPrompt: String?) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
            .createNotification(text, NotificationType.INFORMATION)
        if (retryPrompt != null && ClaudeLauncher.isTerminalAvailable()) {
            notification.addAction(NotificationAction.createSimpleExpiring("Open in a new tab instead") {
                ClaudeLauncher.launch(project, retryPrompt)
            })
        }
        notification.notify(project)
    }

    /**
     * Sends every one of [shown] that is still to be done to Claude in one prompt, after asking.
     * Tracked items are marked in progress; TODO comments from the code are sent as they are.
     */
    fun fixAll(project: Project, shown: List<TodoItem>) {
        val service = TodoService.getInstance(project)
        project.service<AnchorTracker>().flushAll()
        val candidates = shown.mapNotNull { if (CodeTodos.isCode(it)) it else service.store.find(it.id) }
            .filter { CodeTodos.isCode(it) || it.status == Status.OPEN || it.status == Status.IN_PROGRESS }
        // What waits for my decision is not an agent's to fix.
        val items = candidates.filterNot { it.needsDecision }
        val held = candidates.size - items.size
        if (items.isEmpty()) {
            val why = if (held > 0) "The $held open items shown all need your decision first." else "None of the items shown is open or in progress."
            Messages.showInfoMessage(project, why, "Not My TODO")
            return
        }
        val values = TodoSettings.getInstance().values
        if (items.size > values.fixAllLimit) {
            Messages.showInfoMessage(
                project, "${items.size} items are shown. Narrow the list to ${values.fixAllLimit} or fewer to send them in one prompt.",
                "Not My TODO",
            )
            return
        }
        val target = TargetChoice.choose(values.defaultTarget, availability(project)).target
        val where = when (target) {
            FixTarget.SESSION -> "the running Claude Code session"
            FixTarget.TERMINAL -> "Claude Code in a new terminal tab"
            FixTarget.CLIPBOARD -> "the clipboard"
        }
        val tracked = items.filterNot(CodeTodos::isCode)
        val names = items.take(8).joinToString(", ") { it.id } + if (items.size > 8) ", ..." else ""
        val after = (if (tracked.isEmpty()) "" else " and mark the tracked ones in progress") +
            if (held > 0) "? $held that need your decision are left out" else ""
        val answer = Messages.showYesNoDialog(
            project, "Send ${items.size} items ($names) to $where$after${if (held > 0) "." else "?"}", "Fix with Claude", null,
        )
        if (answer != Messages.YES) return
        val prompt = PromptBuilder.buildAll(items, fileTexts(project, items), promptOptions(project))
        deliver(project, target, prompt, items.map { it.id })
        tracked.forEach { setStatus(project, it.id, Status.IN_PROGRESS) }
    }
}
