package io.github.konove.notmytodo.handoff

import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.ide.Plugins
import io.github.konove.notmytodo.ide.StoreServerHint
import io.github.konove.notmytodo.settings.TodoSettings
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path

/** Starts Claude Code with a prompt. The prompt travels in a file so no shell quoting of its text is needed. */
object ClaudeLauncher {
    private const val TERMINAL_PLUGIN = "org.jetbrains.plugins.terminal"

    /** The name the bridge is registered under with Claude Code. */
    const val CHANNEL_NAME = "notmytodo"
    const val CHANNEL_FLAG = "--dangerously-load-development-channels server:$CHANNEL_NAME"

    /**
     * The shell command for a new terminal tab. [claude] is typed as it is, so it may carry its own
     * flags. With [channel] the flag that loads the channel is added unless [claude] already has it.
     * That flag takes every following argument that does not start with `-` as a server name, so
     * when the start carries it, `--` goes before the prompt to keep the prompt from being read as one.
     */
    fun command(promptFile: Path, claude: String = "claude", channel: Boolean = false): String {
        val quoted = "'" + promptFile.toString().replace("'", "'\\''") + "'"
        val base = claude.trim().ifEmpty { "claude" }
        val start = if (channel && !base.contains("server:$CHANNEL_NAME")) "$base $CHANNEL_FLAG" else base
        val separator = if (start.contains("server:$CHANNEL_NAME")) " --" else ""
        return "$start$separator \"\$(cat $quoted)\""
    }

    fun isTerminalAvailable(): Boolean = Plugins.isEnabled(TERMINAL_PLUGIN)

    /** Whether Claude Code has the server with the todo tools, so that a prompt can send the agent to todo_get. */
    fun areToolsAvailable(): Boolean = StoreServerHint.getInstance().registered

    /**
     * Opens a terminal tab in [dir] and runs Claude Code with [prompt]. [channel] loads the channel,
     * which reaches this IDE project only: it is left off for a session started in another checkout.
     */
    fun launch(
        project: Project, prompt: String, dir: String? = project.basePath,
        channel: Boolean = TodoSettings.getInstance().values.channelEnabled,
    ) {
        val file = Files.createTempFile("not-my-todo-", ".md")
        Files.writeString(file, prompt)
        file.toFile().deleteOnExit()
        TerminalRunner.run(project, dir, command(file, TodoSettings.getInstance().values.claudeCommand, channel))
    }

    /** Kept apart so terminal classes load only when the terminal plugin is present. */
    private object TerminalRunner {
        fun run(project: Project, dir: String?, command: String) {
            val widget = TerminalToolWindowManager.getInstance(project)
                .createShellWidget(dir, "Not My TODO", true, true)
            widget.sendCommandToExecute(command)
        }
    }
}
