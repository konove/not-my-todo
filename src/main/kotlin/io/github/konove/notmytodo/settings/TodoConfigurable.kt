package io.github.konove.notmytodo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.util.ui.UIUtil
import io.github.konove.notmytodo.channel.ChannelRegistration
import io.github.konove.notmytodo.channel.ChannelServer
import io.github.konove.notmytodo.handoff.ClaudeLauncher
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.store.ItemsFilePath
import io.github.konove.notmytodo.store.StoreException
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Files
import javax.swing.JButton

/** Settings, Tools, Not My TODO. Edits a copy of the values and stores it on Apply. */
class TodoConfigurable(
    private val project: Project, private val registration: ChannelRegistration,
) : BoundConfigurable("Not My TODO") {
    /** The constructor the platform calls. */
    constructor(project: Project) : this(project, ChannelRegistration())

    internal var draft = TodoSettings.getInstance().values
    internal var itemsFile = TodoProjectSettings.getInstance(project).itemsFile

    private val bridgeStatus = JBLabel("Checking...")
    private val bridgeProblem = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        isVisible = false
    }
    private val sessions = JBLabel()
    private var channelBox: JBCheckBox? = null
    private var registered = false
    private val registerButton = JButton("Register").apply {
        isEnabled = false
        addActionListener { toggleRegistration() }
    }

    override fun createPanel(): DialogPanel = panel {
        group("Fix Handoff") {
            lateinit var channel: Cell<JBCheckBox>
            row {
                channel = checkBox("Send to a running Claude Code session (research preview)")
                    .bindSelected({ draft.channelEnabled }, { draft.channelEnabled = it })
                channel.component.addItemListener { refreshChannel() }
                channelBox = channel.component
            }
            indent {
                row("Bridge:") {
                    cell(bridgeStatus)
                    cell(registerButton)
                }
                row { cell(bridgeProblem) }
                row("Sessions:") {
                    cell(sessions)
                    link("Refresh") { refreshChannel() }
                }
                row("Start Claude with:") {
                    val command = "claude ${ClaudeLauncher.CHANNEL_FLAG}"
                    textField().columns(COLUMNS_LARGE).applyToComponent {
                        text = command
                        isEditable = false
                    }
                    button("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(command)) }
                }
                row {
                    browserLink("About channels", "https://code.claude.com/docs/en/channels")
                    comment("Claude Code asks for confirmation every time it starts with this flag.")
                }
            }.enabledIf(channel.selected)
            row("Default target:") {
                comboBox(FixTarget.entries, textListCellRenderer { it?.label })
                    .bindItem({ draft.defaultTarget }, { draft.defaultTarget = it ?: FixTarget.TERMINAL })
                    .comment("Preselected in the Fix dialog, and used by Fix All.")
            }
            row("Claude command:") {
                textField().columns(COLUMNS_LARGE)
                    .bindText({ draft.claudeCommand }, { draft.claudeCommand = it })
                    .comment("Typed into a new terminal tab, with the prompt added as the last argument. " +
                        "If it ends with an option that takes several values, end it with --.")
            }
            row {
                checkBox("Skip the Fix dialog and send to the default target")
                    .bindSelected({ draft.skipFixDialog }, { draft.skipFixDialog = it })
            }
            row("Fix All limit:") {
                spinner(1..500).bindIntValue({ draft.fixAllLimit }, { draft.fixAllLimit = it })
                    .comment("The most items sent in one prompt.")
            }
        }
        group("Prompt") {
            row("Context lines:") {
                spinner(0..200).bindIntValue({ draft.contextLines }, { draft.contextLines = it })
                    .comment("Lines of code included either side of the item's own lines.")
            }
            row {
                checkBox("Short prompt: the agent reads the item with todo_get, no code is included")
                    .bindSelected({ draft.shortPrompt }, { draft.shortPrompt = it })
                    .enabled(ClaudeLauncher.isMcpAvailable())
            }
            row("Extra instructions:") {
                textArea().rows(3).align(AlignX.FILL)
                    .bindText({ draft.extraInstructions }, { draft.extraInstructions = it })
                    .comment("Added to every prompt.")
            }
            row("Done-when phrases:") {
                textArea().rows(4).align(AlignX.FILL)
                    .bindText({ draft.donePhrases }, { draft.donePhrases = it })
                    .comment("One to a line. Offered in the Fix dialog; each should read on after \"Done when:\".")
            }
        }
        group("General") {
            row("Default priority:") {
                comboBox(Priority.entries)
                    .bindItem({ draft.defaultPriority }, { draft.defaultPriority = it ?: Priority.P2 })
                    .comment("For a new item typed without !p1 to !p3.")
            }
            row {
                checkBox("Show marks in the editor").bindSelected({ draft.editorMarks }, { draft.editorMarks = it })
            }
            row {
                checkBox("Show the Code comments entry").bindSelected({ draft.codeComments }, { draft.codeComments = it })
            }
        }
        group("This Project") {
            row("Items file:") {
                textField().columns(COLUMNS_LARGE)
                    .bindText({ itemsFile }, { itemsFile = it })
                    .comment("Relative to the project root.")
            }
        }
    }

    override fun reset() {
        draft = TodoSettings.getInstance().values
        itemsFile = TodoProjectSettings.getInstance(project).itemsFile
        super.reset()
        refreshChannel()
    }

    override fun apply() {
        super.apply()
        store(draft, itemsFile)
        refreshChannel()
    }

    /** True also after a refused Apply, which has copied the controls into [draft] without saving them. */
    override fun isModified(): Boolean =
        super.isModified() || draft != TodoSettings.getInstance().values ||
            itemsFile.trim() != TodoProjectSettings.getInstance(project).itemsFile

    /**
     * Checks and saves what the page holds. Everything is checked before anything is saved, so a
     * refusal leaves all settings as they were.
     */
    internal fun store(values: TodoSettings.Values, itemsFile: String) {
        if (values.claudeCommand.isBlank()) throw ConfigurationException("Type the command that starts Claude Code.")
        if (values.defaultTarget == FixTarget.SESSION && !values.channelEnabled) {
            throw ConfigurationException("Switch on sending to a running session before making it the default target.")
        }
        val service = TodoService.getInstance(project)
        try {
            if (itemsFile.trim() != TodoProjectSettings.getInstance(project).itemsFile) {
                val target = ItemsFilePath.resolve(service.base, itemsFile)
                val old = service.store.file
                val move = Files.exists(old) && !Files.exists(target) && Messages.showYesNoDialog(
                    project, "Move ${old.fileName} to ${itemsFile.trim()}?", "Not My TODO", null,
                ) == Messages.YES
                service.useItemsFile(itemsFile, move)
            }
        } catch (e: StoreException) {
            throw ConfigurationException(e.message)
        }
        TodoSettings.getInstance().update(values)
    }

    /** Shows the sessions at once and asks Claude Code, in the background, whether the bridge is registered. */
    private fun refreshChannel() {
        val server = project.service<ChannelServer>()
        val idle = server.withoutFlag
        sessions.text = server.error
            ?: ("${server.connected} connected to this project" + if (idle > 0) ", $idle running without the channel flag" else "")
        // Claude Code is not asked about the bridge by someone who never switched the channel on.
        if (channelBox?.isSelected != true) {
            showStatus(null)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val status = registration.status()
            ApplicationManager.getApplication().invokeLater({ if (channelBox?.isSelected == true) showStatus(status) }, ModalityState.any())
        }
    }

    private fun showStatus(status: ChannelRegistration.Status?) {
        registered = status == ChannelRegistration.Status.REGISTERED
        bridgeStatus.text = when (status) {
            ChannelRegistration.Status.REGISTERED -> "Registered"
            ChannelRegistration.Status.NOT_REGISTERED -> "Not registered"
            ChannelRegistration.Status.NO_CLAUDE -> "Claude Code not found"
            null -> ""
        }
        registerButton.text = if (registered) "Unregister" else "Register"
        registerButton.isEnabled = status != null && status != ChannelRegistration.Status.NO_CLAUDE
    }

    private fun toggleRegistration() {
        registerButton.isEnabled = false
        val remove = registered
        ApplicationManager.getApplication().executeOnPooledThread {
            val problem = try {
                if (remove) registration.unregister()
                else {
                    registration.writeLauncher()
                    registration.register()
                }
            } catch (e: IOException) {
                "The launcher could not be written: ${e.message}"
            }
            val status = registration.status()
            ApplicationManager.getApplication().invokeLater({
                bridgeProblem.text = problem.orEmpty()
                bridgeProblem.isVisible = problem != null
                showStatus(status)
            }, ModalityState.any())
        }
    }
}
