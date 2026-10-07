package io.github.konove.notmytodo.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.konove.notmytodo.channel.ChannelRegistration
import io.github.konove.notmytodo.channel.ChannelServer
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.ui.EditorDecorator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class TodoStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            project.service<AnchorTracker>().start()
            project.service<EditorDecorator>().start()
        }
        project.service<ChannelServer>().sync()
        // Tests must not write into the user's own configuration directory.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        withContext(Dispatchers.IO) {
            try {
                // The IDE or the plugin may have moved since the bridge was registered.
                val registration = ChannelRegistration()
                if (TodoSettings.getInstance().values.channelEnabled || registration.launcherExists) registration.writeLauncher()
            } catch (e: IOException) {
                thisLogger().warn("The Not My TODO bridge launcher could not be written", e)
            }
        }
    }
}
