package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem

/**
 * How a prompt is written. [contextLines] is the code shown either side of the item's lines.
 * With [short] the agent is sent to `todo_get` and no code is embedded. [extra] is appended once
 * to every prompt. [itemsFile] is named as the fallback for an agent without the tools.
 */
data class PromptOptions(
    val contextLines: Int = 20,
    val short: Boolean = false,
    val extra: String = "",
    val itemsFile: String = ".todos/items.json",
)

/** Builds the text handed to an agent for one item. */
object PromptBuilder {
    fun build(item: TodoItem, fileText: String?, doneWhen: String, options: PromptOptions = PromptOptions()): String =
        one(item, fileText, doneWhen, options, options.extra)

    /** One prompt for several items, each with its own instructions. */
    fun buildAll(items: List<Pair<TodoItem, String?>>, options: PromptOptions = PromptOptions()): String {
        val extra = options.extra.trim()
        return "Fix the following ${items.size} TODO items, one at a time.\n\n" +
            (if (extra.isEmpty()) "" else "$extra\n\n") +
            items.joinToString("\n---\n\n") { (item, fileText) -> one(item, fileText, "", options, "") }
    }

    private fun one(item: TodoItem, fileText: String?, doneWhen: String, options: PromptOptions, extra: String): String {
        val out = StringBuilder()
        // A TODO comment found in the code is not in the store, so there is no item to report on or to read.
        val comment = ItemId.parse(item.id) == null
        if (comment) {
            out.append("Resolve this TODO comment in the code.\n\n")
            out.append("Comment: ${item.title}\n")
        } else {
            val tags = if (item.tags.isEmpty()) "" else ", tags: ${item.tags.joinToString(", ")}"
            val opening = if (item.needsDecision) "TODO item ${item.id} waits for a decision that is the user's to make" else "Fix TODO item ${item.id}"
            out.append("$opening (priority ${item.priority.json}$tags).\n\n")
            out.append("Title: ${item.title}\n")
        }
        if (options.short && !comment) {
            out.append("\nRead the item with the todo_get tool, id \"${item.id}\". ")
            out.append("It returns the details and the code as it is now.\n")
        } else {
            if (item.details.isNotBlank()) out.append("\nDetails:\n${item.details.trim()}\n")
            location(out, item, fileText, options.contextLines)
        }

        if (doneWhen.isNotBlank()) out.append("\nDone when: ${doneWhen.trim()}\n")
        if (extra.isNotBlank()) out.append("\n${extra.trim()}\n")
        if (comment) {
            out.append("\nWhen you have finished, remove the TODO comment. ")
            out.append("If you could not resolve it, leave the comment and explain why.\n")
            return out.toString()
        }
        if (item.needsDecision) {
            decision(out, item, options)
            return out.toString()
        }
        out.append("\nWhen you have finished, call the todo_update tool with id \"${item.id}\" and status \"fixed\". ")
        out.append("If that tool is not available, set \"status\": \"fixed\" for this item in ${options.itemsFile}. ")
        out.append("If you could not fix it, leave the status alone and explain why.\n")
        val tags = (item.tags + Tags.NEEDS_DECISION).distinct().joinToString(" ")
        out.append("\nIf fixing this needs a decision that is the user's to make, do not guess and do not change the code. ")
        out.append("Call todo_update with id \"${item.id}\", tags \"$tags\" and details that say what has to be decided, ")
        out.append("and leave the status alone.\n")
        return out.toString()
    }

    /** An item that waits for me: the agent lays out the options and asks, and only then does the work. */
    private fun decision(out: StringBuilder, item: TodoItem, options: PromptOptions) {
        out.append("\nDo not change anything yet. Work out what has to be decided and give the user the options, ")
        out.append("each with what it means and what it costs, and say which one you recommend. ")
        out.append("Then ask the user to choose and wait for the answer. ")
        out.append("If you have a tool for asking the user a question, use it.\n")
        val tags = (item.tags - Tags.NEEDS_DECISION).joinToString(" ")
        out.append("\nWhen the user has chosen, carry the choice out. ")
        out.append("When you have finished, call the todo_update tool with id \"${item.id}\", tags \"$tags\" and status \"fixed\". ")
        out.append("If that tool is not available, remove the \"${Tags.NEEDS_DECISION}\" tag and set \"status\": \"fixed\" ")
        out.append("for this item in ${options.itemsFile}. ")
        out.append("If the user does not choose, leave the item as it is.\n")
    }

    private fun location(out: StringBuilder, item: TodoItem, fileText: String?, contextLines: Int) {
        val anchor = item.anchor ?: return
        if (anchor.lost || fileText == null) {
            out.append("\nLocation: ${anchor.path}. The exact lines could not be located; ")
            out.append("when last seen the code was:\n\n```\n${anchor.text}\n```\n")
            return
        }
        val all = AnchorResolver.lines(fileText)
        val from = maxOf(1, anchor.startLine - contextLines)
        val to = minOf(all.size, anchor.endLine + contextLines)
        out.append("\nLocation: ${anchor.path}, lines ${anchor.startLine}-${anchor.endLine}. ")
        out.append("The lines to change are marked with \">\".\n\n```\n")
        for (n in from..to) {
            val mark = if (n in anchor.startLine..anchor.endLine) ">" else " "
            out.append("$mark ${n.toString().padStart(4)}  ${all[n - 1]}\n")
        }
        out.append("```\n")
    }
}
