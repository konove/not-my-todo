package io.github.konove.notmytodo.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/** Whether other plugins this one can work with are there to use. */
object Plugins {
    /** True when the plugin with [id] is installed and enabled. */
    fun isEnabled(id: String): Boolean = PluginManagerCore.isLoaded(PluginId.getId(id))
}
