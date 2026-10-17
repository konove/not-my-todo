package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.ide.StoreRegistration
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.ItemRef
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem

/**
 * How a prompt is written. [contextLines] is the code shown either side of the item's lines.
 * With [short] the agent is sent to `todo_get` and no code is embedded. [extra] is appended once
 * to every prompt. [itemsDir] is the store's folder of the project's item files, named as the fallback for an agent
 * without the tools; with none, no fallback is offered.
 * With [claim] the agent is told to mark an open item in progress before it starts: for a prompt
 * that may never reach an agent, where the IDE cannot know that the work has begun.
 * [absentCheckout] is the project the item's code is of when its checkout is not on this machine: the
 * paths are then relative to a folder the agent cannot see, and the prompt says so.
 * [checkoutAt] is the folder of the checkout the item's code is in when it is not the agent's: a short prompt
 * names it, since `todo_get` gives the paths relative to it.
 */
data class PromptOptions(
    val contextLines: Int = 20,
    val short: Boolean = false,
    val extra: String = "",
    val itemsDir: String = "",
    val claim: Boolean = false,
    val absentCheckout: String = "",
    val checkoutAt: String = "",
)

/** Builds the text handed to an agent for one item. The files are the text of each anchored file, by its path. */
object PromptBuilder {
    private const val RESOLUTION =
        "Pass a resolution too, which says in a sentence or two what you did. If you commit the work, do it after this call and name " +
            "the item id in the commit message: that commit is then shown as the one that fixed it, and fixedIn need not be passed. "

    /** Every tool of the store's server, so that an agent whose tools are deferred loads them in one ToolSearch, not one round each. */
    private val TOOLS = listOf(
        "todo_get", "todo_update", "todo_comment", "todo_decided", "todo_batch",
        "todo_list", "todo_create", "todo_move", "todo_delete", "todo_projects",
    ).joinToString(",", prefix = "select:") { "mcp__${StoreRegistration.NAME}__$it" }

    private val LOAD_TOOLS =
        "\nThe todo tools come from the ${StoreRegistration.NAME} server. If they have to be loaded with ToolSearch before you can call them, " +
            "load them all in one call, with the query \"$TOOLS\".\n"

    fun build(item: TodoItem, files: Map<String, String>, doneWhen: String, options: PromptOptions = PromptOptions()): String =
        one(item, files, doneWhen, options, options.extra, tools = true)

    /** One prompt for several items, each with its own instructions. */
    fun buildAll(items: List<TodoItem>, files: Map<String, String>, options: PromptOptions = PromptOptions()): String {
        val extra = options.extra.trim()
        // The tools are loaded once for all the items, unless they are all TODO comments, which need none.
        val tools = if (items.all { ItemRef.parse(it.id) == null }) "" else "${LOAD_TOOLS.trim()}\n\n"
        return "Fix the following ${items.size} TODO items, one at a time.\n\n" +
            (if (extra.isEmpty()) "" else "$extra\n\n") + tools +
            items.joinToString("\n---\n\n") { item -> one(item, files, "", options, "", tools = false) }
    }

    /** With [tools] the agent is told how to load the todo tools, just before the prompt first names one. */
    private fun one(item: TodoItem, files: Map<String, String>, doneWhen: String, options: PromptOptions, extra: String, tools: Boolean): String {
        val out = StringBuilder()
        // A TODO comment found in the code is not in the store, so there is no item to report on or to read. An item of another project has a qualified id, and is an item.
        val comment = ItemRef.parse(item.id) == null
        if (comment) {
            out.append("Resolve this TODO comment in the code.\n\n")
            out.append("Comment: ${item.title}\n")
        } else {
            val tags = if (item.tags.isEmpty()) "" else ", tags: ${item.tags.joinToString(", ")}"
            val opening = if (item.needsDecision) "TODO item ${item.id} waits for a decision that is the user's to make" else "Fix TODO item ${item.id}"
            val effort = item.effort?.let { ", effort ${it.json}" }.orEmpty()
            out.append("$opening (priority ${item.priority.json}$effort$tags).\n\n")
            out.append("Title: ${item.title}\n")
        }
        if (options.short && !comment) {
            if (tools) out.append(LOAD_TOOLS)
            out.append("\nRead the item with the todo_get tool, id \"${item.id}\". ")
            out.append("It returns the details, the comments and the code as it is now.\n")
            if (options.checkoutAt.isNotBlank() && item.anchors.isNotEmpty()) {
                out.append("The paths todo_get returns are relative to the checkout at ${options.checkoutAt}, not to the folder you are in.\n")
            }
        } else {
            if (item.details.isNotBlank()) out.append("\nDetails:\n${item.details.trim()}\n")
            item.toDecide?.let { out.append("\nTo decide:\n${it.trim()}\n") }
            if (item.decisions.isNotEmpty()) {
                out.append("\nAlready decided by the user:\n")
                item.decisions.forEach { out.append("- ${it.question} Answer: ${it.answer}\n") }
            }
            if (item.comments.isNotEmpty()) {
                out.append("\nComments:\n")
                item.comments.forEach { out.append("- ${it.author.json}, ${it.time}: ${it.text}\n") }
            }
            locations(out, item, files, options.contextLines)
        }
        if (options.absentCheckout.isNotBlank() && item.anchors.isNotEmpty()) {
            out.append("\nThe code is in the checkout of project ${options.absentCheckout}, which is not on this machine; ")
            out.append("the paths are relative to that checkout, not to the folder you are in.\n")
        }
        if (tools && !comment && !options.short) out.append(LOAD_TOOLS)

        if (doneWhen.isNotBlank()) out.append("\nDone when: ${doneWhen.trim()}\n")
        if (extra.isNotBlank()) out.append("\n${extra.trim()}\n")
        if (comment) {
            out.append("\nWhen you have finished, remove the TODO comment. ")
            out.append("If you could not resolve it, leave the comment and explain why.\n")
            return out.toString()
        }
        val claimed = options.claim && item.status == Status.OPEN
        val file = itemFile(item, options)
        if (claimed) {
            out.append("\nBefore you start, call the todo_update tool with id \"${item.id}\" and status \"in_progress\", ")
            out.append("which shows the user that the item is being worked on.")
            file?.let { out.append(" If that tool is not available, set \"status\": \"in_progress\" in $it.") }
            out.append("\n")
        }
        if (item.needsDecision) {
            decision(out, item, file, claimed)
            return out.toString()
        }
        out.append("\nWhen you have finished, call the todo_update tool with id \"${item.id}\" and status \"fixed\". ")
        out.append(RESOLUTION)
        file?.let { out.append("If that tool is not available, set \"status\": \"fixed\" in $it. ") }
        out.append("If you could not fix it, leave the status alone and explain why.\n")
        out.append("\nIf fixing this needs a decision that is the user's to make, do not guess and do not change the code. ")
        out.append("Call todo_update with id \"${item.id}\" and toDecide, ")
        out.append("which says what has to be decided; that marks the item as waiting for the user. Leave the status and the details alone.\n")
        return out.toString()
    }

