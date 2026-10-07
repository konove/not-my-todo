@file:Suppress("FunctionName")

package io.github.konove.notmytodo.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Exposes the project's TODO list through the IDE's MCP server. Method names are the tool names. */
class TodoToolset : McpToolset {
    @McpTool
    @McpDescription(
        "List the TODO items kept by the Not My TODO plugin for this project, in id order. " +
            "All filters must match. Returns a JSON array. Anchored items include, for each of their anchors, the file path and " +
            "current line range but not the code; use todo_get for the code. Pass lost=true after moving or rewriting code " +
            "to find the items that need re-attaching. Pass blocked=false to find the items that can be worked on now. Pass compact=true to survey many items, " +
            "for example to check for a duplicate before todo_create."
    )
    suspend fun todo_list(
        @McpDescription("Only items with one of these statuses, separated by commas or spaces: open, in_progress, fixed, done, wont_fix") status: String? = null,
        @McpDescription("Only items that have all of these tags, separated by commas or spaces") tags: String? = null,
        @McpDescription("Only items with this priority: p1, p2 or p3") priority: String? = null,
        @McpDescription("Only items with an anchor in this file or anywhere under this directory, as a path relative to the project root") path: String? = null,
        @McpDescription("Leave out items that have any of these tags, separated by commas or spaces") withoutTags: String? = null,
        @McpDescription("Only items whose title or details contain every one of these words, in any case") text: String? = null,
        @McpDescription("One line per item with id, title, priority, status, tags and \"at\" (path:lines of each anchor); no details") compact: Boolean = false,
        @McpDescription("Only items with a lost anchor: the code or file they were attached to can no longer be found. Re-attach them with todo_update") lost: Boolean = false,
        @McpDescription("true for only blocked items, false for only items that are not blocked. An item is blocked while one it is blocked by is not done or wont_fix") blocked: Boolean? = null,
        @McpDescription("Only the parts of this item: the items that have it as their parent") parent: String? = null,
    ): String = call { it.list(status, tags, priority, path, withoutTags, text, compact, lost, blocked, parent) }

    @McpTool
    @McpDescription(
        "Get one TODO item by id (for example T-12) as JSON, with its details, its comments and, for an item anchored to code, " +
            "its anchors, each with the current line range and the code at those lines. An anchor without lines is on the whole file. " +
            "Its links to other items are blockedBy, duplicateOf and parent; blocked is true while a blocker is still open, " +
            "and children lists the items that have this one as their parent."
    )
    suspend fun todo_get(
        @McpDescription("Item id, for example T-12") id: String,
    ): String = call { it.get(id) }

    @McpTool
    @McpDescription(
        "Create a TODO item for the user to triage. Give path and startLine to attach it to code, or path alone to " +
            "attach it to the whole file; do not make up a line range for something that concerns a file as a whole. " +
            "Give places to attach it to several places. Leave them all out for a plain note. When you add several items that belong " +
            "together, create one item for the whole first and give its id as parent to the others. Returns the new item as JSON."
    )
    suspend fun todo_create(
        @McpDescription("Short description of what needs doing") title: String,
        @McpDescription("Longer explanation") details: String? = null,
        @McpDescription("p1, p2 or p3; defaults to p2") priority: String? = null,
        @McpDescription("Tags separated by commas or spaces. Add needs-decision when the user has to decide something before this can be fixed") tags: String? = null,
        @McpDescription("File to attach to, as a path relative to the project root") path: String? = null,
        @McpDescription("First line of the code, 1-based; leave out to attach to the whole file") startLine: Int? = null,
        @McpDescription("Last line of the code, 1-based and inclusive; defaults to startLine") endLine: Int? = null,
        @McpDescription("More places to attach to, separated by commas or line breaks, each written path, path:12 or path:12-20") places: String? = null,
        @McpDescription("Ids of the items that must be closed before this one can be worked on, separated by commas or spaces") blockedBy: String? = null,
        @McpDescription("Id of the item that already says what this one says") duplicateOf: String? = null,
        @McpDescription("Id of the item this one is a part of. A parent cannot have a parent of its own") parent: String? = null,
        @McpDescription("Where the item comes from: the commit, the range of commits or the run that left it behind, for example 30c6f6ae..e9ac6245. Put it here and not in details") source: String? = null,
    ): String = call { it.create(title, details, priority, tags, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source) }

