package io.github.konove.notmytodo.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.BrowserHyperlinkListener
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
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

    fun html(text: String, folds: Folds? = null, highlight: Highlight? = null): String = "<html><body>" + body(text, folds, highlight) + "</body></html>"

    /**
     * The HTML of [text] without the tags around it, to put several texts in one page.
     * With [folds], each data-policy block is shown as one line that opens it.
     * With [highlight], a code block whose fence names a language is coloured by it.
     */
    fun body(text: String, folds: Folds? = null, highlight: Highlight? = null): String {
        if (folds == null) return render(text, highlight)
        val lines = text.lines()
        val out = StringBuilder()
        var from = 0
        for (block in policyBlocks(lines)) {
            out.append(render(lines.subList(from, block.first).joinToString("\n"), highlight))
            out.append(folds.fold(policyName(lines[block.first + 1]), render(lines.slice(block).joinToString("\n"), highlight)))
            from = block.last + 1
        }
        return out.append(render(lines.subList(from, lines.size).joinToString("\n"), highlight)).toString()
    }

    private fun render(text: String, highlight: Highlight?): String {
        if (text.isBlank()) return ""
        val source = keepLineBreaks(text)
        val tree = MarkdownParser(flavour, true, CancellationToken.NonCancellable).buildMarkdownTreeFromString(source as CharSequence)
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, source), null)
            .let { if (highlight == null) it else it + (MarkdownElementTypes.CODE_FENCE to Fence(highlight, it.getValue(MarkdownElementTypes.CODE_FENCE))) }
        return HtmlGenerator(source, tree, providers).generateHtml().removeSurrounding("<body>", "</body>")
    }

    /** The HTML of a code block in [language], from `<pre>` to `</pre>`, or null to leave it plain. */
    fun interface Highlight {
        fun html(language: String, code: String): String?
    }

    /** A code block whose fence names a language, as [highlight] colours it; any other, as [plain] writes it. */
    private class Fence(private val highlight: Highlight, private val plain: GeneratingProvider) : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(text)?.trim()?.split(' ')?.first()
            val html = language?.let { highlight.html(it, code(text, node)) }
            if (html == null) plain.processNode(visitor, text, node) else visitor.consumeHtml(html)
        }

        /** The text between the fences as written, without the indent of the opening one. */
        private fun code(text: String, node: ASTNode): String {
            val indent = node.getTextInNode(text).commonPrefixWith(" ".repeat(10)).length
            return node.children.takeWhile { it.type != MarkdownTokenTypes.CODE_FENCE_END }
                .dropWhile { it.type != MarkdownTokenTypes.EOL }.drop(1)
                .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
                .joinToString("") { HtmlGenerator.trimIndents(it.getTextInNode(text), indent) }
        }
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
 * What the detail pane shows under an item's title and chips: where it came from and the commit that fixed it,
 * what I have to decide and what I have decided, how it was fixed, its details, then its comments, oldest first.
 * The facts are a table; each part after them is a section with a heading, but for details with nothing above them.
 */
object ItemText {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    // A full hash, or a short one with a letter in it: a number or a date without dashes is no commit.
    private const val HASH = "(?:[0-9a-f]{40}|(?=[0-9]*[a-f])[0-9a-f]{7,39})"
    private val commits = Regex("$HASH(\\.\\.\\.?$HASH)?", RegexOption.IGNORE_CASE)
    // Brackets after a space: those of a call, such as parse(String), are part of what they follow.
    private val aside = Regex("(.*\\S)\\s+\\(([^()]+)\\)")
    private val dated = Regex("(.*\\S)\\s+(\\d{4}-\\d{2}-\\d{2})")

    /** The `<icon src>` of what the agent and I wrote: icons any IntelliJ HTML pane can show, which [MarkdownPane] draws as badges. */
    const val AGENT_ICON = "AllIcons.Actions.Lightning"
    const val MY_ICON = "AllIcons.General.User"

