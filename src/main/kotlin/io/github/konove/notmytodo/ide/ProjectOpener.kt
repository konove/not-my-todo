package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.model.TodoFormatException
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.model.TodoJson
import io.github.konove.notmytodo.store.Import
import io.github.konove.notmytodo.store.ImportResult
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.Resolution
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens the store project of an IDE project's folder; with none, it is pending until the first write makes it.
 * The items 0.2 kept in an items file there are imported into a project made for them first, under the same lock, so that no other writer can
 * hand out their ids in between. A file that cannot be read stops the project being made: the
 * store's server then goes on leaving that folder to the IDE, and the user can fix the file.
 */
class ProjectOpener(private val store: Store) {
    /** What became of the items file. */
    sealed interface Legacy {
        val file: Path

        data class Imported(override val file: Path, val count: Int) : Legacy

        /** Ids the project already has for other items: nothing was written. */
        data class Clash(override val file: Path, val ids: List<String>) : Legacy

        /** The project holds the items of another file already, such as the one of another clone. */
        data class Elsewhere(override val file: Path, val other: String) : Legacy

        /** The file the project's items came from has changed since: the store does not read it. [sha256] is what it holds now. */
        data class Changed(override val file: Path, val sha256: String) : Legacy
    }

    /**
     * The project's [items], the folder its anchors are relative to, and what became of the items file, when there is something to say.
     * [pending] is the folder's resolution while it has no project: [items] is then empty, refuses writes, and has the key the project would get.
     */
    class Opened(val items: ItemStore, val root: Path, val legacy: Legacy?, val pending: Resolution? = null)

    /**
     * Opens the project of [base], importing [legacy] when it is there. [held] holds what 0.2 showed in
     * place of the file's statuses, by item id. Throws
     * [StoreException] when the project cannot be opened.
     */
    fun open(base: Path, legacy: Path, held: Map<String, HeldStatuses.Held> = emptyMap()): Opened {
        if (!Files.isRegularFile(legacy)) {
            val resolution = store.resolve(base)
            val root = rootOf(resolution, base)
            val repo = resolution.root
            val record = resolution.record ?: if (repo == null) store.projects().firstOrNull { it.key == Store.INBOX } else null
            if (record != null) return Opened(store.items(record.key), root, null)
            // The first write makes the project, as the store's server does: opening a folder writes nothing.
            val key = if (repo == null) Store.INBOX else pendingKey(repo)
            return Opened(ItemStore.unavailable(store.dir(key), store.lock, PENDING), root, null, resolution)
        }
        // Resolved under the lock, so that a project another process made for the folder in between is seen with its import.
        return store.lock.withLock {
            val resolution = store.resolve(base)
            importing(resolution, base, rootOf(resolution, base), legacy, held)
        }
    }

    /**
     * The key the first write would give the project of [repo], as [Store.ensure] picks it. Refuses as it does while the
     * project.json of the folder's own name cannot be read: it may be this folder's, and another beside it would split its items.
     */
    private fun pendingKey(repo: Path): String {
        val base = Store.keyFor(repo, emptySet())
        store.problem(base)?.let {
            throw StoreException("${store.dir(base).path.resolve("project.json")} cannot be read ($it), and it may be the project of $repo. Fix project.json there.")
        }
        val projects = store.root.resolve("projects")
        val taken = if (!Files.isDirectory(projects)) emptySet() else Files.list(projects).use { s ->
            s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList().toSet()
        }
        return Store.keyFor(repo, taken)
    }

    private fun rootOf(resolution: Resolution, base: Path): Path = resolution.root ?: base.toAbsolutePath().normalize()

