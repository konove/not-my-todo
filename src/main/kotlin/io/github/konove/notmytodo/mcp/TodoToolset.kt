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
            "All filters must match. Returns a JSON array. Anchored items include their file path and current " +
            "line range but not the code; use todo_get for the code. Pass lost=true after moving or rewriting code " +
            "to find the items that need re-attaching. Pass compact=true to survey many items, " +
            "for example to check for a duplicate before todo_create."
    )
    suspend fun todo_list(
        @McpDescription("Only items with one of these statuses, separated by commas or spaces: open, in_progress, fixed, done, wont_fix") status: String? = null,
        @McpDescription("Only items that have all of these tags, separated by commas or spaces") tags: String? = null,
        @McpDescription("Only items with this priority: p1, p2 or p3") priority: String? = null,
        @McpDescription("Only items anchored in this file or anywhere under this directory, as a path relative to the project root") path: String? = null,
        @McpDescription("Leave out items that have any of these tags, separated by commas or spaces") withoutTags: String? = null,
        @McpDescription("Only items whose title or details contain every one of these words, in any case") text: String? = null,
        @McpDescription("One line per item with id, title, priority, status, tags and \"at\" (path:lines); no details") compact: Boolean = false,
        @McpDescription("Only items whose anchor is lost: the code they were attached to can no longer be found. Re-attach them with todo_update") lost: Boolean = false,
    ): String = call { it.list(status, tags, priority, path, withoutTags, text, compact, lost) }

    @McpTool
    @McpDescription(
        "Get one TODO item by id (for example T-12) as JSON, with its details and, for an item anchored to code, " +
            "the current line range and the code at those lines."
    )
    suspend fun todo_get(
        @McpDescription("Item id, for example T-12") id: String,
    ): String = call { it.get(id) }

    @McpTool
    @McpDescription(
        "Create a TODO item for the user to triage. Give path and startLine to attach it to code; " +
            "leave them out for a plain note. Returns the new item as JSON."
    )
    suspend fun todo_create(
        @McpDescription("Short description of what needs doing") title: String,
        @McpDescription("Longer explanation") details: String? = null,
        @McpDescription("p1, p2 or p3; defaults to p2") priority: String? = null,
        @McpDescription("Tags separated by commas or spaces. Add needs-decision when the user has to decide something before this can be fixed") tags: String? = null,
        @McpDescription("File to attach to, as a path relative to the project root") path: String? = null,
        @McpDescription("First line of the code, 1-based") startLine: Int? = null,
        @McpDescription("Last line of the code, 1-based and inclusive; defaults to startLine") endLine: Int? = null,
    ): String = call { it.create(title, details, priority, tags, path, startLine, endLine) }

    @McpTool
    @McpDescription(
        "Change fields of a TODO item. Only the fields you pass are changed. When you move or rewrite the code " +
            "an item is attached to, pass startLine (and path, if the file changed) to re-attach it. Set status to \"fixed\" when you " +
            "have finished the work so the user can review it, or to \"wont_fix\" to close it without a change. " +
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
        @McpDescription("First line of the code to re-attach to, 1-based; required when re-attaching") startLine: Int? = null,
        @McpDescription("Last line of the code, 1-based and inclusive; defaults to startLine") endLine: Int? = null,
    ): String = call { it.update(id, title, details, priority, tags, status, path, startLine, endLine) }

    private suspend fun call(block: (TodoTools) -> String): String {
        val project = currentCoroutineContext().project
        return try {
            withContext(Dispatchers.IO) { block(TodoTools(project)) }
        } catch (e: TodoToolError) {
            mcpFail(e.message ?: "the TODO tool failed")
        }
    }
}
