package io.github.konove.notmytodo.ide

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFrame
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

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
    private val relay: () -> Unit = { tellUnreadable(); fire() }

    private val notices = ImportNotices(project)

    /** Set while a reload waits to start: asking again in the meantime asks for nothing more. */
    private val reloading = AtomicBoolean()

    /** The unreadable item files already said, by project and file, so that each is said once. */
    private val toldUnreadable = ConcurrentHashMap.newKeySet<String>()

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
        tellUnreadable()
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // A folder another process made reports the changes of its files only once the IDE has seen
                // them all. They are looked at on a pooled thread: this may run on the event thread.
                val projects = projectsPath()
                events.filterIsInstance<VFileCreateEvent>().filter { it.isDirectory && it.path.startsWith(projects) }
                    .mapNotNull { it.file }.forEach { dir ->
                        ApplicationManager.getApplication().executeOnPooledThread { if (dir.isValid) StoreService.getInstance().seeAll(dir) }
                    }
                changed(events.map { it.path })
            }
        })
        // The application's bus: this topic is not passed on to the projects' buses.
        val app = ApplicationManager.getApplication().messageBus.connect(this)
        app.subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
            // What the watcher missed is read when the user comes back to the IDE, say from the terminal an agent ran in.
            override fun applicationActivated(ideFrame: IdeFrame) = reloadLater()
        })
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

    /**
     * Asks for the items to be read again when [paths], changed on disk, include a file of the store
     * project. While the project could not be opened, any change in the store may be the one that
     * lets it. A change to another project is read into [StoreView]. Returns true when it asked for this project.
     */
    internal fun changed(paths: List<String>): Boolean {
        val mine = store.dir.path.toString().replace('\\', '/')
        val projects = projectsPath()
        fun ours(p: String) = p == mine || p.startsWith("$mine/")
        if (paths.any { it.startsWith(projects) && !ours(it) }) StoreView.getInstance(project).loadLater()
        val wanted = if (available) mine else projects.removeSuffix("/")
        if (paths.none { it == wanted || it.startsWith("$wanted/") }) return false
        reloadLater()
        return true
    }

    /** Reads the items again away from the event thread: it reads files, and opening the project again may wait for the lock. */
    private fun reloadLater() {
        if (!reloading.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            reloading.set(false)
            if (!project.isDisposed) reload()
        }
    }

    private fun projectsPath(): String = StoreService.getInstance().store.root.resolve("projects").toString().replace('\\', '/') + "/"

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
        relay()
        // This project's key may be another now: the others are those but it.
        StoreView.getInstance(project).loadLater()
    }

    /**
     * Renames the store project to [key]. Every link to it, in every project, is rewritten with it,
     * and the project is opened again under its new key. Throws [StoreException] and changes nothing
     * when the key cannot be used.
     */
    fun rename(key: String) {
        StoreService.getInstance().store.rename(store.key, key)
        reopen()
    }

    /** The folder of the store project's item files, which an agent without the tools can edit. */
    val itemsDir: Path get() = store.dir.path.resolve("items")

    /** How the project's items refer to each other and to the items of other projects, these as [StoreView] last read them. */
    fun links(): Links = Links(store.items) { StoreView.getInstance(project).find(it) }

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

    /** Says, once each, which item files of the store project cannot be read: they are left out of the list. */
    private fun tellUnreadable() {
        val items = store
        val fresh = items.unreadable.filter { toldUnreadable.add("${items.key}/${it.file}") }
        if (fresh.isEmpty()) return
        val title = if (fresh.size == 1) "An item file in the store cannot be read" else "${fresh.size} item files in the store cannot be read"
        val content = fresh.joinToString("<br>") { StringUtil.escapeXmlEntities("${it.file}: ${it.problem}") } +
            "<br>Such a file is left as it is and out of the list, and its id is not given out again."
        NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
            .createNotification(title, content, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Show the Items Folder") {
                RevealFileAction.openDirectory(items.dir.path.resolve("items"))
            })
            .notify(project)
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
