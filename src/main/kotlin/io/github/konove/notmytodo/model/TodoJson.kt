package io.github.konove.notmytodo.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

class TodoFormatException(message: String) : Exception(message)

object TodoJson {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun toText(element: JsonElement): String = gson.toJson(element)

    fun encode(file: TodoFile): String {
        val root = JsonObject()
        root.addProperty("version", file.version)
        root.addProperty("nextId", file.nextId)
        val items = JsonArray()
        file.items.sortedBy { it.number }.forEach { items.add(encodeItem(it)) }
        root.add("items", items)
        return toText(root) + "\n"
    }

    fun encodeItem(item: TodoItem): JsonObject {
        val o = JsonObject()
        o.addProperty("id", item.id)
        o.addProperty("title", item.title)
        o.addProperty("details", item.details)
        o.addProperty("priority", item.priority.json)
        o.add("tags", strings(item.tags))
        o.addProperty("status", item.status.json)
        o.addProperty("author", item.author.json)
        o.addProperty("created", item.created)
        o.addProperty("updated", item.updated)
        item.anchor?.let { a ->
            val ao = JsonObject()
            ao.addProperty("path", a.path)
            ao.addProperty("startLine", a.startLine)
            ao.addProperty("endLine", a.endLine)
            ao.addProperty("text", a.text)
            ao.add("before", strings(a.before))
            ao.add("after", strings(a.after))
            ao.addProperty("lost", a.lost)
            o.add("anchor", ao)
        }
        return o
    }

    fun decode(text: String): TodoFile {
        if (text.isBlank()) return TodoFile()
        val root = try {
            JsonParser.parseString(text)
        } catch (e: JsonParseException) {
            throw TodoFormatException("not valid JSON: ${e.message}")
        }
        if (!root.isJsonObject) throw TodoFormatException("the top level must be a JSON object")
        val obj = root.asJsonObject
        val version = obj.int("version") ?: 1
        if (version != 1) throw TodoFormatException("unsupported version $version")
        val array = obj.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        val items = array.mapIndexed { index, element -> decodeItem(element, index) }
        val duplicate = items.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw TodoFormatException("id ${duplicate.key} is used more than once")
        val highest = items.maxOfOrNull { it.number } ?: 0
        val nextId = maxOf(obj.int("nextId") ?: 1, highest + 1)
        return TodoFile(1, nextId, items.sortedBy { it.number })
    }

    private fun decodeItem(element: JsonElement, index: Int): TodoItem {
        if (!element.isJsonObject) throw TodoFormatException("item ${index + 1} is not an object")
        val o = element.asJsonObject
        val id = o.str("id") ?: throw TodoFormatException("item ${index + 1} has no id")
        if (ItemId.parse(id) == null) throw TodoFormatException("item ${index + 1} has a bad id \"$id\" (expected T-1, T-2, ...)")
        val title = o.str("title")?.trim().orEmpty()
        if (title.isEmpty()) throw TodoFormatException("item $id has no title")
        val priority = o.str("priority")?.let {
            Priority.fromJson(it) ?: throw TodoFormatException("item $id has a bad priority \"$it\"")
        } ?: Priority.P2
        val status = o.str("status")?.let {
            Status.fromJson(it) ?: throw TodoFormatException("item $id has a bad status \"$it\"")
        } ?: Status.OPEN
        val author = o.str("author")?.let {
            Author.fromJson(it) ?: throw TodoFormatException("item $id has a bad author \"$it\"")
        } ?: Author.USER
        val anchor = o.get("anchor")?.takeIf { it.isJsonObject }?.asJsonObject?.let { decodeAnchor(it, id) }
        return TodoItem(
            id = id, title = title, details = o.str("details").orEmpty(), priority = priority,
            tags = Tags.normalizeAll(o.strList("tags")), status = status, author = author,
            created = o.str("created").orEmpty(), updated = o.str("updated").orEmpty(), anchor = anchor,
        )
    }

    private fun decodeAnchor(o: JsonObject, id: String): Anchor {
        val path = o.str("path")?.takeIf { it.isNotBlank() } ?: throw TodoFormatException("item $id has an anchor with no path")
        val start = o.int("startLine") ?: throw TodoFormatException("item $id has an anchor with no startLine")
        val end = o.int("endLine") ?: start
        if (start < 1 || end < start) throw TodoFormatException("item $id has a bad anchor line range $start-$end")
        val lost = o.get("lost")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        return Anchor(path, start, end, o.str("text").orEmpty(), o.strList("before"), o.strList("after"), lost)
    }

    private fun strings(values: List<String>): JsonArray = JsonArray().also { a -> values.forEach(a::add) }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? {
        val e = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
        return try {
            e.asInt
        } catch (_: NumberFormatException) {
            throw TodoFormatException("\"$key\" must be a whole number")
        }
    }

    private fun JsonObject.strList(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
            ?: emptyList()
}
