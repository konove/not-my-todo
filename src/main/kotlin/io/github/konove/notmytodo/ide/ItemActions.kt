package io.github.konove.notmytodo.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.settings.FixTarget
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.store.StoreException
import io.github.konove.notmytodo.ui.TodoPanel
import java.awt.datatransfer.StringSelection

/** Item actions shared by the panel and the gutter popup. Call on the event thread. */
object ItemActions {
    const val TOOL_WINDOW_ID = "Not My TODO"

    /** Closing a parent that still has open parts is asked about first: it is allowed, but seldom meant. [id] is a view id. */
    fun setStatus(project: Project, id: String, status: Status) {
        val view = StoreView.getInstance(project)
        val target = view.target(id) ?: return
        if (status == Status.DONE || status == Status.WONT_FIX) {
            val open = Links(target.items.items).children(target.id).count { !it.isClosed }
            val parts = if (open == 1) "1 open part" else "$open open parts"
            if (open > 0 && Messages.showYesNoDialog(project, "$id still has $parts. Close it anyway?", "Not My TODO", null) != Messages.YES) return
        }
        try {
            view.update(id) { it.copy(status = status) }
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
        }
    }

    /**
     * Opens the item's first anchor in the editor, or [anchor] when one is named. Another project's item is opened in the
     * window of its checkout, and a note of it, with no code to go to, opens no window.
     */
    fun navigate(project: Project, item: TodoItem, anchor: Anchor? = item.anchor) {
        if (anchor == null) return
        if (ViewIds.isElsewhere(item.id)) return Checkouts.goTo(project, item.id, maxOf(0, item.anchors.indexOf(anchor)))
        val file = TodoService.getInstance(project).findFile(anchor.path) ?: return
        OpenFileDescriptor(project, file, maxOf(0, anchor.startLine - 1), 0).navigate(true)
    }

    /** The text of every file [items] are anchored in and that can still be read, by path. Another project's are read in its checkout. */
    private fun fileTexts(project: Project, items: List<TodoItem>): Map<String, String> {
        val view = StoreView.getInstance(project)
        return items.flatMap { item -> item.anchors.filterNot { it.lost }.map { keyOf(view, item) to it.path } }.distinct()
            .mapNotNull { (key, path) -> view.readText(key, path)?.let { path to it } }.toMap()
    }

    private fun keyOf(view: StoreView, item: TodoItem): String = ViewIds.split(item.id, view.home)?.project ?: view.home