    /**
     * The file is decoded only when it is going to be imported. Once its items are in the store it is
     * no longer read, as the balloon said, so a kept file that broke since does not stop the project opening.
     */
    private fun importing(resolution: Resolution, base: Path, root: Path, file: Path, held: Map<String, HeldStatuses.Held>): Opened {
        val path = file.toAbsolutePath().normalize().toString()
        // A folder of no repository goes into Inbox, whose record says what it holds already.
        val before = resolution.record?.importedFrom ?: if (resolution.root == null) store.inbox().importedFrom else null
        if (before != null || store.projects().any { it.importedFrom?.path == path }) {
            val key = store.ensure(resolution).record.key
            return Opened(store.items(key), root, before?.let { kept(file, path, it.path, it.sha256) })
        }
        val bytes = try {
            Files.readAllBytes(file)
        } catch (e: IOException) {
            throw StoreException("$file cannot be read: ${e.message}")
        }
        val read = try {
            TodoJson.decode(String(bytes, Charsets.UTF_8))
        } catch (e: TodoFormatException) {
            throw StoreException("$file cannot be imported into the store: ${e.message}. Fix it, then press Reload in the Not My TODO window.")
        }
        val record = store.ensure(resolution).record
        val legacy = when (val result = imported(record.key, base, root, file, read.items, held, renumber = false)) {
            is ImportResult.Imported -> Legacy.Imported(file, result.count)
            is ImportResult.Clash -> Legacy.Clash(file, result.ids)
            ImportResult.AlreadyImported -> null
        }
        return Opened(store.items(record.key), root, legacy)
    }

    /** Runs the import of [file], whose items are [items], with what 0.2 held applied: the status, and the time it was set as `updated`. */
    private fun imported(
        key: String, base: Path, root: Path, file: Path, items: List<TodoItem>, held: Map<String, HeldStatuses.Held>, renumber: Boolean,
    ): ImportResult {
        // Keyed by the file's own ids: adjust runs before renumbering, as the statuses are looked up.
        val applied = items.mapNotNull { item -> held[item.id]?.takeIf { it.from == item.status }?.let { item.id to it } }.toMap()
        val rebased = rebase(base, root)
        return Import.run(store, key, file, applied.mapValues { it.value.to }, { item ->
            val time = applied[item.id]?.time
            rebased(if (time == null) item else item.copy(updated = time))
        }, renumber)
    }

    /**
     * Imports [legacy] into the project of [base] with new ids after its last item, for a file whose ids the project already
     * has. Throws [StoreException] when the file cannot be read or the project cannot be made.
     */
    fun importWithNewIds(base: Path, legacy: Path, held: Map<String, HeldStatuses.Held> = emptyMap()): Legacy = store.lock.withLock {
        val resolution = store.resolve(base)
        val root = rootOf(resolution, base)
        val read = try {
            TodoJson.decode(Files.readString(legacy))
        } catch (e: IOException) {
            throw StoreException("$legacy cannot be read: ${e.message}")
        } catch (e: TodoFormatException) {
            throw StoreException("$legacy cannot be imported into the store: ${e.message}")
        }
        val key = store.ensure(resolution).record.key
        when (val result = imported(key, base, root, legacy, read.items, held, renumber = true)) {
            is ImportResult.Imported -> Legacy.Imported(legacy, result.count)
            ImportResult.AlreadyImported -> throw StoreException("the items of $legacy are in the store already")
            is ImportResult.Clash -> throw StoreException("the items of $legacy clash with ${result.ids.joinToString(", ")}")
        }
    }

    /**
     * What to say of [file] when the project already holds the items of [other], with [sha256] the hash of what was imported:
     * [Legacy.Changed] when [file] is that file and has changed since, [Legacy.Elsewhere] when it is another file and not a
     * copy of it. The file is read for its hash only; one that cannot be read is not worth a word.
     */
    private fun kept(file: Path, path: String, other: String, sha256: String): Legacy? {
        val bytes = try {
            Files.readAllBytes(file)
        } catch (_: IOException) {
            return null
        }
        val now = Import.sha256(bytes)
        return when {
            now == sha256 -> null
            other == path -> Legacy.Changed(file, now)
            else -> Legacy.Elsewhere(file, other)
        }
    }

    companion object {
        /** Why the items of a folder with no project refuse a write: it is made by the first one, through the service. */
        const val PENDING = "this folder has no items in the store yet"

        /** Anchors 0.2 kept relative to [base], made relative to [root], a folder at or above it. */
        fun rebase(base: Path, root: Path): (TodoItem) -> TodoItem {
            val from = base.toAbsolutePath().normalize()
            val to = root.toAbsolutePath().normalize()
            if (from == to || !from.startsWith(to)) return { it }
            val prefix = to.relativize(from).joinToString("/")
            return { item -> item.copy(anchors = item.anchors.map { it.copy(path = "$prefix/${it.path}") }) }
        }
    }
}
