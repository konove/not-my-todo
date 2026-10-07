package io.github.konove.notmytodo.ui

import com.intellij.openapi.util.text.StringUtil
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Status
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

/**
 * What the detail pane shows under an item's facts: what I have to decide and what I have decided,
 * where it came from, how it was fixed, its details, then its comments, oldest first.
 */
object ItemText {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** [grey] is the HTML colour of what is said about a comment, as against what the comment says. */
    fun html(item: TodoItem, zone: ZoneId = ZoneId.systemDefault(), grey: String = "#6C707E"): String {
        val out = StringBuilder("<html><body>")
        item.toDecide?.let {
            val head = if (item.needsDecision) "To decide" else "Was to decide"
            out.append("<p><font color=\"$grey\"><b>$head</b></font></p>")
            out.append(Markdown.body(it))
        }
        if (item.decisions.isNotEmpty()) {
            val gap = if (item.toDecide != null) 14 else 0
            out.append("<p style=\"margin-top: ${gap}px\"><font color=\"$grey\"><b>Decided · ${item.decisions.size}</b></font></p>")
        }
        item.decisions.forEach { decision(out, it, zone, grey) }
        val decided = item.toDecide != null || item.decisions.isNotEmpty()
        if (decided && item.source != null) out.append("<p style=\"margin-top: 14px\"></p>")
        item.source?.let { out.append("<p><font color=\"$grey\">From ${StringUtil.escapeXmlEntities(it)}</font></p>") }
        if (item.fixedIn != null || item.resolution != null) {
            val commit = item.fixedIn?.let { " in ${StringUtil.escapeXmlEntities(it)}" }.orEmpty()
            val head = if (item.status == Status.WONT_FIX) "Closed" else "Fixed"
            out.append("<p><font color=\"$grey\"><b>$head$commit</b></font></p>")
            item.resolution?.let { out.append(Markdown.body(it)) }
        }
        val above = decided || item.source != null || item.fixedIn != null || item.resolution != null
        if (item.details.isNotBlank()) {
            if (above) out.append("<p style=\"margin-top: 14px\"><font color=\"$grey\"><b>Details</b></font></p>")
            out.append(Markdown.body(item.details))
        }
        if (item.comments.isNotEmpty()) {
            val gap = if (item.details.isNotBlank() || above) 14 else 0
            out.append("<p style=\"margin-top: ${gap}px\"><font color=\"$grey\"><b>Comments · ${item.comments.size}</b></font></p>")
        }
        item.comments.forEach { comment ->
            val who = if (comment.author == Author.AGENT) "Agent" else "Me"
            // The icons of Fix with Claude and of Needs My Decision: the same two parties.
            val mark = if (comment.author == Author.AGENT) "AllIcons.Actions.Lightning" else "AllIcons.General.User"
            val time = StringUtil.escapeXmlEntities(time(comment.time, zone))
            out.append("<p style=\"margin-top: 10px\"><icon src=\"$mark\">&nbsp;<b>$who</b>&nbsp;&nbsp;<font color=\"$grey\">$time</font></p>")
            out.append(Markdown.body(comment.text))
        }
        return out.append("</body></html>").toString()
    }

    /** The question, the answers I was offered with mine in bold, and my answer in full when it was none of them. */
    private fun decision(out: StringBuilder, decision: Decision, zone: ZoneId, grey: String) {
        val time = StringUtil.escapeXmlEntities(time(decision.time, zone))
        out.append("<p style=\"margin-top: 10px\"><icon src=\"AllIcons.General.User\">&nbsp;<font color=\"$grey\">$time</font></p>")
        out.append(Markdown.body(decision.question))
        val chosen = decision.options.firstOrNull { it.equals(decision.answer, ignoreCase = true) }
        if (decision.options.isNotEmpty()) {
            out.append("<ul>")
            decision.options.forEach { option ->
                val text = StringUtil.escapeXmlEntities(option)
                out.append(if (option == chosen) "<li><b>$text</b></li>" else "<li><font color=\"$grey\">$text</font></li>")
            }
            out.append("</ul>")
        }
        if (chosen == null) out.append(Markdown.body(decision.answer))
    }

    /** The time in [zone], or as it was written when it is not a time. */
    private fun time(written: String, zone: ZoneId): String = try {
        timeFormat.format(Instant.parse(written).atZone(zone))
    } catch (_: DateTimeParseException) {
        written
    }
}

/** A project path cut down to what tells files apart: where it starts, the last directory and the name. */
object PathText {
    /** The directories, shortened and ending in a slash, and the file name. */
    fun split(path: String): Pair<String, String> {
        val parts = path.split('/')
        val dirs = parts.dropLast(1)
        val shown = if (dirs.size > KEPT + 1) dirs.take(KEPT) + "…" + dirs.last() else dirs
        return shown.joinToString("") { "$it/" } to parts.last()
    }

    private const val KEPT = 3
}
