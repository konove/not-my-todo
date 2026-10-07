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
 * back after edits settle; files changed on disk are re-resolved from their text. An anchor on a
 * whole file has no lines to follow: only its path is kept right, and it is lost when the file goes.
 * Everything here runs on the event thread. The public functions take the read lock themselves:
 * a Swing listener, unlike an IDE action, is not given it.
 */
@Service(Service.Level.PROJECT)
class AnchorTracker(private val project: Project) : Disposable {
    /** One anchor of one item, by its place in the item's list. */
    private data class Key(val itemId: String, val index: Int)

    /**
     * A marker and the anchor it stands for, as last read from or written to the store. The marker
     * is believed only while the store still holds that anchor at that place; when anchors were
     * added, removed or changed elsewhere it is dropped and the anchor resolved again.
     */
    private class Tracked(val marker: RangeMarker, var anchor: Anchor)

    private val service get() = TodoService.getInstance(project)
    private val store get() = service.store
    private val markers = HashMap<Key, Tracked>()
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
                if (markers.values.none { it.marker.document === event.document }) return
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
        markers.filter { (key, tracked) -> items[key.itemId]?.anchors?.getOrNull(key.index) != tracked.anchor }
            .keys.forEach(::drop)
        // A store change says nothing new about file contents, so lost anchors stay lost here;
        // they are looked for again when the file itself is opened, reloaded or changed on disk.
        FileEditorManager.getInstance(project).openFiles.forEach { syncFile(it, retryLost = false) }
    }

    /** Makes sure every anchor in [file] is up to date, and has a marker if the file is open. */
    fun syncFile(file: VirtualFile, retryLost: Boolean = true) = runReadActionBlocking { sync(file, retryLost) }

    private fun sync(file: VirtualFile, retryLost: Boolean) {
        if (project.isDisposed || !file.isValid || file.isDirectory) return
        val path = service.relativePath(file) ?: return
        val keys = store.items.flatMap { item ->
            item.anchors.indices.filter { item.anchors[it].path == path }.map { Key(item.id, it) }
        }
        if (keys.isEmpty()) return
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val text = document?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return
        for (key in keys) {
            // Read anew each time: saving one anchor of an item replaces the item.
            val anchor = anchorAt(key)?.takeIf { it.path == path } ?: continue
            if (anchor.lost && !retryLost) continue
            if (anchor.isFile) {
                // The file is here, so a whole-file anchor is found.
                if (anchor.lost) saveAnchor(key, anchor, anchor.copy(lost = false))
                continue
            }
            val tracked = markers[key]
            if (tracked != null && tracked.marker.isValid && tracked.marker.document === document &&
                tracked.anchor == anchor && !anchor.lost
            ) continue
            drop(key)
            val range = AnchorResolver.resolve(anchor, text)
            if (range == null) {
                if (!anchor.lost) saveAnchor(key, anchor, anchor.copy(lost = true))
                continue
            }
            val fresh = AnchorResolver.capture(path, text, range.startLine, range.endLine)
            val stored = (if (fresh != anchor) saveAnchor(key, anchor, fresh) else anchor) ?: continue
            if (document != null) markers[key] = Tracked(createMarker(document, range), stored)
        }
    }

    fun flushAll() = runReadActionBlocking {
        markers.values.map { it.marker.document }.distinct().forEach(::flush)
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
        for ((key, tracked) in markers.entries.filter { it.value.marker.document === document }) {
            val anchor = anchorAt(key)
            if (anchor == null || anchor != tracked.anchor || anchor.path != path) {
                drop(key)
                continue
            }
            val marker = tracked.marker
            if (!marker.isValid || marker.startOffset == marker.endOffset) {
                drop(key)
                saveAnchor(key, anchor, anchor.copy(lost = true))
                continue
            }
            val startLine = document.getLineNumber(marker.startOffset) + 1
            val endLine = document.getLineNumber(marker.endOffset) + 1
            val fresh = AnchorResolver.capture(path, text, startLine, endLine)
            if (fresh == anchor) continue
            val stored = saveAnchor(key, anchor, fresh)
            if (stored == null) drop(key) else tracked.anchor = stored
        }
    }

    /**
     * Points the item's anchor number [index] at the editor's selection; an item with no anchor
     * gets its first. Returns false if nothing usable is selected.
     */
    fun reattach(itemId: String, editor: Editor, index: Int = 0): Boolean {
        val anchor = runReadActionBlocking { EditorAnchors.fromSelection(project, editor) } ?: return false
        return changed(itemId) { attach(itemId, anchor, index) }
    }

    /** Adds the editor's selection as one more anchor, or its whole file when nothing is selected. */
    fun add(itemId: String, editor: Editor): Boolean {
        val anchor = runReadActionBlocking { EditorAnchors.fromSelection(project, editor) ?: EditorAnchors.wholeFile(project, editor) }
            ?: return false
        return changed(itemId) { change(itemId) { it + anchor } }
    }

    /** Takes the item's anchor number [index] away. The item becomes a plain note when it was the last. */
    fun remove(itemId: String, index: Int): Boolean =
        changed(itemId) { change(itemId) { anchors -> anchors.filterIndexed { i, _ -> i != index } } }

    private fun changed(itemId: String, action: () -> Unit): Boolean {
        if (store.find(itemId) == null) return false
        try {
            action()
        } catch (_: StoreException) {
            return false
        }
        return true
    }

    /** Puts [anchor] in place of the item's anchor number [index], or after the last when there is no such anchor. */
    fun attach(itemId: String, anchor: Anchor, index: Int = 0): TodoItem = change(itemId) { anchors ->
        if (index in anchors.indices) anchors.mapIndexed { i, old -> if (i == index) anchor else old } else anchors + anchor
    }

    /**
     * Rewrites the item's anchors and returns it as saved. The markers that followed the old
     * places are dropped first, or the next flush would write the old lines back.
     */
    fun change(itemId: String, change: (List<Anchor>) -> List<Anchor>): TodoItem {
        markers.keys.filter { it.itemId == itemId }.forEach(::drop)
        val saved = store.update(itemId) { it.copy(anchors = change(it.anchors).distinctBy(Anchor::place)) }
        saved.anchors.map { it.path }.distinct().mapNotNull(service::findFile).forEach(::syncFile)
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
        for (key in allKeys()) {
            val anchor = anchorAt(key) ?: continue
            val moved = when {
                anchor.path == from -> to
                anchor.path.startsWith("$from/") -> to + anchor.path.removePrefix(from)
                else -> continue
            }
            val stored = saveAnchor(key, anchor, anchor.copy(path = moved))
            // The marker went along with the document; it now stands for the anchor under its new path.
            if (stored != null) markers[key]?.anchor = stored
        }
    }

    private fun markLostUnder(absolutePath: String) {
        val gone = service.relativePath(absolutePath) ?: return
        for (key in allKeys()) {
            val anchor = anchorAt(key) ?: continue
            if (anchor.path != gone && !anchor.path.startsWith("$gone/")) continue
            drop(key)
            if (!anchor.lost) saveAnchor(key, anchor, anchor.copy(lost = true))
        }
    }

    private fun allKeys(): List<Key> = store.items.flatMap { item -> item.anchors.indices.map { Key(item.id, it) } }

    private fun anchorAt(key: Key): Anchor? = store.find(key.itemId)?.anchors?.getOrNull(key.index)

    private fun createMarker(document: Document, range: LineRange): RangeMarker =
        document.createRangeMarker(
            document.getLineStartOffset(range.startLine - 1),
            document.getLineEndOffset(range.endLine - 1),
        )

    private fun drop(key: Key) {
        markers.remove(key)?.marker?.dispose()
    }

    private fun dropMarkers(document: Document) {
        markers.filter { it.value.marker.document === document }.keys.forEach(::drop)
    }

    /**
     * Writes [fresh] in place of [expected] and returns it as stored, or null when it was not
     * written. Anchor bookkeeping must never surface an error or change the item's updated time.
     * It also only applies if the anchor on disk is still [expected], so a file changed behind our
     * back (another branch, an agent) is never overwritten from a stale snapshot.
     */
    private fun saveAnchor(key: Key, expected: Anchor, fresh: Anchor): Anchor? {
        val wanted = fresh.copy(unknown = expected.unknown)
        return try {
            val saved = store.update(key.itemId, touch = false) {
                if (it.anchors.getOrNull(key.index) != expected) it
                else it.copy(anchors = it.anchors.mapIndexed { i, old -> if (i == key.index) wanted else old })
            }
            saved.anchors.getOrNull(key.index)?.takeIf { it == wanted }
        } catch (_: StoreException) {
            null
        }
    }

    override fun dispose() {
        markers.values.forEach { it.marker.dispose() }
        markers.clear()
    }

    private companion object {
        const val FLUSH_DELAY_MS = 500
    }
}
