package io.github.konove.notmytodo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.konove.notmytodo.capture.CaptureParser
import io.github.konove.notmytodo.ide.StoreView
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreException
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** One-line capture with inline `!p1 #tag` syntax. With no anchor it saves a plain note. */
class CaptureDialog(
    private val project: Project, private var anchor: Anchor?, initialTitle: String = "",
) : DialogWrapper(project) {
    private val titleField = JBTextField(45)
    private val defaultPriority = TodoSettings.getInstance().values.defaultPriority
    private val chips = JBLabel(chipText("", defaultPriority))
    private val anchorLabel = JBLabel()
    private val removeAnchor = ActionLink("Remove anchor") {
        anchor = null
        showAnchor()
    }
    private val detailsArea = JBTextArea(4, 45).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val view = StoreView.getInstance(project)

    /**
     * Where a plain note goes: this project, Inbox (offered before it is made) or another. An anchored
     * item stays in this project, whose code it is on.
     */
    internal val projectBox = ComboBox((listOf(view.home, Store.INBOX) + view.keys()).distinct().toTypedArray()).apply {
        selectedItem = view.home
        renderer = com.intellij.ui.SimpleListCellRenderer.create("") { key -> if (key == view.home) "$key (this project)" else key }
    }

    /** The row of [projectBox], hidden with it while there is an anchor. */
    private lateinit var projectRow: com.intellij.ui.dsl.builder.Row

    init {
        title = "New TODO"
        setOKButtonText("Save")
        titleField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                chips.text = chipText(titleField.text, defaultPriority)
                if (CaptureParser.parse(titleField.text)?.inbox == true) projectBox.selectedItem = Store.INBOX
            }
        })
        titleField.text = initialTitle
        init()
        showAnchor()
    }

    private fun showAnchor() {
        val current = anchor
        anchorLabel.text = if (current == null) "Plain note, not attached to code" else anchorText(current)
        removeAnchor.isVisible = current != null
        projectRow.visible(current == null)
        if (current != null) projectBox.selectedItem = view.home
    }

    override fun getPreferredFocusedComponent(): JComponent = titleField

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(titleField).align(AlignX.FILL).comment("Type !p1 to !p3 and #tags inline.")
        }
        row { cell(chips) }
        row {
            cell(anchorLabel)
            cell(removeAnchor)
        }
        projectRow = row("Project:") { cell(projectBox) }
        row("Details:") {
            cell(JBScrollPane(detailsArea)).align(AlignX.FILL)
        }
    }

    override fun doValidate(): ValidationInfo? = refusal()?.let { ValidationInfo(it, titleField) }

    /** Fills the title as if typed: for tests. */
    internal fun typeTitle(text: String) {
        titleField.text = text
    }

    /** Why the dialog cannot save what it holds; null when it can. */
    internal fun refusal(): String? {
        val captured = CaptureParser.parse(titleField.text) ?: return "Type a title"
        if (captured.inbox && anchor != null) return "An item attached to code stays in this project. Remove the anchor to put it in Inbox."
        return null
    }

    /** Saves the item; false when it was refused, and the dialog stays open. */
    internal fun save(): Boolean {
        val captured = CaptureParser.parse(titleField.text) ?: return false
        if (refusal() != null) return false
        val key = if (anchor != null) view.home else projectBox.selectedItem as String
        try {
            view.create(
                key,
                Draft(
                    title = captured.title,
                    details = detailsArea.text.trim(),
                    priority = captured.priority ?: defaultPriority,
                    tags = captured.tags,
                    anchors = listOfNotNull(anchor),
                ),
            )
        } catch (e: StoreException) {
            Messages.showErrorDialog(project, e.message, "Not My TODO")
            return false
        }
        return true
    }

    override fun doOKAction() {
        if (save()) super.doOKAction()
    }

    companion object {
        fun chipText(input: String, default: Priority = Priority.P2): String {
            val captured = CaptureParser.parse(input)
            val priority = captured?.priority?.json?.uppercase() ?: "${default.json.uppercase()} (default)"
            return (listOf(priority) + captured?.tags.orEmpty().map { "#$it" }).joinToString("   ")
        }

        fun anchorText(anchor: Anchor): String {
            val name = anchor.path.substringAfterLast('/')
            return if (anchor.isFile) name else "$name:${anchor.linesText("–")}"
        }
    }
}
