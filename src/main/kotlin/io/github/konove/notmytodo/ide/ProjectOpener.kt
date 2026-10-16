package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.model.Status
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
 * Opens the store project of an IDE project's folder, made when there is none. The items 0.2 kept in
 * an items file there are imported into it first, under the same lock, so that no other writer can
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
    }

    /** The project's [items], the folder its anchors are relative to, and what became of the items file, when there is something to say. */
    class Opened(val items: ItemStore, val root: Path, val legacy: Legacy?)

    /**
     * Opens the project of [base], importing [legacy] when it is there. [held] holds the statuses 0.2
     * showed in place of the file's, by item id: the file's status and the one shown. Throws
     * [StoreException] when the project cannot be opened.
     */
    fun open(base: Path, legacy: Path, held: Map<String, Pair<Status, Status>> = emptyMap()): Opened {
        val resolution = store.resolve(base)
        val root = resolution.root ?: base.toAbsolutePath().normalize()
        if (!Files.isRegularFile(legacy)) return Opened(store.items(store.ensure(resolution).record.key), root, null)
        return store.lock.withLock { importing(resolution, base, root, legacy, held) }
    }

    private fun importing(resolution: Resolution, base: Path, root: Path, file: Path, held: Map<String, Pair<Status, Status>>): Opened {
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
        val path = file.toAbsolutePath().normalize().toString()
        val before = record.importedFrom
        val legacy = when {
            before == null -> {
                val statuses = read.items.mapNotNull { item ->
                    held[item.id]?.takeIf { it.first == item.status }?.let { item.id to it.second }
                }.toMap()
                when (val result = Import.run(store, record.key, file, statuses, rebase(base, root))) {
                    is ImportResult.Imported -> Legacy.Imported(file, result.count)
                    is ImportResult.Clash -> Legacy.Clash(file, result.ids)
                    ImportResult.AlreadyImported -> null
                }
            }
            before.path == path || before.sha256 == Import.sha256(bytes) -> null
            else -> Legacy.Elsewhere(file, before.path)
        }
        return Opened(store.items(record.key), root, legacy)
    }

    companion object {
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
