package io.github.konove.notmytodo.handoff

import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.ide.Plugins
import io.github.konove.notmytodo.settings.TodoSettings
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path

/** Starts Claude Code with a prompt. The prompt travels in a file so no shell quoting of its text is needed. */
object ClaudeLauncher {
    private const val TERMINAL_PLUGIN = "org.jetbrains.plugins.terminal"
    private const val MCP_PLUGIN = "com.intellij.mcpServer"

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

    fun isMcpAvailable(): Boolean = Plugins.isEnabled(MCP_PLUGIN)

    /** Opens a terminal tab in the project directory and runs Claude Code with [prompt]. */
    fun launch(project: Project, prompt: String) {
        val file = Files.createTempFile("not-my-todo-", ".md")
        Files.writeString(file, prompt)
        file.toFile().deleteOnExit()
        val values = TodoSettings.getInstance().values
        TerminalRunner.run(project, command(file, values.claudeCommand, values.channelEnabled))
    }

    /** Kept apart so terminal classes load only when the terminal plugin is present. */
    private object TerminalRunner {
        fun run(project: Project, command: String) {
            val widget = TerminalToolWindowManager.getInstance(project)
                .createShellWidget(project.basePath, "Not My TODO", true, true)
            widget.sendCommandToExecute(command)
        }
    }
}
