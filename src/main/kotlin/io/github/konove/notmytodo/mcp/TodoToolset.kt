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
            "current line range but not the code; use todo_get for the code. To choose what to work on next, take the highest priority first and, " +
            "within a priority, the least effort. An item with open parts has the highest priority among them, here and wherever it is shown, whatever was set on it. Pass lost=true after moving or rewriting code " +
            "to find the items that need re-attaching: the files are read from disk again for every answer, so lost is as the code is now. Pass blocked=false to find the items that can be worked on now. Pass compact=true to survey many items, " +
            "for example to check for a duplicate before todo_create; with anyText one call looks for it under every name it may have. " +
            "In a compact line, an item that has parts says how many of them are open, fixed and closed as parts, for example \"2 open, 2 fixed, 1 closed\", " +
            "leaving out a group that counts none: at \"3 fixed, 2 closed\", with none open, the parent itself can be set to fixed, and at \"5 closed\" " +
            "it is time to close the parent yourself, which is never closed for you."
    )
    suspend fun todo_list(
        @McpDescription("Only items with one of these statuses, separated by commas or spaces: open, in_progress, fixed, done, wont_fix") status: String? = null,
        @McpDescription("Only items that have all of these tags, separated by commas or spaces") tags: String? = null,
        @McpDescription("Only items with this priority: p1, p2 or p3") priority: String? = null,
        @McpDescription("Only items with an anchor in this file or anywhere under this directory, as a path relative to the project root") path: String? = null,
        @McpDescription("Leave out items that have any of these tags, separated by commas or spaces") withoutTags: String? = null,
        @McpDescription("Only items whose title or details contain every one of these words, in any case") text: String? = null,
        @McpDescription("One line per item with id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor), and for an item that has parts, parts: how many of them are open (open or in_progress), fixed and closed (done or wont_fix), for example \"2 open, 2 fixed, 1 closed\", without a group that counts none; no details") compact: Boolean = false,
        @McpDescription("Only items with a lost anchor: the code or file they were attached to can no longer be found. Re-attach them with todo_update") lost: Boolean = false,
        @McpDescription("true for only blocked items, false for only items that are not blocked. An item is blocked while one it is blocked by is not done or wont_fix") blocked: Boolean? = null,
        @McpDescription("Only the parts of this item: the items that have it as their parent") parent: String? = null,
        @McpDescription("Only items with this effort: s, m or l") effort: String? = null,
        @McpDescription("Only items whose title or details contain at least one of these words or \"quoted phrases\", in any case, separated by commas or spaces, for example: cache memoise \"lookup table\"") anyText: String? = null,
    ): String = call { it.list(status, tags, priority, path, withoutTags, text, compact, lost, blocked, parent, effort, anyText) }

    @McpTool
    @McpDescription(
        "Get one TODO item by id (for example T-12) as JSON, with its details, its comments and, for an item anchored to code, " +
            "its anchors, each with the path, the current line range, lost and code, the code at those lines now. An anchor without lines is on the whole file. " +
            "An anchor that is lost has no code but text, the code it was attached to, which is the clue to where it belongs now. " +
            "Pass full=true for the text and the lines before and after it as they are stored, for every anchor. " +
            "toDecide is what the user has to decide, and decisions what the user was asked and answered. " +
            "Its links to other items are blockedBy, duplicateOf and parent; blocked is true while a blocker is still open, " +
            "and children lists the items that have this one as their parent, with parts saying how many of them are open (open or in_progress), fixed and closed (done or wont_fix), " +
            "for example \"2 open, 2 fixed, 1 closed\", without a group that counts none. A parent is not closed when its parts are: close it yourself."
    )
    suspend fun todo_get(
        @McpDescription("Item id, for example T-12") id: String,
        @McpDescription("true to get, for every anchor, the stored text and the lines before and after it as well. Leave it out: code has the same lines as they are now") full: Boolean = false,
    ): String = call { it.get(id, full) }

    @McpTool
    @McpDescription(
        "Create a TODO item for the user to triage. Give path and startLine to attach it to code, or path alone to " +
            "attach it to the whole file; do not make up a line range for something that concerns a file as a whole. " +
            "Give places to attach it to several places. Leave them all out for a plain note. When you add several items that belong " +
            "together, create one item for the whole first and give its id as parent to the others, or file them all in one todo_batch. " +
            "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
            "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it; pass full=true for the whole item as JSON."
    )
    suspend fun todo_create(
        @McpDescription("Short description of what needs doing") title: String,
        @McpDescription("Longer explanation") details: String? = null,
        @McpDescription("p1, p2 or p3; defaults to p2") priority: String? = null,
        @McpDescription("Tags separated by commas or spaces. When the user has to decide something before this can be fixed, pass toDecide and not the needs-decision tag alone") tags: String? = null,
        @McpDescription("File to attach to, as a path relative to the project root") path: String? = null,
        @McpDescription("First line of the code, 1-based; leave out to attach to the whole file") startLine: Int? = null,
        @McpDescription("Last line of the code, 1-based and inclusive; defaults to startLine") endLine: Int? = null,
        @McpDescription("More places to attach to, separated by commas or line breaks, each written path, path:12 or path:12-20") places: String? = null,
        @McpDescription("Ids of the items that must be closed before this one can be worked on, separated by commas or spaces") blockedBy: String? = null,
        @McpDescription("Id of the item that already says what this one says") duplicateOf: String? = null,
        @McpDescription("Id of the item this one is a part of. A parent cannot have a parent of its own") parent: String? = null,
        @McpDescription("Where the item comes from: the commit, the range of commits or the run that left it behind, for example 30c6f6ae..e9ac6245. HEAD, or another name git knows, is stored as the id of the commit it names now, and so is each end of a range such as HEAD~3..HEAD. Put it here and not in details") source: String? = null,
        @McpDescription("What the user has to decide before this can be fixed, as Markdown. Passing it tags the item needs-decision, so that no agent fixes it before the user has decided") toDecide: String? = null,
        @McpDescription("Your rough guess at how much work it is: s for under an hour, m for a few hours, l for a day or more. Pass it whenever you can tell") effort: String? = null,
        @McpDescription("true to get the whole item back as JSON, as todo_get with full=true gives it but without the code. Leave it out: you know the item, and the one line shows that the change landed") full: Boolean = false,
    ): String = call { it.create(title, details, priority, tags, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source, toDecide, effort, full) }

    @McpTool
    @McpDescription(
        "Change fields of a TODO item. Only the fields you pass are changed. Make every change to an item in one call: " +
            "status, resolution, fixedIn and the place it is attached to belong in the same todo_update, not in a call each. " +
            "details is replaced whole: " +
            "to add a finding or a progress note, use todo_comment instead. When you move or rewrite the code " +
            "an item is attached to, pass startLine (and path, if the file changed) to re-attach it; for an item attached to " +
            "several places, path says which one moves. Pass places to set the whole list of places instead. Set status to \"fixed\" when you " +
            "have finished the work so the user can review it, or to \"wont_fix\" to close it without a change; either way pass " +
            "resolution to say what you did or why. A parent whose parts show none open, for example \"3 fixed, 2 closed\", can itself be set to \"fixed\". When you commit the work, name the item id in the commit message, for example " +
            "\"Fix T-12: ...\": that commit is then shown as the one that fixed the item, with nothing to record after it. " +
            "Pass fixedIn only for a commit whose message does not name the item. " +
            "When an item says what another already says, set duplicateOf to that other item and status to \"wont_fix\". " +
            "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
            "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it, and changed, the names of the fields that changed. Of those, fixedIn, source, effort, parent, duplicateOf and blockedBy " +
            "are shown as saved, null when taken away, as status, priority and tags always are: check them there, without a todo_get. Pass full=true for the whole item as JSON."
    )
    suspend fun todo_update(
        @McpDescription("Item id, for example T-12") id: String,
        @McpDescription("New title") title: String? = null,
        @McpDescription("New details") details: String? = null,
        @McpDescription("p1, p2 or p3. On an item with open parts it is kept but not shown until they are closed: set the priority of the parts instead") priority: String? = null,
        @McpDescription("Replacement tags separated by commas or spaces. An item tagged needs-decision waits for the user: do not fix it. To make an item wait, pass toDecide; todo_decided takes the tag away, so do not pass tags to drop it") tags: String? = null,
        @McpDescription("open, in_progress, fixed, done or wont_fix") status: String? = null,
        @McpDescription("File to re-attach to, as a path relative to the project root; defaults to the item's current file") path: String? = null,
        @McpDescription("First line of the code to re-attach to, 1-based; leave out with a path to attach to the whole file. The line it already has re-attaches an anchor that is lost to the code that is there now") startLine: Int? = null,
        @McpDescription("Last line of the code, 1-based and inclusive; defaults to startLine") endLine: Int? = null,
        @McpDescription("Every place the item is to be attached to, replacing the ones it has, separated by commas or line breaks, each written path, path:12 or path:12-20. Places it already has are kept as they are, except one whose anchor is lost: that one is attached to the code that is at those lines now. Pass an empty text to make it a plain note") places: String? = null,
        @McpDescription("Ids of the items that must be closed before this one can be worked on, separated by commas or spaces, replacing the ones it has. Pass an empty text to unblock it") blockedBy: String? = null,
        @McpDescription("Id of the item that already says what this one says. Pass an empty text to take the link away") duplicateOf: String? = null,
        @McpDescription("Id of the item this one is a part of. A parent cannot have a parent of its own. Pass an empty text to take it out of its parent") parent: String? = null,
        @McpDescription("Where the item comes from: the commit, the range of commits or the run that left it behind. HEAD, or another name git knows, is stored as the id of the commit it names now, and so is each end of a range such as HEAD~3..HEAD. Pass an empty text to take it away") source: String? = null,
        @McpDescription("Id of the commit that fixed the item. Leave it out when the commit message names the item id: the commit is then read from git, and a fixedIn you pass is shown in its place. HEAD, or another name git knows, is stored as the id of the commit it names now. Pass an empty text to take it away") fixedIn: String? = null,
        @McpDescription("What you did to fix the item, or why it is closed without a change, as Markdown in a sentence or two. Pass an empty text to take it away") resolution: String? = null,
        @McpDescription("What the user has to decide before this can be fixed, as Markdown. Passing it tags the item needs-decision, so that no agent fixes it before the user has decided. Pass an empty text to take it away") toDecide: String? = null,
        @McpDescription("A rough guess at how much work it is: s for under an hour, m for a few hours, l for a day or more. Pass an empty text to take it away") effort: String? = null,
        @McpDescription("true to get the whole item back as JSON, as todo_get with full=true gives it but without the code. Leave it out: you know the item, and the one line shows that the change landed") full: Boolean = false,
    ): String = call {
        it.update(id, title, details, priority, tags, status, path, startLine, endLine, places, blockedBy, duplicateOf, parent, source, fixedIn, resolution, toDecide, effort, full)
    }

    @McpTool
    @McpDescription(
        "Add a comment to a TODO item: a finding or a progress note. What the user has to decide goes in toDecide " +
            "of todo_update instead. The user is shown " +
            "a comment on an item that is in progress or tagged needs-decision as soon as it is added. It is appended to " +
            "the item's comments with your authorship and the time, and nothing else on the item is changed, so " +
            "prefer it to rewriting details with todo_update. " +
            "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
            "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it; pass full=true for the whole item as JSON."
    )
    suspend fun todo_comment(
        @McpDescription("Item id, for example T-12") id: String,
        @McpDescription("The comment, as Markdown") text: String,
        @McpDescription("true to get the whole item back as JSON, as todo_get with full=true gives it but without the code. Leave it out: you know the item, and the one line shows that the change landed") full: Boolean = false,
    ): String = call { it.comment(id, text, full) }

    @McpTool
    @McpDescription(
        "Create or change several TODO items in one call, saved as one write: use it to file a list of findings or to " +
            "re-tag, re-prioritise or close many items, instead of calling todo_create or todo_update for each or editing " +
            "the items file. items is an array of objects. An object with an id changes that item and takes the " +
            "fields of todo_update, path, startLine, endLine and places to re-attach it among them, and comment as well, which adds a comment " +
            "as todo_comment does; an object without an id is a new item " +
            "and takes the fields of todo_create, and ref as well: a name of your choosing by which the entries after it " +
            "name the new item, written \"\$ref\" (a \$ and the name), in parent, blockedBy and duplicateOf. So one call files a whole item and its parts. " +
            "The entries are applied in order, and when one is wrong none is saved " +
            "and the error names it. Returns one line per entry with the id, title, priority, effort, status and tags of the item as saved, " +
            "which for a new item is how you learn its id, and for an item that has parts with parts, how many of them are open, fixed and closed " +
            "after the whole batch, for example \"3 fixed, 2 closed\"."
    )
    suspend fun todo_batch(
        @McpDescription(
            "Array of objects, passed as an array and not as a text, for example [{\"title\": \"Rework the index\", \"ref\": \"whole\"}, " +
                "{\"title\": \"Cache the index\", \"parent\": \"\$whole\", \"priority\": \"p3\", \"effort\": \"s\", \"tags\": \"perf\", " +
                "\"path\": \"src/Index.kt\", \"startLine\": 12}, {\"id\": \"T-3\", \"tags\": \"perf mcp\", \"blockedBy\": \"\$whole\"}, " +
                "{\"id\": \"T-4\", \"status\": \"wont_fix\", \"resolution\": \"...\"}, " +
                "{\"id\": \"T-5\", \"toDecide\": \"Which of the two?\"}, {\"id\": \"T-6\", \"path\": \"src/Moved.kt\", \"startLine\": 40}]"
        ) items: JsonEntries,
    ): String = call { it.batch(items.text) }

    @McpTool
    @McpDescription(
        "Record what the user decided about a TODO item: call it when you have asked the user what an item tagged " +
            "needs-decision waits for and the user has answered, before you do the work. The questions, the options and the " +
            "answers are appended to the item's decisions with the time, and the needs-decision tag is taken away: the item no longer " +
            "waits for the user, so there is no need to pass tags to todo_update afterwards. toDecide stays as the record of what was asked. " +
            "When the user answered only some of the questions, pass what is still open as toDecide to todo_update, which makes the item wait again. " +
            "The status is not changed. " +
            "Returns one line with the id, title, priority, effort, status, tags and \"at\" (path:lines of each anchor) of the item as saved, " +
            "lost when an anchor is lost, and parts when it has parts, as todo_list compact=true shows it, and changed, the names of the fields that changed; pass full=true for the whole item as JSON."
    )
    suspend fun todo_decided(
        @McpDescription("Item id, for example T-12") id: String,
        @McpDescription(
            "Array, passed as an array and not as a text, with one object for each question you asked: question, options (an array of the answers you offered, " +
                "left out when you offered none) and answer (the option the user chose or what the user typed, in the user's words), " +
                "for example [{\"question\": \"Which license?\", \"options\": [\"MIT\", \"Apache 2.0\"], \"answer\": \"MIT\"}]"
        ) decisions: JsonEntries,
        @McpDescription("true to get the whole item back as JSON, as todo_get with full=true gives it but without the code. Leave it out: you know the item, and the one line shows that the change landed") full: Boolean = false,
    ): String = call { it.decided(id, decisions.text, full) }

    @McpTool
    @McpDescription(
        "Delete a TODO item that you made: one made to try the tools, or filed by mistake. Do not edit the items file to remove an item. " +
            "Only an item made by an agent can be deleted, and only when it is not the parent of an item and no item names it in " +
            "blockedBy or duplicateOf; anything else is refused with an error that says why. An item the user made, or work that will not be done, " +
            "is closed with todo_update and status \"wont_fix\" instead. Deleting cannot be undone. Returns one line with deleted, the id, and the title of the item that is gone."
    )
    suspend fun todo_delete(
        @McpDescription("Item id, for example T-12") id: String,
    ): String = call { it.delete(id) }

    private suspend fun call(block: (TodoTools) -> String): String {
        val project = currentCoroutineContext().project
        return try {
            withContext(Dispatchers.IO) { block(TodoTools(project)) }
        } catch (e: TodoToolError) {
            mcpFail(e.message ?: "the TODO tool failed")
        }
    }
}
