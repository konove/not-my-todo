package io.github.konove.notmytodo.ui

import com.intellij.openapi.util.text.StringUtil
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.TodoItem
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Markdown, as agents and I write it in an item's details, to HTML a Swing text pane can show. */
object Markdown {
    private val flavour = GFMFlavourDescriptor()

    fun html(text: String): String = "<html><body>" + body(text) + "</body></html>"

    /** The HTML of [text] without the tags around it, to put several texts in one page. */
    fun body(text: String): String {
        val source = keepLineBreaks(text)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        return HtmlGenerator(source, tree, flavour).generateHtml().removeSurrounding("<body>", "</body>")
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

/** What the detail pane shows under an item's facts: its details, then its comments, oldest first. */
object ItemText {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun html(item: TodoItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = StringBuilder("<html><body>")
        if (item.details.isNotBlank()) out.append(Markdown.body(item.details))
        item.comments.forEachIndexed { index, comment ->
            if (index > 0 || item.details.isNotBlank()) out.append("<hr>")
            val who = if (comment.author == Author.AGENT) "Agent" else "Me"
            out.append("<p><b>").append(StringUtil.escapeXmlEntities("$who · ${time(comment.time, zone)}")).append("</b></p>")
            out.append(Markdown.body(comment.text))
        }
        return out.append("</body></html>").toString()
    }

    /** The time in [zone], or as it was written when it is not a time. */
    private fun time(written: String, zone: ZoneId): String = try {
        timeFormat.format(Instant.parse(written).atZone(zone))
    } catch (_: DateTimeParseException) {
        written
    }
}
