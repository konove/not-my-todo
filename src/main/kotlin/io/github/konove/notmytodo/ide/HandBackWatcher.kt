package io.github.konove.notmytodo.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.konove.notmytodo.handoff.HandBack
import io.github.konove.notmytodo.handoff.HandBacks
import io.github.konove.notmytodo.model.TodoItem
import java.nio.file.Path

/** Shows a balloon when an agent comments on an item that waits for me or that is in progress, or says what I have to decide. */
@Service(Service.Level.PROJECT)
class HandBackWatcher(private val project: Project) : Disposable {
    // Held, not looked up, so that it can still be let go of when the project is closing.
    private val store = TodoService.getInstance(project).store
    private val listener = { check() }
    private var started = false

    // What the store held when it was last looked at. Read and written under the watcher's monitor.
    private var file: Path? = null
    private var items: List<TodoItem> = emptyList()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        file = store.file
        items = store.items
        store.addListener(listener)
    }

    /** Called by the store on whichever thread made the change. */
    private fun check() {
        val found = synchronized(this) {
            val before = items
            // Another file is another list: the same id there is not the same item.
            val same = file == store.file
            file = store.file
            items = store.items
            if (same) HandBacks.between(before, items) else emptyList()
        }
        if (found.isNotEmpty()) show(found)
    }

    /** One balloon for everything that came back in one write, so that a batch does not fill the screen. */
    private fun show(found: List<HandBack>) {
        // What waits for me comes before what is only a note.
        val backs = found.sortedByDescending { it.item.needsDecision }
        val first = backs.first().item
        val deciding = backs.count { it.item.needsDecision }
        val title: String
        val content: String
        if (backs.size == 1) {
            title = if (deciding == 1) "${first.id} needs your decision" else "The agent left a comment on ${first.id}"
            val said = StringUtil.shortenTextWithEllipsis(backs.first().said.lines().joinToString(" ") { it.trim() }, MAX_SHOWN, 0)
            content = StringUtil.escapeXmlEntities(first.title) + "<br>" + StringUtil.escapeXmlEntities(said)
        } else {
            title = when (deciding) {
                backs.size -> "${backs.size} items need your decision"
                0 -> "The agent left comments on ${backs.size} items"
                else -> "The agent left comments on ${backs.size} items; $deciding ${if (deciding == 1) "needs" else "need"} your decision"
            }
            val named = backs.take(MAX_NAMED).map { StringUtil.escapeXmlEntities("${it.item.id} ${it.item.title}") }
            content = (named + listOfNotNull("and ${backs.size - MAX_NAMED} more".takeIf { backs.size > MAX_NAMED })).joinToString("<br>")
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
            .createNotification(title, content, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Show") { ItemActions.openInPanel(project, first.id) })
            .notify(project)
    }

    override fun dispose() {
        store.removeListener(listener)
    }

    private companion object {
        const val MAX_SHOWN = 200
        const val MAX_NAMED = 5
    }
}
