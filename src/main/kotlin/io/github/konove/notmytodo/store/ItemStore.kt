package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.ItemId
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.Tags
import io.github.konove.notmytodo.model.TodoFile
import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

class StoreException(message: String) : Exception(message)

data class Draft(
    val title: String,
    val details: String = "",
    val priority: Priority = Priority.P2,
    val tags: List<String> = emptyList(),
    val author: Author = Author.USER,
    val anchors: List<Anchor> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    val duplicateOf: String? = null,
    val parent: String? = null,
    val source: String? = null,
    val toDecide: String? = null,
)

/**
 * Holds the items of one `.todos/items.json`. The file is the source of truth: it is re-read
 * before every change, and written whole after every change.
 */
class ItemStore(file: Path, private val clock: () -> Instant = Instant::now) {
    @Volatile
    var file: Path = file
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var data = TodoFile()
    private var broken: String? = null
    private var loaded = false
    private var diskText: String? = null

    init {
        synchronized(this) { load() }
    }

    val items: List<TodoItem>
        get() = synchronized(this) { if (broken != null) emptyList() else data.items }

    val error: String?
        get() = synchronized(this) { broken }

    fun find(id: String): TodoItem? = items.firstOrNull { it.id == id }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun reload(): Boolean {
        val changed = synchronized(this) { load() }
        if (changed) fire()
        return changed
    }

    /**
     * Switches to [newFile] and reads it. With [move], the current file is taken along first,
     * unless there already is a file at the new place: that one is never overwritten.
     */
    fun moveTo(newFile: Path, move: Boolean = false) {
        synchronized(this) {
            if (newFile == file) return
            if (move && Files.exists(file) && !Files.exists(newFile)) {
                try {
                    Files.createDirectories(newFile.parent)
                    Files.move(file, newFile)
                } catch (e: IOException) {
                    throw StoreException("$file cannot be moved to $newFile: ${e.message}")
                }
            }
            file = newFile
            loaded = false
            load()
        }
        fire()
    }

    fun create(draft: Draft): TodoItem = mutate { created(it, draft) }

    fun update(id: String, touch: Boolean = true, change: (TodoItem) -> TodoItem): TodoItem =
        mutate { updated(it, id, touch, change) }

    /** Makes several changes as one: they are written together or, when one of them fails, not at all. */
    fun <T> batch(changes: (Batch) -> T): T = mutate { f ->
        val batch = Batch(f)
        val result = changes(batch)
        batch.file to result
    }

    /** The changes of one [batch]. Each is made to what the ones before it left. */
    inner class Batch internal constructor(internal var file: TodoFile) {
        fun create(draft: Draft): TodoItem = created(file, draft).also { file = it.first }.second

        fun update(id: String, change: (TodoItem) -> TodoItem): TodoItem =
            updated(file, id, true, change).also { file = it.first }.second

        fun comment(id: String, author: Author, text: String): TodoItem = update(id, commented(author, text))
    }

    private fun created(f: TodoFile, draft: Draft): Pair<TodoFile, TodoItem> {
        val title = draft.title.trim()
        if (title.isEmpty()) throw StoreException("the title must not be empty")
        val now = timestamp()
        val item = TodoItem(
            id = ItemId.of(f.nextId), title = title, details = draft.details, priority = draft.priority,
            tags = Tags.normalizeAll(draft.tags), status = Status.OPEN, author = draft.author,
            created = now, updated = now, anchors = draft.anchors,
            blockedBy = draft.blockedBy.distinct(), duplicateOf = draft.duplicateOf, parent = draft.parent,
            source = draft.source.said(), toDecide = draft.toDecide.said(),
        )
        checkLinks(item, f.items + item)
        return f.copy(nextId = f.nextId + 1, items = f.items + item) to item
    }

    private fun updated(f: TodoFile, id: String, touch: Boolean, change: (TodoItem) -> TodoItem): Pair<TodoFile, TodoItem> {
        val old = f.items.firstOrNull { it.id == id } ?: throw StoreException("there is no item with id $id")
        val changed = change(old)
        val edited = changed.copy(
            id = old.id, author = old.author, created = old.created, updated = old.updated,
            title = changed.title.trim(), tags = Tags.normalizeAll(changed.tags), blockedBy = changed.blockedBy.distinct(),
            source = changed.source.said(), fixedIn = changed.fixedIn.said(), resolution = changed.resolution.said(),
            toDecide = changed.toDecide.said(),
            // A change may build the item or an anchor anew; what a newer plugin wrote stays either way.
            // An anchor built anew has no unknown fields of its own and takes those of the one it replaces.
            unknown = old.unknown,
            anchors = changed.anchors.mapIndexed { i, a ->
                val replaced = old.anchors.getOrNull(i)?.takeIf { it !in changed.anchors }
                if (a.unknown.isEmpty() && replaced != null) a.copy(unknown = replaced.unknown) else a
            },
        )
        if (edited.title.isEmpty()) throw StoreException("the title must not be empty")
        if (edited == old) return f to old
        // Links that were already there are left alone, so a file with a bad one can still be edited.
        if (edited.blockedBy != old.blockedBy || edited.duplicateOf != old.duplicateOf || edited.parent != old.parent) {
            checkLinks(edited, f.items.map { if (it.id == id) edited else it })
        }
        val saved = if (touch) edited.copy(updated = timestamp()) else edited
        return f.copy(items = f.items.map { if (it.id == id) saved else it }) to saved
    }

