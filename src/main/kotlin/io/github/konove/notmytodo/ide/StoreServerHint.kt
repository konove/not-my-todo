package io.github.konove.notmytodo.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.channel.ChannelRegistration
import io.github.konove.notmytodo.settings.TodoSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Claude Code gets the todo tools from the notmytodo-store server, which is installed apart from the
 * plugin. Whether it has it is asked at startup and again, while it has not, when the IDE is come back
 * to, at most every five minutes: a server installed meanwhile is found without a restart. When it has
 * not, a balloon says how to install it, once a session, until the user asks not to be told again.
 */
@Service(Service.Level.APP)
class StoreServerHint {
    private val told = AtomicBoolean()
    private val lock = Any()
    private var lastAsked: Long? = null

    /** The time, for the interval between asks. Replaced in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** True once Claude Code has said it has the server; false while that is not known. */
    @Volatile
    var registered = false
        private set

    /**
     * Asks Claude Code, which takes a moment: not for the event thread. Asks nothing once it has the
     * server, or within [INTERVAL_MS] of the last ask, since each ask starts a Claude Code process.
     */
    fun check(project: Project, registration: StoreRegistration = StoreRegistration()) {
        synchronized(lock) {
            if (registered) return
            val now = clock()
            lastAsked?.let { if (now - it < INTERVAL_MS) return }
            lastAsked = now
        }
        val status = registration.status()
        registered = status == ChannelRegistration.Status.REGISTERED
        if (status == ChannelRegistration.Status.NOT_REGISTERED && !TodoSettings.getInstance().values.storeHintDismissed && told.compareAndSet(false, true)) show(project)
    }

    private fun show(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
            .createNotification(TITLE, TEXT, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Don't Show Again") {
                TodoSettings.getInstance().update(TodoSettings.getInstance().values.apply { storeHintDismissed = true })
            })
            .notify(project)
    }

    companion object {
        /** How long to wait between asks of Claude Code while it has not the server. */
        const val INTERVAL_MS = 5 * 60 * 1000L
        const val TITLE = "Agents get the todo tools from not-my-todo-store"
        private const val TEXT = "Claude Code has no notmytodo-store server, so its sessions cannot list or fix these items. " +
            "Install it from the not-my-todo-store repository with <code>./gradlew installServer</code>."

        fun getInstance(): StoreServerHint = service()
    }
}
