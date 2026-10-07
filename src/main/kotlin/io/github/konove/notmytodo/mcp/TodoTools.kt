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

    fun list(
        status: String? = null, tags: String? = null, priority: String? = null, path: String? = null,
        withoutTags: String? = null, text: String? = null, compact: Boolean = false, lost: Boolean = false,
    ): String {
        val statuses = words(status).map(::parseStatus)
        val wantedPriority = priority?.takeIf { it.isNotBlank() }?.let(::parsePriority)
        val wanted = Tags.parseList(tags.orEmpty())
        val unwanted = Tags.parseList(withoutTags.orEmpty())
        val under = path?.takeIf { it.isNotBlank() }?.let(::directory)
        val needles = words(text)
        flushAnchors()
        val found = readable().filter { item ->
            (statuses.isEmpty() || item.status in statuses) &&
                (wantedPriority == null || item.priority == wantedPriority) &&
                item.tags.containsAll(wanted) && unwanted.none { it in item.tags } &&
                (under == null || item.anchors.any { isUnder(it.path, under) }) &&
                (!lost || item.anyLost) &&
                needles.all { item.title.contains(it, ignoreCase = true) || item.details.contains(it, ignoreCase = true) }
        }
        // One item per line: pretty printing would triple the size of a list that is meant to be read whole.
        if (compact) {
            return if (found.isEmpty()) "[]" else found.joinToString(",\n", "[\n", "\n]") { compactRow(it).toString() }
        }
        val array = JsonArray()
        found.forEach { array.add(summary(it)) }
        return TodoJson.toText(array)
    }

    fun get(id: String): String {
        flushAnchors()
        val item = item(id)
        val json = TodoJson.encodeItem(item)
        // Written in the order of item.anchors.
        val encoded = json.getAsJsonArray("anchors")
        item.anchors.forEachIndexed { index, anchor ->
            if (anchor.isFile || anchor.lost) return@forEachIndexed
            service.refresh(anchor.path)
            val text = runReadActionBlocking { service.readText(anchor.path) } ?: return@forEachIndexed
            val lines = AnchorResolver.lines(text)
            if (anchor.endLine <= lines.size) {
                encoded[index].asJsonObject
                    .addProperty("code", lines.subList(anchor.startLine - 1, anchor.endLine).joinToString("\n"))
            }
        }
        return TodoJson.toText(json)
    }

    fun create(
        title: String, details: String?, priority: String?, tags: String?,
        path: String?, startLine: Int?, endLine: Int?, places: String? = null,
    ): String {
        if (title.isBlank()) throw TodoToolError("title must not be empty")
        val parsedPriority = priority?.let(::parsePriority) ?: Priority.P2
        if (path == null && (startLine != null || endLine != null)) {
            throw TodoToolError("path is required when startLine or endLine is given")
        }
        val first = path?.let { buildAnchor(Place(filePath(it), startLine, endLine)) }
        val anchors = (listOfNotNull(first) + parsePlaces(places).map(::buildAnchor)).distinctBy(Anchor::place)
        val draft = Draft(title, details.orEmpty(), parsedPriority, Tags.parseList(tags.orEmpty()), Author.AGENT, anchors)
        return TodoJson.toText(TodoJson.encodeItem(storeCall { store.create(draft) }))
    }

    fun update(
        id: String, title: String?, details: String?, priority: String?, tags: String?, status: String?,
        path: String? = null, startLine: Int? = null, endLine: Int? = null, places: String? = null,
    ): String {
        val parsedPriority = priority?.let(::parsePriority)
        val parsedStatus = status?.let(::parseStatus)
        val parsedTags = tags?.let(Tags::parseList)
        val moves = path != null || startLine != null || endLine != null
        if (moves && places != null) throw TodoToolError("pass either places or path, startLine and endLine, not both")
        if (places != null) {
            // The places an item has are compared as they are now, unsaved edits included.
            flushAnchors()
            val current = item(id).anchors
            val wanted = parsePlaces(places).map { place -> current.firstOrNull(place::isAt) ?: buildAnchor(place) }
            onTracker { it.change(id) { wanted } }
        } else if (moves) {
            val current = item(id).anchors
            val file = path?.let(::filePath)
            val index = when {
                current.size < 2 -> 0
                file == null -> throw TodoToolError(
                    "$id is attached to ${current.size} places: give path to say which one moves, or places to set them all"
                )
                else -> current.indices.singleOrNull { current[it].path == file } ?: throw TodoToolError(
                    "$id does not have exactly one place in $file: pass places to set the whole list of places"
                )
            }
            val anchorPath = file ?: current.firstOrNull()?.path
                ?: throw TodoToolError("path is required to attach $id to code: it is a plain note")
            val anchor = buildAnchor(Place(anchorPath, startLine, endLine))
            onTracker { it.attach(id, anchor, index) }
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

    fun comment(id: String, text: String): String {
        if (text.isBlank()) throw TodoToolError("text must not be empty")
        return TodoJson.toText(TodoJson.encodeItem(storeCall { store.comment(id, Author.AGENT, text) }))
    }

    /** A place as a tool is given it: a file, with lines or, for the whole file, without. */
    private data class Place(val path: String, val startLine: Int?, val endLine: Int?) {
        fun isAt(anchor: Anchor): Boolean = anchor.path == path &&
            if (startLine == null) anchor.isFile else anchor.startLine == startLine && anchor.endLine == (endLine ?: startLine)
    }

    /** Reads places written `path`, `path:12` or `path:12-20`, separated by commas or line breaks. */
    private fun parsePlaces(places: String?): List<Place> =
        places.orEmpty().split(Regex("[,\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
            val lines = Regex("^(.+):(\\d+)(?:-(\\d+))?$").matchEntire(entry)
            when {
                lines != null -> {
                    val (file, start, end) = lines.destructured
                    Place(filePath(file), start.toIntOrNull() ?: badPlace(entry), if (end.isEmpty()) null else end.toIntOrNull() ?: badPlace(entry))
                }
                ':' in entry -> badPlace(entry)
                else -> Place(filePath(entry), null, null)
            }
        }

    private fun badPlace(entry: String): Nothing =
        throw TodoToolError("\"$entry\" is not a place: write path, path:12 or path:12-20")

    private fun buildAnchor(place: Place): Anchor {
        val path = place.path
        if (place.startLine == null && place.endLine != null) throw TodoToolError("startLine is required when endLine is given")
        // An agent often edits a file and files a TODO on it straight away.
        service.refresh(path)
        val text = runReadActionBlocking { service.readText(path) }
            ?: throw TodoToolError("file not found in the project: $path (use a path relative to the project root)")
        val start = place.startLine ?: return Anchor.file(path)
        val end = place.endLine ?: start
        val count = AnchorResolver.lines(text).size
        if (start < 1 || end < start || end > count) {
            throw TodoToolError("lines $start-$end are outside $path, which has $count lines")
        }
        return AnchorResolver.capture(path, text, start, end)
    }

    private fun summary(item: TodoItem): JsonObject {
        val json = TodoJson.encodeItem(item)
        json.getAsJsonArray("anchors")?.forEach {
            it.asJsonObject.apply {
                remove("text")
                remove("before")
                remove("after")
            }
        }
        return json
    }

    private fun compactRow(item: TodoItem): JsonObject {
        val json = JsonObject()
        json.addProperty("id", item.id)
        json.addProperty("title", item.title)
        json.addProperty("priority", item.priority.json)
        json.addProperty("status", item.status.json)
        json.add("tags", JsonArray().also { a -> item.tags.forEach(a::add) })
        if (item.anchors.isNotEmpty()) json.addProperty("at", item.anchors.joinToString(", ") { it.place })
        if (item.anyLost) json.addProperty("lost", true)
        return json
    }

    private fun words(value: String?): List<String> =
        value.orEmpty().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    /** A file or directory as anchors spell it: forward slashes, relative, no slash at the end. "" is the root. */
    private fun directory(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./").trim('/').let { if (it == ".") "" else it }

    private fun isUnder(file: String, directory: String): Boolean =
        directory.isEmpty() || file == directory || file.startsWith("$directory/")

    /** A file as anchors spell it: forward slashes, relative to the project root. */
    private fun filePath(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

    private fun item(id: String): TodoItem =
        readable().firstOrNull { it.id == id } ?: throw TodoToolError("there is no item with id $id")

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

    /** Changes anchors on the event thread, where the tracker keeps its markers. */
    private fun onTracker(change: (AnchorTracker) -> Unit) {
        var failure: StoreException? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                if (!project.isDisposed) change(project.service<AnchorTracker>())
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
