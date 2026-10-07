package io.github.konove.notmytodo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import io.github.konove.notmytodo.handoff.PromptOptions
import io.github.konove.notmytodo.model.Priority

/**
 * Where Fix sends its prompt. The order is the order of falling back when one is not available.
 * [hint] says what choosing it means, and [action] is what the Fix dialog's button says for it.
 */
enum class FixTarget(val label: String, val hint: String, val action: String) {
    SESSION(
        "Running Claude Code session",
        "Joins the conversation already open, with everything it has read so far.",
        "Send to Session",
    ),
    TERMINAL("New terminal tab", "Starts Claude Code from scratch in the project directory.", "Open in Terminal"),
    CLIPBOARD("Clipboard", "Copies the prompt for you to paste anywhere.", "Copy Prompt"),
}

/** Told on the application message bus after any option, per user or per project, has changed. */
fun interface TodoSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<TodoSettingsListener> = Topic.create("Not My TODO settings", TodoSettingsListener::class.java)

        fun fire() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
    }
}

/** The options that are the same in every project. */
@Service(Service.Level.APP)
@State(name = "NotMyTodoSettings", storages = [Storage("notMyTodo.xml")])
class TodoSettings : PersistentStateComponent<TodoSettings.Values> {
    data class Values(
        var channelEnabled: Boolean = false,
        var defaultTarget: FixTarget = FixTarget.TERMINAL,
        var claudeCommand: String = "claude",
        var skipFixDialog: Boolean = false,
        var fixAllLimit: Int = 50,
        var contextLines: Int = 20,
        var shortPrompt: Boolean = false,
        var extraInstructions: String = "",
        var donePhrases: String = DEFAULT_DONE_PHRASES,
        var defaultPriority: Priority = Priority.P2,
        var editorMarks: Boolean = true,
        var codeComments: Boolean = true,
    ) {
        /** The prompt options for a project whose items are in [itemsFile]. The short form needs the MCP tools. */
        fun promptOptions(itemsFile: String, mcpAvailable: Boolean): PromptOptions =
            PromptOptions(contextLines, shortPrompt && mcpAvailable, extraInstructions, itemsFile)

        /** The phrases the Fix dialog offers for "Done when", one to a line in [donePhrases]. */
        fun donePhraseList(): List<String> = donePhrases.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private var current = Values()

    /** A copy: changing it changes nothing until it is passed to [update]. */
    val values: Values get() = current.copy()

    fun update(next: Values) {
        current = sane(next.copy())
        TodoSettingsListener.fire()
    }

    override fun getState(): Values = current

    override fun loadState(state: Values) {
        current = sane(state)
    }

    /** The file can be edited by hand; numbers outside their range and an empty command are pulled back in. */
    private fun sane(v: Values): Values = v.apply {
        fixAllLimit = fixAllLimit.coerceIn(1, 500)
        contextLines = contextLines.coerceIn(0, 200)
        if (claudeCommand.isBlank()) claudeCommand = "claude"
    }

    companion object {
        /** Each reads on after "Done when:" and holds in any language. */
        const val DEFAULT_DONE_PHRASES = "the project builds\nthe tests pass\na new test covers the change\n" +
            "nothing unrelated is changed\nI have approved the diff\nthe change is committed"

        fun getInstance(): TodoSettings = service()
    }
}