    /**
     * The links of [item], one of [items], must lead to other items that are there and must not come
     * back to it. Parts go one level deep: a parent is not a part of anything.
     */
    private fun checkLinks(item: TodoItem, items: List<TodoItem>) {
        val byId = items.associateBy { it.id }
        fun check(id: String, what: String) {
            if (id == item.id) throw StoreException("${item.id} cannot be $what itself")
            if (id !in byId) throw StoreException("there is no item with id $id for ${item.id} to be $what")
        }
        fun circles(first: String, next: (TodoItem) -> List<String>): Boolean {
            val seen = HashSet<String>()
            val left = ArrayDeque(listOf(first))
            while (left.isNotEmpty()) {
                val id = left.removeFirst()
                if (id == item.id) return true
                if (seen.add(id)) byId[id]?.let { left += next(it) }
            }
            return false
        }
        for (id in item.blockedBy) {
            check(id, "blocked by")
            if (circles(id) { it.blockedBy }) throw StoreException("${item.id} cannot be blocked by $id: $id waits for ${item.id}")
        }
        item.duplicateOf?.let { id ->
            check(id, "a duplicate of")
            if (circles(id) { listOfNotNull(it.duplicateOf) }) {
                throw StoreException("${item.id} cannot be a duplicate of $id: $id is a duplicate of ${item.id}")
            }
        }
        item.parent?.let { id ->
            check(id, "a part of")
            byId.getValue(id).parent?.let {
                throw StoreException("${item.id} cannot be a part of $id: $id is itself a part of $it, and parts go one level deep")
            }
            if (items.any { it.parent == item.id }) {
                throw StoreException("${item.id} cannot be a part of $id: it has parts of its own, and parts go one level deep")
            }
        }
    }

    /** Adds a note to the end of the item's comments. Nothing else of the item is read from the caller. */
    fun comment(id: String, author: Author, text: String): TodoItem = update(id, change = commented(author, text))

    private fun commented(author: Author, text: String): (TodoItem) -> TodoItem {
        val note = text.trim()
        if (note.isEmpty()) throw StoreException("the comment text must not be empty")
        return { it.copy(comments = it.comments + Comment(author, timestamp(), note)) }
    }

    /**
     * Adds what I was asked and what I answered to the end of the item's decisions, with the time.
     * Whether the item still waits for me is not changed: that is its tag.
     */
    fun decide(id: String, decisions: List<Decision>): TodoItem {
        if (decisions.isEmpty()) throw StoreException("there must be at least one decision")
        val now = timestamp()
        val added = decisions.map { d ->
            val question = d.question.trim()
            val answer = d.answer.trim()
            if (question.isEmpty()) throw StoreException("the question must not be empty")
            if (answer.isEmpty()) throw StoreException("the answer must not be empty")
            Decision(question, d.options.map { it.trim() }.filter { it.isNotEmpty() }, answer, now)
        }
        return update(id) { it.copy(decisions = it.decisions + added) }
    }

    fun delete(id: String) {
        mutate { f ->
            if (f.items.none { it.id == id }) throw StoreException("there is no item with id $id")
            // Nothing is left pointing at an item that is gone.
            val left = f.items.filterNot { it.id == id }.map {
                if (id !in it.blockedBy && it.duplicateOf != id && it.parent != id) it
                else it.copy(
                    blockedBy = it.blockedBy - id,
                    duplicateOf = it.duplicateOf.takeIf { d -> d != id },
                    parent = it.parent.takeIf { p -> p != id },
                )
            }
            f.copy(items = left) to Unit
        }
    }

    private fun <T> mutate(block: (TodoFile) -> Pair<TodoFile, T>): T {
        var changed = false
        try {
            return synchronized(this) {
                changed = load()
                broken?.let { throw StoreException("$file cannot be read: $it") }
                val (next, result) = block(data)
                if (next != data) {
                    write(next)
                    data = next
                    changed = true
                }
                result
            }
        } finally {
            if (changed) fire()
        }
    }

    /** Reads the file if it differs from what was last read or written. Returns true if state changed. */
    private fun load(): Boolean {
        val text = try {
            if (Files.exists(file)) Files.readString(file) else null
        } catch (e: IOException) {
            val message = "cannot read the file: ${e.message}"
            val changed = broken != message
            broken = message
            data = TodoFile()
            loaded = false
            return changed
        }
        if (loaded && text == diskText) return false
        loaded = true
        diskText = text
        try {
            data = if (text == null) TodoFile() else TodoJson.decode(text)
            broken = null
        } catch (e: TodoFormatException) {
            data = TodoFile()
            broken = e.message
        }
        return true
    }

    private fun write(next: TodoFile) {
        val text = TodoJson.encode(next)
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw StoreException("$file cannot be written: ${e.message}")
        }
        diskText = text
    }

    /** A text with something in it, trimmed; a blank one is as good as none. */
    private fun String?.said(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun timestamp(): String = clock().truncatedTo(ChronoUnit.SECONDS).toString()

    /** A listener that throws must not hide a change that is already on disk, or starve the others. */
    private fun fire() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                System.getLogger(ItemStore::class.java.name)
                    .log(System.Logger.Level.WARNING, "TODO store listener failed", e)
            }
        }
    }
}
