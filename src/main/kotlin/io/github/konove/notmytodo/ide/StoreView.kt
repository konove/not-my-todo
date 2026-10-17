package io.github.konove.notmytodo.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.ProjectRecord
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreException
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Which projects' items the tool window lists. */
enum class ProjectScope(val label: String) { THIS_PROJECT("This project"), INBOX("Inbox"), ALL("All projects") }

/**
 * The items of the store as this IDE project names them (see [ViewIds]). This project's are those
 * of [TodoService]; the others' are kept in a snapshot that is read again, off the event thread,
 * when the store changes, so that listing them or following a link to one reads no file. Every read
 * and write by view id goes to the project the id names.
 */
@Service(Service.Level.PROJECT)
class StoreView(private val project: Project) {
    /** The records of every project, and the items of every one but this. */
    private class Snapshot(val records: List<ProjectRecord>, val items: Map<String, List<TodoItem>>)

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptyMap())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val waiting = AtomicBoolean()

    /** An item's project, its id there, that project's items, and whether that project is this one. */
    data class Target(val key: String, val id: String, val items: ItemStore, val home: Boolean)

    private val service get() = TodoService.getInstance(project)
    private val stores get() = StoreService.getInstance().store

    /** This project's key. */
    val home: String get() = service.store.key

    /** Told after the other projects were read again, on whichever thread read them. A listener must not wait for the event thread. */
    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Reads every other project again, and tells the listeners. A store that cannot be listed leaves what was read before. */
    fun load() {
        try {
            val records = stores.projects()
            val mine = home
            snapshot = Snapshot(records, records.filter { it.key != mine }.associate { it.key to stores.items(it.key).items })
        } catch (e: IOException) {
            logger<StoreView>().warn("The store's projects cannot be read", e)
        } catch (e: UncheckedIOException) {
            logger<StoreView>().warn("The store's projects cannot be read", e)
        } catch (e: StoreException) {
            logger<StoreView>().warn("The store's projects cannot be read", e)
        }
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                logger<StoreView>().warn("A Not My TODO listener failed", e)
            }
        }
    }

    /** [load] on a pooled thread. While one waits to start, asking again asks for nothing more: it will read what the others would. */
    fun loadLater() {
        if (!waiting.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            waiting.set(false)
            if (!project.isDisposed) load()
        }
    }

    /** Every project's key: this one, Inbox, then the others by key. */
    fun keys(): List<String> {
        val all = snapshot.records.map { it.key }.toSet() + home
        return (listOf(home, Store.INBOX).filter { it in all } + all.sorted()).distinct()
    }

    fun items(scope: ProjectScope): List<TodoItem> {
        val mine = home
        val own = service.store.items
        fun of(key: String) = snapshot.items[key].orEmpty().map { ViewIds.shown(it, key, mine) }
        return when (scope) {
            ProjectScope.THIS_PROJECT -> own
            ProjectScope.INBOX -> if (mine == Store.INBOX) own else of(Store.INBOX)
            ProjectScope.ALL -> own + snapshot.items.keys.sorted().flatMap(::of)
        }
    }

    fun find(viewId: String): TodoItem? {
        val mine = home
        val ref = ViewIds.split(viewId, mine) ?: return null
        if (ref.project == mine) return service.store.find(ref.id)
        return snapshot.items[ref.project]?.firstOrNull { it.id == ref.id }?.let { ViewIds.shown(it, ref.project!!, mine) }
    }

    /** Where [viewId] lives; null when it is no id, or names a project the store has not. */
    fun target(viewId: String): Target? {
        val own = service.store
        val ref = ViewIds.split(viewId, own.key) ?: return null
        val key = ref.project!!
        if (key == own.key) return Target(key, ref.id, own, true)
        if (stores.project(key) == null) return null
        return Target(key, ref.id, stores.items(key), false)
    }

    fun update(viewId: String, change: (TodoItem) -> TodoItem): TodoItem {
        val t = target(viewId) ?: throw StoreException("there is no item with id $viewId")
        val saved = t.items.update(t.id, change = change)
        // The snapshot would show the item as it was until the watcher reported the write.
        if (!t.home) load()
        return ViewIds.shown(saved, t.key, home)
    }

    fun delete(viewId: String) {
        val t = target(viewId) ?: throw StoreException("there is no item with id $viewId")
        t.items.delete(t.id)
        if (!t.home) load()
    }

    /** Makes an item in project [key]. Inbox is made when there is none; another project must be there. */
    fun create(key: String, draft: Draft): TodoItem {
        val own = service.store
        if (key == own.key) return own.create(draft)
        if (key == Store.INBOX) stores.inbox() else if (stores.project(key) == null) throw StoreException("there is no project $key")
        val made = stores.items(key).create(draft)
        load()
        return ViewIds.shown(made, key, own.key)
    }

    /** How the items of every project refer to each other, by view id. */
    fun links(): Links = Links(items(ProjectScope.ALL), ::find)

    /** The folder of project [key]'s item files, which an agent without the tools can edit. */
    fun itemsDir(key: String): Path = stores.dir(key).path.resolve("items")

    /** Where project [key] is checked out on this machine: the first of its checkouts that is a folder here. */
    fun checkout(key: String): Path? {
        if (key == home) return service.root
        val record = snapshot.records.firstOrNull { it.key == key } ?: stores.project(key) ?: return null
        return record.checkouts.mapNotNull { runCatching { Path.of(it) }.getOrNull() }.firstOrNull { Files.isDirectory(it) }
    }

    /** A file of project [key], by the path its anchors have; another project's is read from its checkout. */
    fun readText(key: String, relativePath: String): String? {
        if (key == home) return service.readText(relativePath)
        val file = try {
            checkout(key)?.resolve(relativePath)
        } catch (_: InvalidPathException) {
            null
        } ?: return null
        return try {
            if (Files.isRegularFile(file)) Files.readString(file) else null
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        fun getInstance(project: Project): StoreView = project.service()
    }
}
