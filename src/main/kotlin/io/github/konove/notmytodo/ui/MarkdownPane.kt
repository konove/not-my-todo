package io.github.konove.notmytodo.ui

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.lang.documentation.DocumentationSettings
import com.intellij.lang.documentation.QuickDocHighlightingHelper
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.util.IconUtil
import com.intellij.util.ui.ExtendableHTMLViewFactory
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JEditorPane
import javax.swing.text.html.StyleSheet

/**
 * The pane an item's text is shown in: the one Quick Documentation uses, which draws code on a rounded ground,
 * with the sections, facts and comments of [ItemText] laid out on it.
 */
object MarkdownPane {
    /** What is said about the text rather than the text: labels, headings and times. Darker in a dark theme is too faint to read. */
    val grey: Color = JBColor(Color(0x6C707E), Color(0x9DA0A8))

    private val agentGround = JBColor(Color(0xE7EEFD), Color(0x25324D))
    private val agentMark = JBColor(Color(0x3574F0), Color(0x6E9CF7))
    private val myGround = JBColor(Color(0xEBECF0), Color(0x393B40))

    /**
     * Typed as the editor pane it is, so that only this file uses the experimental API of [JBHtmlPane].
     * The pane loads its style sheets again, with the colours of the new theme, when the theme changes.
     */
    fun create(): JEditorPane = JBHtmlPane(
        JBHtmlPaneStyleConfiguration {
            spaceBeforeParagraph(0)
            spaceAfterParagraph(8)
        },
        JBHtmlPaneConfiguration.Builder()
            // A long path, link or hash breaks where it must instead of widening the pane.
            .extensions(ExtendableHTMLViewFactory.Extensions.WORD_WRAP)
            .customStyleSheetProvider { StyleSheet().apply { addRule(css()) } }
            .iconResolver(::icon)
            .build(),
    ).apply {
        isEditable = false
        isOpaque = false
        border = null
    }

    /**
     * A code block as Quick Documentation shows one in [language], found by its id, name or the extension of its
     * files (xml, cpp, json), or null when no language goes by that name.
     * The panel refreshes often, so the languages it has looked up and the blocks it has coloured are kept
     * until the editor's colours or the settings of Quick Documentation change.
     * Colouring parses the code into a file, which takes a read action even on the EDT.
     */
    fun highlighter(project: Project): Markdown.Highlight {
        var look: List<Any?>? = null
        val languages = HashMap<String, Language?>()
        val done = object : LinkedHashMap<Pair<String, String>, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Pair<String, String>, String>) = size > 32
        }
        return Markdown.Highlight { name, code ->
            val colors = EditorColorsManager.getInstance()
            val now = listOf(
                colors.globalScheme, colors.schemeModificationCounter,
                DocumentationSettings.isHighlightingOfCodeBlocksEnabled(), DocumentationSettings.isCodeBackgroundEnabled(),
                DocumentationSettings.getHighlightingSaturation(false), DocumentationSettings.getHighlightingSaturation(true),
            )
            if (now != look) {
                look = now
                languages.clear()
                done.clear()
            }
            languages.getOrPut(name) { QuickDocHighlightingHelper.guessLanguage(name) }?.let { language ->
                done.getOrPut(name to code) {
                    runReadActionBlocking { QuickDocHighlightingHelper.getStyledCodeBlock(project, language, code.removeSuffix("\n")) }
                }
            }
        }
    }

    /**
     * The page [render] makes with [highlight], or without it when coloured code makes it longer than [JBHtmlPane]
     * takes without writing the start of the text to the log.
     */
    fun fit(highlight: Markdown.Highlight?, render: (Markdown.Highlight?) -> String): String =
        highlight?.let(render)?.takeIf { it.length <= LONGEST } ?: render(null)

    private const val LONGEST = 50_000

    /**
     * The badge of an `<icon src>` that [ItemText] writes for the agent or for me: the icons of Fix with Claude and
     * of Needs My Decision, the same two parties. Another HTML pane shows them as they are.
     */
    fun icon(name: String): Icon? = when (name) {
        ItemText.AGENT_ICON -> agentBadge
        ItemText.MY_ICON -> myBadge
        else -> null
    }

    private val agentBadge = Badge(AllIcons.Actions.Lightning, agentGround, agentMark)
    private val myBadge = Badge(AllIcons.General.User, myGround, grey)

    private fun css(): String {
        val grey = ColorUtil.toHtmlColor(grey)
        val rule = ColorUtil.toHtmlColor(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        return """
            p.section { color: $grey; margin-top: 14px; margin-bottom: 8px; padding-bottom: 4px; border-bottom: 1px solid $rule }
            table.facts td { padding: 0 12px 4px 0 }
            td.label { color: $grey }
            table.entry { margin-top: 4px }
            table.entry td { padding: 0 0 2px 0 }
            td.badge { width: ${Badge.SIZE}px; padding-right: 8px }
            li { padding-top: 0; padding-bottom: 3px }
        """.trimIndent()
    }

    /** [mark] in [tint] on a round ground of [ground]. */
    private class Badge(private val mark: Icon, private val ground: Color, private val tint: Color) : Icon {
        // The small mark is made again only when the theme or the scale changes. It is scaled as the chips' are,
        // without a component, so that it is the same size in a pane on any screen.
        private var inner: Icon? = null
        private var innerFor: Pair<Int, Int>? = null

        override fun getIconWidth() = JBUI.scale(SIZE)

        override fun getIconHeight() = JBUI.scale(SIZE)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ground
                g2.fillOval(x, y, iconWidth, iconHeight)
                val key = tint.rgb to iconWidth
                val inner = inner.takeIf { innerFor == key } ?: IconUtil.colorize(IconUtil.scale(mark, null, 0.75f), tint).also { inner = it; innerFor = key }
                inner.paintIcon(c, g2, x + (iconWidth - inner.iconWidth) / 2, y + (iconHeight - inner.iconHeight) / 2)
            } finally {
                g2.dispose()
            }
        }

        companion object {
            const val SIZE = 20
        }
    }
}