    fun openInPanel(project: Project, id: String) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        window.activate {
            (window.contentManager.contents.firstOrNull()?.component as? TodoPanel)?.select(id)
        }
    }

    /** Shows the handoff dialog and sends the prompt for a TODO comment found in the code. Nothing is stored. */
    fun fixComment(project: Project, item: TodoItem) = handOff(project, item)

    /**
     * Shows the handoff dialog, sends the prompt, and marks the item in progress. A prompt that
     * went to the clipboard may reach no agent, so it has the agent do the marking. Another project's
     * item is sent to a Claude Code started in its checkout, with no dialog.
     */
    fun fix(project: Project, id: String) {
        if (ViewIds.isElsewhere(id)) return fixElsewhere(project, id)
        project.service<AnchorTracker>().flushAll()
        val store = TodoService.getInstance(project).store
        val item = store.find(id) ?: return
        val target = handOff(project, TodoService.getInstance(project).links().shown(item)) ?: return
        if (target != FixTarget.CLIPBOARD) setStatus(project, id, Status.IN_PROGRESS)
    }

    /** The prompt for [viewId], another project's item, as its own project names it: the agent starts in its checkout. */
    internal fun elsewherePrompt(project: Project, viewId: String): String? {
        val view = StoreView.getInstance(project)
        val target = view.target(viewId) ?: return null
        val item = target.items.find(target.id) ?: return null
        val shown = Links(target.items.items) { view.find(ViewIds.stored(it, target.key)) }.shown(item)
        val files = shown.anchors.filterNot { it.lost }.map { it.path }.distinct()
            .mapNotNull { path -> view.readText(target.key, path)?.let { path to it } }.toMap()
        val options = TodoSettings.getInstance().values.promptOptions(view.itemsDir(target.key).toString(), ClaudeLauncher.areToolsAvailable())
        return PromptBuilder.build(shown, files, "", options)
    }

    private fun fixElsewhere(project: Project, viewId: String) {
        val view = StoreView.getInstance(project)
        val key = ViewIds.split(viewId, view.home)?.project ?: return
        val checkout = view.checkout(key)
        if (checkout == null) {
            Messages.showInfoMessage(project, "$key has no checkout on this machine, so there is no folder to start Claude Code in.", "Not My TODO")
            return
        }
        if (!ClaudeLauncher.isTerminalAvailable()) {
            Messages.showInfoMessage(project, "Starting Claude Code in $checkout needs the Terminal plugin.", "Not My TODO")
            return
        }
        val prompt = elsewherePrompt(project, viewId) ?: return
        ClaudeLauncher.launch(project, prompt, checkout.toString(), channel = false)
        setStatus(project, viewId, Status.IN_PROGRESS)
    }

    /**
     * The prompt a row of the list carries when it is dragged out, say onto a terminal where Claude
     * Code runs, or copied. Nothing is known of where it lands, so the status is left alone and
     * the prompt has the agent mark the item in progress. Another project's item keeps its
     * qualified id, which any session's server can find.
     */
    fun dragPrompt(project: Project, row: TodoItem): String {
        project.service<AnchorTracker>().flushAll()
        val view = StoreView.getInstance(project)
        val item = when {
            CodeTodos.isCode(row) -> row
            ViewIds.isElsewhere(row.id) -> view.links().shown(view.find(row.id) ?: row)
            else -> TodoService.getInstance(project).links().shown(view.find(row.id) ?: row)
        }
        saveAnchored(project, listOf(item))
        val options = promptOptions(project).copy(claim = true, itemsDir = view.itemsDir(keyOf(view, item)).toString())
        return PromptBuilder.build(item, fileTexts(project, listOf(item)), "", options)
    }

    /** Where the prompt went, or null when the dialog was cancelled. */
    private fun handOff(project: Project, item: TodoItem): FixTarget? {
        saveAnchored(project, listOf(item))
        val values = TodoSettings.getInstance().values
        val files = fileTexts(project, listOf(item))
        val options = promptOptions(project)
        val available = availability(project)
        val target: FixTarget
        val prompt: String
        if (values.skipFixDialog) {
            target = TargetChoice.choose(values.defaultTarget, available).target
            prompt = PromptBuilder.build(item, files, "", options.copy(claim = target == FixTarget.CLIPBOARD))
        } else {
            val dialog = HandoffDialog(project, item, files, available, values.defaultTarget, options)
            if (!dialog.showAndGet()) return null
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
        return target
    }

    private fun promptOptions(project: Project): PromptOptions = TodoSettings.getInstance().values
        .promptOptions(TodoService.getInstance(project).itemsDir.toString(), ClaudeLauncher.areToolsAvailable())

    /** The agent reads the files from disk, so what the editor shows of [items]' files is saved first. */
    private fun saveAnchored(project: Project, items: List<TodoItem>) {
        val service = TodoService.getInstance(project)
        val documents = FileDocumentManager.getInstance()
        val own = items.filterNot { ViewIds.isElsewhere(it.id) }
        own.flatMap { it.anchors }.map { it.path }.distinct().mapNotNull(service::findFile)
            .mapNotNull(documents::getCachedDocument).filter(documents::isDocumentUnsaved).forEach(documents::saveDocument)
    }

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
     * Tracked items are marked in progress, or by the agent when the prompt goes to the clipboard;
     * TODO comments from the code are sent as they are.
     */
    fun fixAll(project: Project, shown: List<TodoItem>) {
        val service = TodoService.getInstance(project)
        project.service<AnchorTracker>().flushAll()
        // Fix All sends one prompt to one session, in this project: another project's items are sent each from its own checkout.
        val elsewhere = shown.count { ViewIds.isElsewhere(it.id) && !it.isClosed }
        val candidates = shown.filterNot { ViewIds.isElsewhere(it.id) }.mapNotNull { if (CodeTodos.isCode(it)) it else service.store.find(it.id) }
            .filter { CodeTodos.isCode(it) || it.status == Status.OPEN || it.status == Status.IN_PROGRESS }
        // What waits for my decision is not an agent's to fix.
        val items = candidates.filterNot { it.needsDecision }
        val held = candidates.size - items.size
        if (items.isEmpty()) {
            val why = when {
                held > 0 -> "The $held open items shown all need your decision first."
                elsewhere > 0 -> "Fix All sends this project's items only, and none of those shown is open. Fix the items of other projects one at a time."
                else -> "None of the items shown is open or in progress."
            }
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
        val copied = target == FixTarget.CLIPBOARD
        val marks = tracked.isNotEmpty() && !copied
        val answer = Messages.showYesNoDialog(project, fixAllQuestion(items.size, names, where, marks, held, elsewhere), "Fix with Claude", null)
        if (answer != Messages.YES) return
        saveAnchored(project, items)
        val links = TodoService.getInstance(project).links()
        val prompt = PromptBuilder.buildAll(items.map(links::shown), fileTexts(project, items), promptOptions(project).copy(claim = copied))
        deliver(project, target, prompt, items.map { it.id })
        if (!copied) tracked.forEach { setStatus(project, it.id, Status.IN_PROGRESS) }
    }

    /** What Fix All asks: a question, with what is left out said after it. */
    internal fun fixAllQuestion(count: Int, names: String, where: String, marks: Boolean, held: Int, elsewhere: Int): String {
        val left = listOfNotNull(
            "$held that need your decision".takeIf { held > 0 },
            "$elsewhere of other projects".takeIf { elsewhere > 0 },
        )
        val after = (if (marks) " and mark the tracked ones in progress" else "") +
            if (left.isEmpty()) "?" else "? ${left.joinToString(" and ")} are left out."
        return "Send $count items ($names) to $where$after"
    }
}
