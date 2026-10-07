package io.github.konove.notmytodo.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** Markdown, as agents and I write it in an item's details, to HTML a Swing text pane can show. */
object Markdown {
    private val flavour = GFMFlavourDescriptor()

    fun html(text: String): String {
        val source = keepLineBreaks(text)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        return "<html>" + HtmlGenerator(source, tree, flavour).generateHtml() + "</html>"
    }

    /**
     * Ends every line outside a code fence with two spaces, which Markdown reads as a line break.
     * Details are written like comments on a code host: a new line is meant as one.
     */
    private fun keepLineBreaks(text: String): String {
        var fenced = false
        return text.lines().joinToString("\n") { line ->
            val fence = line.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
            if (fence) fenced = !fenced
            if (fenced || fence || line.isBlank()) line else line.trimEnd() + "  "
        }
    }
}
