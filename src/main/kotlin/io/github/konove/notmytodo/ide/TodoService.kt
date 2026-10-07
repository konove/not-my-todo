package io.github.konove.notmytodo.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.konove.notmytodo.settings.TodoProjectSettings
import io.github.konove.notmytodo.settings.TodoSettingsListener
import io.github.konove.notmytodo.store.ItemStore
import io.github.konove.notmytodo.store.ItemsFilePath
import io.github.konove.notmytodo.store.StoreException
import java.nio.file.Path

/** Owns the project's item store and maps between project-relative paths and files. */
@Service(Service.Level.PROJECT)
class TodoService(private val project: Project) : Disposable {
    /** The project root, which the items file path is relative to. */
    val base: Path get() = Path.of(project.basePath ?: System.getProperty("java.io.tmpdir"))

    val store = ItemStore(configuredFile())

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // The store can be pointed at another file, so ask it each time.
                val target = store.file.toString().replace('\\', '/')
                if (events.any { it.path == target }) store.reload()
            }
        })
        // Make the file known to the IDE so its watcher reports outside edits.
        store.addListener { watchFile() }
        watchFile()
    }

    private fun watchFile() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(store.file)
        }
    }

    /** The file named in the project settings, or the default one if that path can no longer be used. */
    private fun configuredFile(): Path = try {
        ItemsFilePath.resolve(base, TodoProjectSettings.getInstance(project).itemsFile)
    } catch (_: StoreException) {
        base.resolve(TodoProjectSettings.DEFAULT_ITEMS_FILE)
    }

    /**
     * Stores the items in [relative] from now on. With [move] the current file is taken along
     * when there is none at the new place. Throws [StoreException] and changes nothing when the
     * path cannot be used.
     */
    fun useItemsFile(relative: String, move: Boolean) {
        val file = ItemsFilePath.resolve(base, relative)
        ItemsFilePath.requireWritable(base, file)
        store.moveTo(file, move)
        TodoProjectSettings.getInstance(project).itemsFile = relative.trim()
        watchFile()
        TodoSettingsListener.fire()
    }

    fun relativePath(file: VirtualFile): String? = relativePath(file.path)

    fun relativePath(absolutePath: String): String? {
        val roots = listOfNotNull(project.basePath) + ProjectRootManager.getInstance(project).contentRoots.map { it.path }
        for (root in roots) {
            if (absolutePath.startsWith("$root/")) return absolutePath.removePrefix("$root/")
        }
        return null
    }

    fun findFile(relativePath: String): VirtualFile? {
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath("$base/$relativePath")?.let { return it }
        }
        return ProjectRootManager.getInstance(project).contentRoots
            .firstNotNullOfOrNull { it.findFileByRelativePath(relativePath) }
    }

    /** Brings the IDE's view of one file up to date with the disk. Do not call inside a read action. */
    fun refresh(relativePath: String) {
        val base = project.basePath ?: return
        LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/$relativePath")?.refresh(false, false)
    }

    fun readText(relativePath: String): String? = runReadActionBlocking {
        val file = findFile(relativePath)?.takeIf { it.isValid && !it.isDirectory } ?: return@runReadActionBlocking null
        FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): TodoService = project.service()
    }
}
