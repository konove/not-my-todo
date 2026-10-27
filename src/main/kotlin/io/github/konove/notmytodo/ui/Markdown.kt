package io.github.konove.notmytodo.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.BrowserHyperlinkListener
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

/** Markdown, as agents and I write it in an item's details, to HTML a Swing text pane can show. */
object Markdown {
    private val flavour = GFMFlavourDescriptor()
    private val policyTag = Regex("^\\s*<data(?:Source|Policy):([^>\\s]+)>")

    fun html(text: String, folds: Folds? = null): String = "<html><body>" + body(text, folds) + "</body></html>"

    /**
     * The HTML of [text] without the tags around it, to put several texts in one page.
     * With [folds], each data-policy block is shown as one line that opens it.
     */
    fun body(text: String, folds: Folds? = null): String {
        if (folds == null) return render(text)
        val lines = text.lines()
        val out = StringBuilder()
        var from = 0
        for (block in policyBlocks(lines)) {
            out.append(render(lines.subList(from, block.first).joinToString("\n")))
            out.append(folds.fold(policyName(lines[block.first + 1]), render(lines.slice(block).joinToString("\n"))))
            from = block.last + 1
        }
        return out.append(render(lines.subList(from, lines.size).joinToString("\n"))).toString()
    }

    private fun render(text: String): String {
        if (text.isBlank()) return ""
        val source = keepLineBreaks(text)
        val tree = MarkdownParser(flavour, true, CancellationToken.NonCancellable).buildMarkdownTreeFromString(source as CharSequence)
        return HtmlGenerator(source, tree, flavour).generateHtml().removeSurrounding("<body>", "</body>")
    }

    /**
     * The code fences, from the opening line to the closing one, whose first line is a `<dataSource:…>` or
     * `<dataPolicy:…>` tag: the wrapper some MCP servers put around their data and ask to keep with it, about 1 KB each time.
     */
    private fun policyBlocks(lines: List<String>): List<IntRange> {
        val blocks = ArrayList<IntRange>()
        var open = -1
        lines.forEachIndexed { index, line ->
            if (!isFence(line)) return@forEachIndexed
            if (open < 0) {
                open = index
                return@forEachIndexed
            }
            if (open + 1 < index && policyTag.containsMatchIn(lines[open + 1])) blocks += open..index
            open = -1
        }
        return blocks
    }

    /** Chat for `<dataSource:chat>`. */
    private fun policyName(line: String): String =
        policyTag.find(line)!!.groupValues[1].replaceFirstChar { it.uppercaseChar() }

    private fun isFence(line: String): Boolean = line.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }

    /**
     * Numbers the data-policy blocks of one page in the order they are shown and opens those in [open].
     * The line of each is a link to [SCHEME] and its number, which the pane takes to open or close it.
     */
    class Folds(private val open: Set<Int>, private val grey: String) {
        private var next = 0

        internal fun fold(name: String, block: String): String {
            val index = next++
            val shown = index in open
            val mark = if (shown) "▾" else "▸"
            val line = "<p><a href=\"$SCHEME$index\"><font color=\"$grey\">$mark ${StringUtil.escapeXmlEntities(name)} data policy</font></a></p>"
            return if (shown) line + block else line
        }

        companion object {
            const val SCHEME = "notmytodo-policy:"

            /** The number of the block a link opens or closes, or null for any other link. */
            fun index(href: String?): Int? = href?.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.toIntOrNull()

            /**
             * Calls [toggle] with the number of the block whose line was clicked and where that line starts in the text,
             * and opens every other link in the browser.
             */
            fun listener(toggle: (index: Int, offset: Int) -> Unit) = HyperlinkListener { e ->
                val index = index(e.description)
                if (index == null) BrowserHyperlinkListener.INSTANCE.hyperlinkUpdate(e)
                else if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) toggle(index, e.sourceElement?.startOffset ?: 0)
            }

            /** [open] with [index] opened, or closed when it was open. */
            fun toggled(open: Set<Int>, index: Int): Set<Int> = if (index in open) open - index else open + index
        }
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

    /**
     * [grey] is the HTML colour of what is said about a comment, as against what the comment says.
     * [open] are the numbers of the data-policy blocks shown in full, counted through the whole page.
     */
    fun html(item: TodoItem, zone: ZoneId = ZoneId.systemDefault(), grey: String = "#6C707E", open: Set<Int> = emptySet()): String {
        val folds = Markdown.Folds(open, grey)
        val out = StringBuilder("<html><body>")
        item.toDecide?.let {
            val head = if (item.needsDecision) "To decide" else "Was to decide"
            out.append("<p><font color=\"$grey\"><b>$head</b></font></p>")
            out.append(Markdown.body(it, folds))
        }
        if (item.decisions.isNotEmpty()) {
            val gap = if (item.toDecide != null) 14 else 0
            out.append("<p style=\"margin-top: ${gap}px\"><font color=\"$grey\"><b>Decided · ${item.decisions.size}</b></font></p>")
        }
        item.decisions.forEach { decision(out, it, zone, grey, folds) }
        val decided = item.toDecide != null || item.decisions.isNotEmpty()
        if (decided && (item.source != null || item.movedFrom != null)) out.append("<p style=\"margin-top: 14px\"></p>")
        item.source?.let { out.append("<p><font color=\"$grey\">From ${StringUtil.escapeXmlEntities(it)}</font></p>") }
        item.movedFrom?.let { out.append("<p><font color=\"$grey\">Moved from ${StringUtil.escapeXmlEntities(it)}</font></p>") }
        if (item.fixedIn != null || item.resolution != null) {
            val commit = item.fixedIn?.let { " in ${StringUtil.escapeXmlEntities(it)}" }.orEmpty()
            val head = if (item.status == Status.WONT_FIX) "Closed" else "Fixed"
            out.append("<p><font color=\"$grey\"><b>$head$commit</b></font></p>")
            item.resolution?.let { out.append(Markdown.body(it, folds)) }
        }
        val above = decided || item.source != null || item.movedFrom != null || item.fixedIn != null || item.resolution != null
        if (item.details.isNotBlank()) {
            if (above) out.append("<p style=\"margin-top: 14px\"><font color=\"$grey\"><b>Details</b></font></p>")
            out.append(Markdown.body(item.details, folds))
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
            out.append(Markdown.body(comment.text, folds))
        }
        return out.append("</body></html>").toString()
    }

    /** The question, the answers I was offered with mine in bold, and my answer in full when it was none of them. */
    private fun decision(out: StringBuilder, decision: Decision, zone: ZoneId, grey: String, folds: Markdown.Folds) {
        val time = StringUtil.escapeXmlEntities(time(decision.time, zone))
        out.append("<p style=\"margin-top: 10px\"><icon src=\"AllIcons.General.User\">&nbsp;<font color=\"$grey\">$time</font></p>")
        out.append(Markdown.body(decision.question, folds))
        val chosen = decision.options.firstOrNull { it.equals(decision.answer, ignoreCase = true) }
        if (decision.options.isNotEmpty()) {
            out.append("<ul>")
            decision.options.forEach { option ->
                val text = StringUtil.escapeXmlEntities(option)
                out.append(if (option == chosen) "<li><b>$text</b></li>" else "<li><font color=\"$grey\">$text</font></li>")
            }
            out.append("</ul>")
        }
        if (chosen == null) out.append(Markdown.body(decision.answer, folds))
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
