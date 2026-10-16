package io.github.konove.notmytodo.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreRoot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The store of this machine, one for the whole IDE: every open project shares its lock and its items. */
@Service(Service.Level.APP)
class StoreService : Disposable {
    val store: Store = Store(System.getProperty(ROOT_PROPERTY)?.takeIf { it.isNotBlank() }?.let(Path::of) ?: StoreRoot.default())

    private var watch: LocalFileSystem.WatchRequest? = null

    /**
     * Has the IDE's file watcher report what other processes, an agent's notmytodo-store server among
     * them, write to the store's projects. Only the projects folder is watched: the lock file beside
     * it must never be opened by this process. Not in tests, whose store is no one else's.
     * Takes a moment: not for the event thread.
     */
    @Synchronized
    fun watch() {
        if (watch != null || ApplicationManager.getApplication().isUnitTestMode) return
        val projects = store.root.resolve("projects")
        try {
            Files.createDirectories(projects)
        } catch (_: IOException) {
            return
        }
        watch = LocalFileSystem.getInstance().addRootToWatch(projects.toString(), true)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projects)?.let(::seeAll)
    }

    /**
     * Shows the IDE every file under [dir], a folder of the store's projects, once: the watcher reports
     * a change only for a file the IDE has seen, and a refresh looks only one level into a folder it
     * has not. Symbolic links are not followed, so nothing outside the folder is looked at. Lists
     * the whole folder: not for the event thread.
     */
    internal fun seeAll(dir: VirtualFile) {
        VfsUtilCore.visitChildrenRecursively(dir, object : VirtualFileVisitor<Any>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {})
        VfsUtil.markDirtyAndRefresh(true, true, true, dir)
    }

    override fun dispose() {
        watch?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
    }

    companion object {
        /** Names another store, as the tests do: they must not write into the user's own. */
        const val ROOT_PROPERTY = "notmytodo.storeRoot"

        fun getInstance(): StoreService = service()
    }
}
