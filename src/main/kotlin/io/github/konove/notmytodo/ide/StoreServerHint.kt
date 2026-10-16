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
 * plugin. Whether it has it is asked once an IDE session; when it has not, a balloon says how to
 * install it, until the user asks not to be told again.
 */
@Service(Service.Level.APP)
class StoreServerHint {
    private val asked = AtomicBoolean()

    /** True once Claude Code has said it has the server; false while that is not known. */
    @Volatile
    var registered = false
        private set

    /** Asks Claude Code, which takes a moment: not for the event thread. */
    fun check(project: Project, registration: StoreRegistration = StoreRegistration()) {
        if (!asked.compareAndSet(false, true)) return
        val status = registration.status()
        registered = status == ChannelRegistration.Status.REGISTERED
        if (status == ChannelRegistration.Status.NOT_REGISTERED && !TodoSettings.getInstance().values.storeHintDismissed) show(project)
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
        const val TITLE = "Agents get the todo tools from not-my-todo-store"
        private const val TEXT = "Claude Code has no notmytodo-store server, so its sessions cannot list or fix these items. " +
            "Install it from the not-my-todo-store repository with <code>./gradlew installServer</code>."

        fun getInstance(): StoreServerHint = service()
    }
}
