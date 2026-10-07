package io.github.konove.notmytodo.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.Alarm
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.anchor.LineRange
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.store.StoreException

/**
 * Keeps anchors pointing at their code. Open files are followed with range markers and written
 * back after edits settle; files changed on disk are re-resolved from their text.
 * Everything here runs on the event thread. The public functions take the read lock themselves:
 * a Swing listener, unlike an IDE action, is not given it.
 */
@Service(Service.Level.PROJECT)
class AnchorTracker(private val project: Project) : Disposable {
    private val service get() = TodoService.getInstance(project)
    private val store get() = service.store
    private val markers = HashMap<String, RangeMarker>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var started = false

    fun start() {
        if (started) return
        started = true
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = syncFile(file)
        })
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun fileContentReloaded(file: VirtualFile, document: Document) {
                dropMarkers(document)
                syncFile(file)
            }

            override fun beforeDocumentSaving(document: Document) = flush(document)
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // items.json may be part of the same batch (a branch switch); read it first.
                store.reload()
                events.forEach(::onFileEvent)
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (markers.values.none { it.document === event.document }) return
                alarm.cancelAllRequests()
                alarm.addRequest({ flushAll() }, FLUSH_DELAY_MS)
            }
        }, this)
        store.addListener {
            ApplicationManager.getApplication().invokeLater({ syncOpenFiles() }, project.disposed)
        }
        syncOpenFiles()
    }

    private fun syncOpenFiles() = runReadActionBlocking {
        val items = store.items.associateBy { it.id }
        markers.keys.filter { items[it]?.anchor == null }.forEach { markers.remove(it)?.dispose() }
        // A store change says nothing new about file contents, so lost anchors stay lost here;
        // they are looked for again when the file itself is opened, reloaded or changed on disk.
        FileEditorManager.getInstance(project).openFiles.forEach { syncFile(it, retryLost = false) }
    }

    /** Makes sure every item anchored in [file] has an up-to-date anchor, and a marker if the file is open. */
    fun syncFile(file: VirtualFile, retryLost: Boolean = true) = runReadActionBlocking { sync(file, retryLost) }

    private fun sync(file: VirtualFile, retryLost: Boolean) {
        if (project.isDisposed || !file.isValid || file.isDirectory) return
        val path = service.relativePath(file) ?: return
        val anchored = store.items.filter { it.anchor?.path == path }
        if (anchored.isEmpty()) return
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val text = document?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return
        for (item in anchored) {
            val anchor = item.anchor ?: continue
            if (anchor.lost && !retryLost) continue
            val marker = markers[item.id]
            if (marker != null && marker.isValid && marker.document === document && !anchor.lost) continue
            markers.remove(item.id)?.dispose()
            val range = AnchorResolver.resolve(anchor, text)
            if (range == null) {
                if (!anchor.lost) save(item) { it.copy(anchor = anchor.copy(lost = true)) }
                continue
            }
            if (document != null) markers[item.id] = createMarker(document, range)
            val fresh = AnchorResolver.capture(path, text, range.startLine, range.endLine)
            if (fresh != anchor) save(item) { it.copy(anchor = fresh) }
        }
    }

    fun flushAll() = runReadActionBlocking {
        markers.values.map { it.document }.distinct().forEach(::flush)
    }

    private fun flush(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document)
        val path = file?.takeIf { it.isValid }?.let { service.relativePath(it) }
        if (path == null) {
            // The document's file is gone; its markers describe nothing any more.
            dropMarkers(document)
            return
        }
        val text = document.text
        for ((id, marker) in markers.entries.filter { it.value.document === document }) {
            val item = store.find(id)
            val anchor = item?.anchor
            if (item == null || anchor == null || anchor.path != path) {
                markers.remove(id)?.dispose()
                continue
            }
            if (!marker.isValid || marker.startOffset == marker.endOffset) {
                markers.remove(id)?.dispose()
                save(item) { it.copy(anchor = anchor.copy(lost = true)) }
                continue
            }
            val startLine = document.getLineNumber(marker.startOffset) + 1
            val endLine = document.getLineNumber(marker.endOffset) + 1
            val fresh = AnchorResolver.capture(path, text, startLine, endLine)
            if (fresh != anchor) save(item) { it.copy(anchor = fresh) }
        }
    }

    /** Points the item at the editor's current selection. Returns false if nothing usable is selected. */
    fun reattach(itemId: String, editor: Editor): Boolean {
        val anchor = runReadActionBlocking { EditorAnchors.fromSelection(project, editor) } ?: return false
        if (store.find(itemId) == null) return false
        try {
            attach(itemId, anchor)
        } catch (_: StoreException) {
            return false
        }
        return true
    }

    /**
     * Points the item at [anchor] and returns it as saved. The marker that followed the old
     * place is dropped first, or the next flush would write the old lines back.
     */
    fun attach(itemId: String, anchor: Anchor): TodoItem {
        markers.remove(itemId)?.dispose()
        val saved = store.update(itemId) { it.copy(anchor = anchor) }
        service.findFile(anchor.path)?.let(::syncFile)
        return saved
    }

    private fun onFileEvent(event: VFileEvent) {
        when (event) {
            is VFileContentChangeEvent, is VFileCreateEvent -> {
                val file = event.file ?: return
                if (FileDocumentManager.getInstance().getCachedDocument(file) == null) syncFile(file)
            }
            is VFileDeleteEvent -> markLostUnder(event.path)
            is VFileMoveEvent -> movePaths(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent ->
                if (event.propertyName == VirtualFile.PROP_NAME) movePaths(event.oldPath, event.newPath)
        }
    }

    private fun movePaths(oldAbsolute: String, newAbsolute: String) {
        val from = service.relativePath(oldAbsolute) ?: return
        val to = service.relativePath(newAbsolute) ?: return
        for (item in store.items) {
            val anchor = item.anchor ?: continue
            val moved = when {
                anchor.path == from -> to
                anchor.path.startsWith("$from/") -> to + anchor.path.removePrefix(from)
                else -> continue
            }
            save(item) { it.copy(anchor = anchor.copy(path = moved)) }
        }
    }

    private fun markLostUnder(absolutePath: String) {
        val gone = service.relativePath(absolutePath) ?: return
        for (item in store.items) {
            val anchor = item.anchor ?: continue
            if (anchor.path != gone && !anchor.path.startsWith("$gone/")) continue
            markers.remove(item.id)?.dispose()
            if (!anchor.lost) save(item) { it.copy(anchor = anchor.copy(lost = true)) }
        }
    }

    private fun createMarker(document: Document, range: LineRange): RangeMarker =
        document.createRangeMarker(
            document.getLineStartOffset(range.startLine - 1),
            document.getLineEndOffset(range.endLine - 1),
        )

    private fun dropMarkers(document: Document) {
        markers.entries.filter { it.value.document === document }.forEach { markers.remove(it.key)?.dispose() }
    }

    /**
     * Anchor bookkeeping must never surface an error or change the item's updated time. It also
     * only applies if the anchor on disk is still the one [item] was read with, so a file changed
     * behind our back (another branch, an agent) is never overwritten from a stale snapshot.
     */
    private fun save(item: TodoItem, change: (TodoItem) -> TodoItem) {
        try {
            store.update(item.id, touch = false) { if (it.anchor != item.anchor) it else change(it) }
        } catch (_: StoreException) {
        }
    }

    override fun dispose() {
        markers.values.forEach { it.dispose() }
        markers.clear()
    }

    private companion object {
        const val FLUSH_DELAY_MS = 500
    }
}
