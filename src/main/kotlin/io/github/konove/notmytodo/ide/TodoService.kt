package io.github.konove.notmytodo.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.konove.notmytodo.model.Links
import io.github.konove.notmytodo.settings.TodoProjectSettings
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.ProjectDir
import io.github.konove.notmytodo.store.StoreException
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the store project of the IDE project, and maps between the paths its anchors have and files.
 * The project is found in the machine's store for the IDE project's folder, and made there when it
 * has none. The listeners are kept here and not on the store's items, so that they stay when the
 * project is opened again: after a rename, or once a project that could not be opened can be.
 */
@Service(Service.Level.PROJECT)
class TodoService(private val project: Project) : Disposable {
    /** The IDE project's folder. */
    val base: Path get() = Path.of(project.basePath ?: System.getProperty("java.io.tmpdir"))

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val relay: () -> Unit = { fire() }

    private val notices = ImportNotices(project)

    /** The folder anchors are relative to: the root of the repository, which may be a folder above [base]. */
    @Volatile
    var root: Path = base
        private set

    /** False while the store project could not be opened: [store] then holds nothing and says why. */
    @Volatile
    var available = false
        private set

    // Declared after everything open() sets, so that their initial values do not overwrite what it set.
    @Volatile
    var store: ItemStore = open()
        private set

    init {
        store.addListener(relay)
    }

    /** Told after every change to the items, on whichever thread made it. A listener must not wait for the event thread. */
    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /**
     * Reads the items again. A project that could not be opened, or whose folder is gone (renamed in
     * another window), is opened again instead. Returns true when the items may have changed.
     */
    fun reload(): Boolean {
        if (!available || !Files.isDirectory(store.dir.path)) {
            reopen()
            return true
        }
        return store.reload()
    }

    /** Finds the store project again: after it was renamed, or when it could not be opened before. */
    fun reopen() {
        synchronized(this) {
            val next = open()
            if (next !== store) {
                store.removeListener(relay)
                next.addListener(relay)
                store = next
            }
        }
        fire()
    }

    /** How the project's items refer to each other and to the items of other projects. */
    fun links(): Links = Links(store.items, StoreService.getInstance().store::find)

    /**
     * The store project of [base], made when there is none, with the items of the items file 0.2
     * kept there imported into it. It may wait for the store's lock, up to five seconds, when
     * another writer holds it.
     */
    private fun open(): ItemStore {
        val stores = StoreService.getInstance().store
        val held = project.service<HeldStatuses>()
        return try {
            val opened = ProjectOpener(stores).open(base, legacyFile(), held.held())
            root = opened.root
            available = true
            opened.legacy?.let {
                if (it is ProjectOpener.Legacy.Imported) held.clear()
                notices.tell(opened.items.key, base, it)
            }
            opened.items
        } catch (e: StoreException) {
            unavailable(e.message)
        } catch (e: IOException) {
            unavailable(e.message)
        } catch (e: UncheckedIOException) {
            unavailable(e.message)
        }
    }

    /** The items file of 0.2: .todos/items.json, or the file its project setting named. */
    private fun legacyFile(): Path {
        val named = TodoProjectSettings.getInstance(project).itemsFile.trim().ifEmpty { TodoProjectSettings.DEFAULT_ITEMS_FILE }
        return try {
            base.resolve(named).normalize()
        } catch (_: InvalidPathException) {
            base.resolve(TodoProjectSettings.DEFAULT_ITEMS_FILE)
        }
    }

    private fun unavailable(why: String?): ItemStore {
        available = false
        val stores = StoreService.getInstance().store
        return ItemStore.unavailable(ProjectDir(stores.root), stores.lock, why ?: "the store cannot be opened")
    }

    private fun fire() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                logger<TodoService>().warn("A Not My TODO listener failed", e)
            }
        }
    }

    private val rootPath: String get() = root.toString().replace('\\', '/')

    fun relativePath(file: VirtualFile): String? = relativePath(file.path)

    fun relativePath(absolutePath: String): String? {
        val roots = listOf(rootPath) + listOfNotNull(project.basePath) + ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        for (r in roots.distinct()) {
            if (absolutePath.startsWith("$r/")) return absolutePath.removePrefix("$r/")
        }
        return null
    }

    fun findFile(relativePath: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath("$rootPath/$relativePath")?.let { return it }
        return ProjectRootManager.getInstance(project).contentRoots
            .firstNotNullOfOrNull { it.findFileByRelativePath(relativePath) }
    }

    /** Brings the IDE's view of one file up to date with the disk. Do not call inside a read action. */
    fun refresh(relativePath: String) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath("$rootPath/$relativePath")?.refresh(false, false)
    }

    /** Brings the IDE's view of several files up to date with the disk, in one go. Do not call inside a read action. */
    fun refresh(relativePaths: Collection<String>) {
        val files = relativePaths.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByPath("$rootPath/$it") }
        if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
    }

    fun readText(relativePath: String): String? = runReadActionBlocking {
        val file = findFile(relativePath)?.takeIf { it.isValid && !it.isDirectory } ?: return@runReadActionBlocking null
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }

    override fun dispose() {
        store.removeListener(relay)
    }

    companion object {
        fun getInstance(project: Project): TodoService = project.service()
    }
}
