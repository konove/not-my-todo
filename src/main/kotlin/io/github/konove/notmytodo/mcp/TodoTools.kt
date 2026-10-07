package io.github.konove.notmytodo.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.ide.AnchorTracker
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.StoreException

class TodoToolError(message: String) : Exception(message)

/** What the MCP tools do, as plain functions returning JSON text. Safe to call from any thread. */
class TodoTools(private val project: Project) {
    private val service get() = TodoService.getInstance(project)
    private val store get() = service.store

    fun list(status: String?, tag: String?, priority: String?, path: String?): String {
        val wantedStatus = status?.let(::parseStatus)
        val wantedPriority = priority?.let(::parsePriority)
        val wantedTag = tag?.let { Tags.normalize(it) ?: throw TodoToolError("tag must be a single word") }
        flushAnchors()
        val array = JsonArray()
        readable().filter { item ->
            (wantedStatus == null || item.status == wantedStatus) &&
                (wantedPriority == null || item.priority == wantedPriority) &&
                (wantedTag == null || wantedTag in item.tags) &&
                (path == null || item.anchor?.path == path)
        }.forEach { array.add(summary(it)) }
        return TodoJson.toText(array)
    }

    fun get(id: String): String {
        flushAnchors()
        val item = readable().firstOrNull { it.id == id } ?: throw TodoToolError("there is no item with id $id")
        val json = TodoJson.encodeItem(item)
        val anchor = item.anchor
        if (anchor != null && !anchor.lost) {
            service.refresh(anchor.path)
            val text = runReadActionBlocking { service.readText(anchor.path) }
            if (text != null) {
                val lines = AnchorResolver.lines(text)
                if (anchor.endLine <= lines.size) {
                    json.addProperty("code", lines.subList(anchor.startLine - 1, anchor.endLine).joinToString("\n"))
                }
            }
        }
        return TodoJson.toText(json)
    }

    fun create(
        title: String, details: String?, priority: String?, tags: String?,
        path: String?, startLine: Int?, endLine: Int?,
    ): String {
        if (title.isBlank()) throw TodoToolError("title must not be empty")
        val parsedPriority = priority?.let(::parsePriority) ?: Priority.P2
        val anchor = path?.let { buildAnchor(it, startLine, endLine) }
        val draft = Draft(title, details.orEmpty(), parsedPriority, Tags.parseList(tags.orEmpty()), Author.AGENT, anchor)
        return TodoJson.toText(TodoJson.encodeItem(storeCall { store.create(draft) }))
    }

    fun update(
        id: String, title: String?, details: String?, priority: String?, tags: String?, status: String?,
        path: String? = null, startLine: Int? = null, endLine: Int? = null,
    ): String {
        val parsedPriority = priority?.let(::parsePriority)
        val parsedStatus = status?.let(::parseStatus)
        val parsedTags = tags?.let(Tags::parseList)
        if (path != null || startLine != null || endLine != null) {
            val item = readable().firstOrNull { it.id == id } ?: throw TodoToolError("there is no item with id $id")
            val anchorPath = path ?: item.anchor?.path
                ?: throw TodoToolError("path is required to attach $id to code: it is a plain note")
            attach(id, buildAnchor(anchorPath, startLine, endLine))
        }
        val saved = storeCall {
            store.update(id) {
                it.copy(
                    title = title ?: it.title,
                    details = details ?: it.details,
                    priority = parsedPriority ?: it.priority,
                    tags = parsedTags ?: it.tags,
                    status = parsedStatus ?: it.status,
                )
            }
        }
        return TodoJson.toText(TodoJson.encodeItem(saved))
    }

    private fun buildAnchor(path: String, startLine: Int?, endLine: Int?): Anchor {
        val start = startLine ?: throw TodoToolError("startLine is required when path is given")
        val end = endLine ?: start
        // An agent often edits a file and files a TODO on it straight away.
        service.refresh(path)
        val text = runReadActionBlocking { service.readText(path) }
            ?: throw TodoToolError("file not found in the project: $path (use a path relative to the project root)")
        val count = AnchorResolver.lines(text).size
        if (start < 1 || end < start || end > count) {
            throw TodoToolError("lines $start-$end are outside $path, which has $count lines")
        }
        return AnchorResolver.capture(path, text, start, end)
    }

    private fun summary(item: TodoItem): JsonObject {
        val json = TodoJson.encodeItem(item)
        json.getAsJsonObject("anchor")?.apply {
            remove("text")
            remove("before")
            remove("after")
        }
        return json
    }

    private fun readable(): List<TodoItem> {
        store.error?.let { throw TodoToolError("${store.file} cannot be read: $it. Fix or delete that file.") }
        return store.items
    }

    /** Writes pending editor line changes to the store so answers reflect unsaved edits. */
    private fun flushAnchors() {
        ApplicationManager.getApplication().invokeAndWait {
            if (!project.isDisposed) project.service<AnchorTracker>().flushAll()
        }
    }

    /** Re-attaches on the event thread, where the tracker keeps its markers. */
    private fun attach(id: String, anchor: Anchor) {
        var failure: StoreException? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                if (!project.isDisposed) project.service<AnchorTracker>().attach(id, anchor)
            } catch (e: StoreException) {
                failure = e
            }
        }
        failure?.let { throw TodoToolError(it.message ?: "the change could not be saved") }
    }

    private fun <T> storeCall(block: () -> T): T = try {
        block()
    } catch (e: StoreException) {
        throw TodoToolError(e.message ?: "the change could not be saved")
    }

    private fun parsePriority(value: String): Priority =
        Priority.fromJson(value) ?: throw TodoToolError("priority must be one of p1, p2, p3")

    private fun parseStatus(value: String): Status =
        Status.fromJson(value) ?: throw TodoToolError("status must be one of open, in_progress, fixed, done, wont_fix")
}
