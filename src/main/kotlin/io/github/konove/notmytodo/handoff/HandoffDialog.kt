package io.github.konove.notmytodo.handoff

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import io.github.konove.notmytodo.ide.CodeTodos
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.settings.FixTarget
import io.github.konove.notmytodo.settings.TodoConfigurable
import io.github.konove.notmytodo.settings.TodoSettings
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * Asks where the prompt for one item goes. A "done when" line can be added, from ready-made
 * phrases or typed, and the prompt can be opened and read before it is sent.
 */
class HandoffDialog(
    private val project: Project,
    private val item: TodoItem,
    private val fileText: String?,
    private val availability: TargetChoice.Availability,
    wanted: FixTarget,
    private val options: PromptOptions = PromptOptions(),
) : DialogWrapper(project) {
    private val doneWhen = JBTextField().apply {
        emptyText.text = "Optional. Type a condition, or pick from the phrases below"
    }
    private val chosen = TargetChoice.choose(wanted, availability).target

    // The running session is offered only when the channel is switched on; the others always are.
    private val targets: Map<FixTarget, JBRadioButton> = FixTarget.entries
        .filter { it != FixTarget.SESSION || availability.channelOn }
        .associateWith { target ->
            JBRadioButton(target.label, target == chosen).apply {
                isEnabled = TargetChoice.reason(target, availability) == null
            }
        }
    private val phrasePanel = JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(12), 0)).apply { isOpaque = false }
    private var phraseBoxes: Map<String, JBCheckBox> = emptyMap()
    private val preview = JBTextArea(14, 80).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val skipBox = JBCheckBox("Don't ask next time, send straight there").apply {
        toolTipText = "Switch the dialog back on in Settings | Tools | Not My TODO."
    }

    val prompt: String get() = PromptBuilder.build(item, fileText, doneWhen.text, options)
    val target: FixTarget get() = targets.entries.first { it.value.isSelected }.key

    /** True when the dialog is to be skipped from now on, and prompts sent to [target]. */
    val skipNextTime: Boolean get() = skipBox.isSelected

    /** What the confirming button says. */
    val sendText: String get() = target.action

    /** The phrases offered, and whether each is in the "done when" text now. */
    internal val phrases: Map<String, Boolean> get() = phraseBoxes.mapValues { it.value.isSelected }

    internal var doneWhenText: String
        get() = doneWhen.text
        set(value) {
            doneWhen.text = value
        }

    init {
        title = "Fix ${item.id} with Claude"
        ButtonGroup().apply { targets.values.forEach(::add) }
        targets.values.forEach { it.addActionListener { setOKButtonText(sendText) } }
        setOKButtonText(sendText)
        doneWhen.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = showPrompt()
        })
        showPhrases()
        init()
        showPrompt()
    }

    private fun showPrompt() {
        preview.text = prompt
        preview.caretPosition = 0
        for ((phrase, box) in phraseBoxes) box.isSelected = DonePhrases.has(doneWhen.text, phrase)
    }

    /** Fills the row of phrases from the settings; they may have been edited while the dialog is open. */
    private fun showPhrases() {
        phraseBoxes = TodoSettings.getInstance().values.donePhraseList().associateWith { phrase ->
            JBCheckBox(phrase, DonePhrases.has(doneWhen.text, phrase)).apply {
                isOpaque = false
                addActionListener { toggle(phrase) }
            }
        }
        phrasePanel.removeAll()
        phraseBoxes.values.forEach(phrasePanel::add)
        phrasePanel.revalidate()
        phrasePanel.repaint()
    }

    internal fun toggle(phrase: String) {
        doneWhen.text = DonePhrases.toggle(doneWhen.text, phrase)
    }

    private fun editPhrases() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, TodoConfigurable::class.java)
        showPhrases()
        fitWindow()
    }

    /** The window follows its content when the prompt is opened or closed, or the phrases change. */
    private fun fitWindow() = SwingUtilities.invokeLater { if (!isDisposed) window?.pack() }

    override fun getPreferredFocusedComponent(): JComponent = doneWhen

    override fun createSouthAdditionalPanel(): JPanel = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { add(skipBox) }

    override fun createCenterPanel(): JComponent = panel {
        row { text("<b>${StringUtil.escapeXmlEntities(item.title)}</b>", maxLineLength = 80) }
        if (!CodeTodos.isCode(item)) {
            row { comment((listOf(item.id, item.priority.json) + item.tags.map { "#$it" }).joinToString(" · ")) }
        }
        buttonsGroup("Send to:") {
            for ((target, button) in targets) {
                row { cell(button).comment(TargetChoice.reason(target, availability) ?: target.hint) }
            }
        }
        row("Done when:") { cell(doneWhen).align(AlignX.FILL) }
        row("") {
            cell(phrasePanel).align(AlignX.FILL).resizableColumn()
            link("Edit phrases...") { editPhrases() }
        }
        collapsibleGroup("Prompt") {
            row { cell(JBScrollPane(preview)).align(Align.FILL) }.resizableRow()
        }.apply {
            expanded = PropertiesComponent.getInstance().getBoolean(PROMPT_OPEN_KEY, false)
            addExpandedListener {
                PropertiesComponent.getInstance().setValue(PROMPT_OPEN_KEY, it, false)
                fitWindow()
            }
        }.resizableRow()
    }

    private companion object {
        const val PROMPT_OPEN_KEY = "notmytodo.handoff.promptOpen"
    }
}
