package io.github.konove.notmytodo.channel

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.settings.TodoSettingsListener
import java.io.IOException
import java.nio.file.Path

/** Listens for this project's running Claude Code sessions while the channel option is on. */
@Service(Service.Level.PROJECT)
class ChannelServer(project: Project) : Disposable {
    private val hub = project.basePath?.let { ChannelHub(Path.of(it), Path.of(BridgeMain.defaultCacheDir())) }

    /** Why the channel is not listening although it is switched on, or null. */
    @Volatile
    var error: String? = null
        private set

    val running: Boolean get() = hub?.running == true
    val connected: Int get() = hub?.connected ?: 0
    val withoutFlag: Int get() = hub?.withoutFlag ?: 0

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(TodoSettingsListener.TOPIC, TodoSettingsListener { sync() })
    }

    /**
     * Starts or stops listening to match the option. Deliberately synchronous and cheap (a loopback bind
     * and one small file), so callers can read [running] and [error] right after.
     */
    fun sync() {
        val hub = hub ?: return
        if (!TodoSettings.getInstance().values.channelEnabled) {
            hub.stop()
            error = null
            return
        }
        error = try {
            hub.start()
            null
        } catch (e: IOException) {
            "The channel could not start: ${e.message}"
        } catch (e: RuntimeException) {
            "The channel could not start: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Sends [prompt] to the newest connected session. False when there is none. */
    fun send(prompt: String, itemIds: List<String>): Boolean = hub?.send(prompt, itemIds) == true

    override fun dispose() {
        hub?.stop()
    }
}