    @McpTool
    @McpDescription(
        "Change fields of a TODO item. Only the fields you pass are changed. details is replaced whole: " +
            "to add a finding or a progress note, use todo_comment instead. When you move or rewrite the code " +
            "an item is attached to, pass startLine (and path, if the file changed) to re-attach it; for an item attached to " +
            "several places, path says which one moves. Pass places to set the whole list of places instead. Set status to \"fixed\" when you " +
            "have finished the work so the user can review it, or to \"wont_fix\" to close it without a change; either way pass " +
            "resolution to say what you did or why, and with \"fixed\" pass fixedIn if you committed the work. " +
            "When an item says what another already says, set duplicateOf to that other item and status to \"wont_fix\". " +
            "Returns the updated item as JSON."
    )
    suspend fun todo_update(
        @McpDescription("Item id, for example T-12") id: String,
        @McpDescription("New title") title: String? = null,
        @McpDescription("New details") details: String? = null,
        @McpDescription("p1, p2 or p3") priority: String? = null,
        @McpDescription("Replacement tags separated by commas or spaces. Include needs-decision when the user has to decide something before this can be fixed; do not fix items that carry it") tags: String? = null,
        @McpDescription("open, in_progress, fixed, done or wont_fix") status: String? = null,
        @McpDescription("File to re-attach to, as a path relative to the project root; defaults to the item's current file") path: String? = null,
        @McpDescription("First line of the code to re-attach to, 1-based; leave out with a path to attach to the whole file") startLine: Int? = null,
        @McpDescription("Last line of the code, 1-based and inclusive; defaults to startLine") endLine: Int? = null,
        @McpDescription("Every place the item is to be attached to, replacing the ones it has, separated by commas or line breaks, each written path, path:12 or path:12-20. Places it already has are kept as they are. Pass an empty text to make it a plain note") places: String? = null,
        @McpDescription("Ids of the items that must be closed before this one can be worked on, separated by commas or spaces, replacing the ones it has. Pass an empty text to unblock it") blockedBy: String? = null,
        @McpDescription("Id of the item that already says what this one says. Pass an empty text to take the link away") duplicateOf: String? = null,
        @McpDescription("Id of the item this one is a part of. A parent cannot have a parent of its own. Pass an empty text to take it out of its parent") parent: String? = null,
        @McpDescription("Where the item comes from: the commit, the range of commits or the run that left it behind. Pass an empty text to take it away") source: String? = null,
        @McpDescription("Id of the commit that fixed the item, if the work is committed. Pass an empty text to take it away") fixedIn: String? = null,
        @McpDescription("What you did to fix the item, or why it is closed without a change, as Markdown in a sentence or two. Pass an empty text to take it away") resolution: String? = null,
    ): String = call {
        it.update(id, title, details, priority, tags, status, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source, fixedIn, resolution)
    }

    @McpTool
    @McpDescription(
        "Add a comment to a TODO item: a finding, a progress note or a question for the user. It is appended to " +
            "the item's comments with your authorship and the time, and nothing else on the item is changed, so " +
            "prefer it to rewriting details with todo_update. Returns the updated item as JSON."
    )
    suspend fun todo_comment(
        @McpDescription("Item id, for example T-12") id: String,
        @McpDescription("The comment, as Markdown") text: String,
    ): String = call { it.comment(id, text) }

    private suspend fun call(block: (TodoTools) -> String): String {
        val project = currentCoroutineContext().project
        return try {
            withContext(Dispatchers.IO) { block(TodoTools(project)) }
        } catch (e: TodoToolError) {
            mcpFail(e.message ?: "the TODO tool failed")
        }
    }
}