    /**
     * [grey] is the HTML colour of what is said about the text, as against the text.
     * [open] are the numbers of the data-policy blocks shown in full, counted through the whole page.
     * [highlight] colours the code blocks whose fence names a language.
     */
    fun html(
        item: TodoItem,
        zone: ZoneId = ZoneId.systemDefault(),
        grey: String = "#6C707E",
        open: Set<Int> = emptySet(),
        highlight: Markdown.Highlight? = null,
    ): String {
        val folds = Markdown.Folds(open, grey)
        fun markdown(text: String) = Markdown.body(text, folds, highlight)
        val out = StringBuilder("<html><body>")
        val start = out.length
        // A heading with a gap over it, but for the first thing on the page.
        fun section(title: String) {
            val gap = if (out.length == start) " style=\"margin-top: 0\"" else ""
            out.append("<p class=\"section\"$gap><b>$title</b></p>")
        }
        facts(out, item, grey)
        item.toDecide?.let {
            section(if (item.needsDecision) "To decide" else "Was to decide")
            out.append(markdown(it))
        }
        if (item.decisions.isNotEmpty()) section("Decided · ${item.decisions.size}")
        fun stamp(at: String) = "<font color=\"$grey\">${StringUtil.escapeXmlEntities(time(at, zone))}</font>"
        item.decisions.forEach { entry(out, MY_ICON, stamp(it.time), decision(it, grey, ::markdown)) }
        item.resolution?.let {
            section(if (item.status == Status.WONT_FIX) "Closed" else "Resolution")
            out.append(markdown(it))
        }
        if (item.details.isNotBlank()) {
            if (out.length != start) section("Details")
            out.append(markdown(item.details))
        }
        if (item.comments.isNotEmpty()) section("Comments · ${item.comments.size}")
        item.comments.forEach { comment ->
            val agent = comment.author == Author.AGENT
            val head = "<b>${if (agent) "Agent" else "Me"}</b>&nbsp;&nbsp;${stamp(comment.time)}"
            entry(out, if (agent) AGENT_ICON else MY_ICON, head, markdown(comment.text))
        }
        return out.append("</body></html>").toString()
    }

    /** Where the item came from, where it was moved from and the commit that fixed or closed it, as a table of labels and values. */
    private fun facts(out: StringBuilder, item: TodoItem, grey: String) {
        val rows = buildList {
            item.source?.let { add("From" to source(it, grey)) }
            item.movedFrom?.let { add("Moved from" to StringUtil.escapeXmlEntities(it)) }
            item.fixedIn?.let { add((if (item.status == Status.WONT_FIX) "Closed in" else "Fixed in") to "<code>${StringUtil.escapeXmlEntities(it)}</code>") }
        }
        if (rows.isEmpty()) return
        out.append("<table class=\"facts\">")
        rows.forEach { (label, value) -> out.append("<tr><td class=\"label\">$label</td><td>$value</td></tr>") }
        out.append("</table>")
    }

    /**
     * A commit or a range of them as code. Otherwise a trailing date goes grey after a dot, and what is
     * in brackets at the end goes on a grey line of its own: `TODO restudy 2026-10-17 (sessions 2026-10-09..17)`.
     */
    private fun source(text: String, grey: String): String {
        if (commits.matches(text)) return "<code>${StringUtil.escapeXmlEntities(text)}</code>"
        val bracketed = aside.matchEntire(text)
        val what = bracketed?.groupValues?.get(1) ?: text
        val head = dated.matchEntire(what)?.let {
            "${StringUtil.escapeXmlEntities(it.groupValues[1])} <font color=\"$grey\">· ${it.groupValues[2]}</font>"
        } ?: StringUtil.escapeXmlEntities(what)
        val tail = bracketed?.let { "<br><font color=\"$grey\">${StringUtil.escapeXmlEntities(it.groupValues[2])}</font>" }.orEmpty()
        return head + tail
    }

    /** A comment or a decision: the badge of who wrote it beside [head], and [body] under the head. */
    private fun entry(out: StringBuilder, badge: String, head: String, body: String) {
        out.append("<table class=\"entry\" width=\"100%\"><tr><td class=\"badge\" valign=\"top\"><icon src=\"$badge\"></td><td>$head</td></tr>")
        out.append("<tr><td></td><td>$body</td></tr></table>")
    }

    /** The question, the answers I was offered with mine in bold, and my answer in full when it was none of them. */
    private fun decision(decision: Decision, grey: String, markdown: (String) -> String): String {
        val out = StringBuilder(markdown(decision.question))
        val chosen = decision.options.firstOrNull { it.equals(decision.answer, ignoreCase = true) }
        if (decision.options.isNotEmpty()) {
            out.append("<ul>")
            decision.options.forEach { option ->
                val text = StringUtil.escapeXmlEntities(option)
                out.append(if (option == chosen) "<li><b>$text</b></li>" else "<li><font color=\"$grey\">$text</font></li>")
            }
            out.append("</ul>")
        }
        if (chosen == null) out.append(markdown(decision.answer))
        return out.toString()
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