    /** An item that waits for me: the agent lays out the options and asks, and only then does the work. */
    private fun decision(out: StringBuilder, item: TodoItem, file: String?, claimed: Boolean) {
        out.append("\nDo not change anything ${if (claimed) "else " else ""}yet. Work out what has to be decided and give the user the options, ")
        out.append("each with what it means and what it costs, and say which one you recommend. ")
        out.append("Then ask the user to choose and wait for the answer. ")
        out.append("If you have a tool for asking the user a question, use it.\n")
        out.append("\nWhen the user has answered, and before you change anything, call the todo_decided tool with id \"${item.id}\" ")
        out.append("and every question you asked, each with the options you offered and the answer: ")
        out.append("the option the user chose or what the user typed, in the user's words. ")
        out.append("That takes the \"${Tags.NEEDS_DECISION}\" tag away. ")
        out.append("If that tool is not available, say what was decided in the resolution.\n")
        out.append("\nWhen the user has chosen, carry the choice out. ")
        out.append("When you have finished, call the todo_update tool with id \"${item.id}\" and status \"fixed\". ")
        out.append(RESOLUTION)
        file?.let { out.append("If that tool is not available, remove the \"${Tags.NEEDS_DECISION}\" tag and set \"status\": \"fixed\" in $it. ") }
        out.append("If the user does not choose, leave the item as it is.\n")
    }

    /** The file the store keeps [item] in, named by its own id, for an agent without the tools; null when the prompt does not know the folder. */
    private fun itemFile(item: TodoItem, options: PromptOptions): String? =
        options.itemsDir.takeIf { it.isNotBlank() }?.let { "$it/${item.id.substringAfterLast('/')}.json" }

    /** Every anchor of the item, numbered when there is more than one. */
    private fun locations(out: StringBuilder, item: TodoItem, files: Map<String, String>, contextLines: Int) {
        if (item.anchors.size > 1) out.append("\nThe item is attached to ${item.anchors.size} places.\n")
        item.anchors.forEachIndexed { index, anchor ->
            val label = if (item.anchors.size > 1) "Location ${index + 1}" else "Location"
            location(out, label, anchor, files[anchor.path], contextLines)
        }
    }

    private fun location(out: StringBuilder, label: String, anchor: Anchor, fileText: String?, contextLines: Int) {
        if (anchor.isFile) {
            out.append("\n$label: ${anchor.path}, the whole file.")
            out.append(if (anchor.lost || fileText == null) " The file could not be found.\n" else "\n")
            return
        }
        if (anchor.lost || fileText == null) {
            out.append("\n$label: ${anchor.path}. The exact lines could not be located; ")
            out.append("when last seen the code was:\n\n```\n${anchor.text}\n```\n")
            return
        }
        val all = AnchorResolver.lines(fileText)
        val from = maxOf(1, anchor.startLine - contextLines)
        val to = minOf(all.size, anchor.endLine + contextLines)
        out.append("\n$label: ${anchor.path}, lines ${anchor.startLine}-${anchor.endLine}. ")
        out.append("The lines to change are marked with \">\".\n\n```\n")
        for (n in from..to) {
            val mark = if (n in anchor.startLine..anchor.endLine) ">" else " "
            out.append("$mark ${n.toString().padStart(4)}  ${all[n - 1]}\n")
        }
        out.append("```\n")
    }
}
