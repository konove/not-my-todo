package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Effort
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
    val effort: Effort? = null,
)

/** A status that is shown and not yet written: [to] in place of [from], which the file has, set at the time [updated]. */
data class Hold(val from: Status, val to: Status, val updated: String)

/** Keeps the holds of a store beyond its life, so that they are there when the IDE is started again. */
interface HoldKeeper {
    fun load(): Map<String, Hold>

    fun save(holds: Map<String, Hold>)

    /** Keeps nothing: the holds last as long as the store. */
    object None : HoldKeeper {
        override fun load(): Map<String, Hold> = emptyMap()

        override fun save(holds: Map<String, Hold>) {}
    }
}

/**
 * Holds the items of one `.todos/items.json`. The file is the source of truth: it is re-read
   * before every change, and written whole after every change. A change that is only kept in memory,
 * such as the lines an anchor has moved to, is written when its item is saved for another reason:
 * the file then does not change with every edit of the code above an anchor. A status that is held
 * (see [hold]) is written with the next change to the file, whichever item that change is to.
 */
class ItemStore(file: Path, private val keeper: HoldKeeper = HoldKeeper.None, private val clock: () -> Instant = Instant::now) {
    @Volatile
    var file: Path = file
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var data = TodoFile()

    /** The items as the file has them: [data] without the changes that are only kept in memory. */
    private var saved = TodoFile()

    /** The statuses that wait for the next write, by item id. [data] has them, [saved] does not. */
    private var held: Map<String, Hold> = keeper.load()
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
            // A hold is on an item of this file, and is not taken to another.
            if (held.isNotEmpty() && broken == null) {
                write(data)
                saved = data
                keep(emptyMap())
            }
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

    /**
     * Changes item [id]. Without [touch] its updated time stays. Without [persist] the change is
     * kept in memory and written when the item is next saved for another reason.
     */
    fun update(id: String, touch: Boolean = true, persist: Boolean = true, change: (TodoItem) -> TodoItem): TodoItem =
        mutate(persist) { updated(it, id, touch, change) }

    /**
     * Sets the status of item [id] without writing it: it is shown from now on, here and when the
     * IDE is started again, and written with the next change to the file. So accepting a fix that is
     * already committed does not leave the file changed on its own. When the file brings the item
     * another status before that, the file is right and the hold is dropped.
     */
    fun hold(id: String, status: Status): TodoItem = mutate(persist = false) { f ->
        val (next, item) = updated(f, id, true) { it.copy(status = status) }
        val onDisk = saved.items.first { it.id == id }
        if (onDisk.status == status) {
            // Set back to what the file says: nothing is left to write.
            keep(held - id)
            val back = item.copy(updated = onDisk.updated)
            next.copy(items = next.items.map { if (it.id == id) back else it }) to back
        } else {
            keep(held + (id to Hold(onDisk.status, status, item.updated)))
            next to item
        }
    }

    private fun keep(holds: Map<String, Hold>) {
        if (holds == held) return
        held = holds
        keeper.save(holds)
    }

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
            source = draft.source.said(), toDecide = draft.toDecide.said(), effort = draft.effort,
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
     * The item no longer waits for me: its [Tags.NEEDS_DECISION] tag goes, and what was to be decided stays.
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
        return update(id) { it.copy(decisions = it.decisions + added, tags = it.tags - Tags.NEEDS_DECISION) }
    }

    /**
     * Deletes item [id] and returns it. [check] is given the item and all the items there are, and
     * throws a [StoreException] to turn the delete down.
     */
    fun delete(id: String, check: (TodoItem, List<TodoItem>) -> Unit = { _, _ -> }): TodoItem =
        mutate { f ->
            val gone = f.items.firstOrNull { it.id == id } ?: throw StoreException("there is no item with id $id")
            check(gone, f.items)
            // Nothing is left pointing at an item that is gone.
            val left = f.items.filterNot { it.id == id }.map {
                if (id !in it.blockedBy && it.duplicateOf != id && it.parent != id) it
                else it.copy(
                    blockedBy = it.blockedBy - id,
                    duplicateOf = it.duplicateOf.takeIf { d -> d != id },
                    parent = it.parent.takeIf { p -> p != id },
                )
            }
            f.copy(items = left) to gone
        }

    private fun <T> mutate(persist: Boolean = true, block: (TodoFile) -> Pair<TodoFile, T>): T {
        var changed = false
        try {
            return synchronized(this) {
                changed = load()
                broken?.let { throw StoreException("$file cannot be read: $it") }
                val (next, result) = block(data)
                if (next != data) {
                    if (persist) {
                        // Only what this change touched, and what is held for the next write, is written as it is in memory.
                        val before = data.items.associateBy { it.id }
                        val onDisk = saved.items.associateBy { it.id }
                        val out = next.copy(items = next.items.map { if (before[it.id] == it && it.id !in held) onDisk[it.id] ?: it else it })
                        write(out)
                        saved = out
                        keep(emptyMap())
                    }
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
            saved = TodoFile()
            loaded = false
            return changed
        }
        if (loaded && text == diskText) return false
        loaded = true
        diskText = text
        try {
            val read = if (text == null) TodoFile() else TodoJson.decode(text)
            // An item the file did not change keeps what is only in memory.
            val was = saved.items.associateBy { it.id }
            val live = data.items.associateBy { it.id }
            // A hold stays while the file has the status it was made on.
            val holds = held.filter { (id, hold) -> read.items.any { it.id == id && it.status == hold.from } }
            data = read.copy(items = read.items.map { item ->
                val now = if (was[item.id] == item) live[item.id] ?: item else item
                holds[item.id]?.let { now.copy(status = it.to, updated = it.updated) } ?: now
            })
            saved = read
            keep(holds)
            broken = null
        } catch (e: TodoFormatException) {
            data = TodoFile()
            saved = TodoFile()
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
