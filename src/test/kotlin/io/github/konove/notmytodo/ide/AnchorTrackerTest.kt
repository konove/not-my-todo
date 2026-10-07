package io.github.konove.notmytodo.ide

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.model.TodoItem
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ItemStore
import java.nio.file.Files

class AnchorTrackerTest : BasePlatformTestCase() {
    private lateinit var service: TodoService
    private lateinit var store: ItemStore
    private lateinit var tracker: AnchorTracker
    private val text = "one\ntwo\nthree\nfour\n"

    override fun setUp() {
        super.setUp()
        service = TodoService.getInstance(project)
        store = service.store
        Files.deleteIfExists(store.file)
        store.reload()
        tracker = project.service()
        tracker.start()
    }

    private fun openWithItem(name: String, startLine: Int, endLine: Int): TodoItem {
        val psi = myFixture.configureByText(name, text)
        val path = service.relativePath(psi.virtualFile)!!
        val item = store.create(Draft("t", anchor = AnchorResolver.capture(path, text, startLine, endLine)))
        tracker.syncFile(psi.virtualFile)
        return item
    }

    private fun edit(action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, action)
        tracker.flushAll()
    }

    fun `test anchor follows lines inserted above`() {
        val item = openWithItem("a.txt", 2, 3)
        edit { myFixture.editor.document.insertString(0, "zero\n") }
        val anchor = store.find(item.id)!!.anchor!!
        assertEquals(3, anchor.startLine)
        assertEquals(4, anchor.endLine)
        assertEquals("two\nthree", anchor.text)
        assertEquals(listOf("zero", "one"), anchor.before)
    }

    fun `test typing inside the range updates the stored text`() {
        val item = openWithItem("b.txt", 2, 2)
        edit { myFixture.editor.document.insertString(text.indexOf("two") + 3, "!") }
        val anchor = store.find(item.id)!!.anchor!!
        assertEquals("two!", anchor.text)
        assertFalse(anchor.lost)
    }

    fun `test anchor bookkeeping does not change the updated time`() {
        val item = openWithItem("c.txt", 2, 2)
        edit { myFixture.editor.document.insertString(0, "zero\n") }
        assertEquals(item.updated, store.find(item.id)!!.updated)
    }

    fun `test deleting the anchored text marks the anchor lost`() {
        val item = openWithItem("d.txt", 2, 3)
        edit { myFixture.editor.document.deleteString(text.indexOf("two"), text.indexOf("four")) }
        assertTrue(store.find(item.id)!!.anchor!!.lost)
    }

    fun `test a lost anchor is found again when the text comes back`() {
        val item = openWithItem("e.txt", 2, 2)
        edit { myFixture.editor.document.deleteString(text.indexOf("two"), text.indexOf("three")) }
        assertTrue(store.find(item.id)!!.anchor!!.lost)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "two\n") }
        tracker.syncFile(myFixture.file.virtualFile)
        val anchor = store.find(item.id)!!.anchor!!
        assertFalse(anchor.lost)
        assertEquals(1, anchor.startLine)
    }

    fun `test renaming the file updates the path`() {
        val item = openWithItem("f.txt", 2, 2)
        val oldPath = store.find(item.id)!!.anchor!!.path
        myFixture.renameElement(myFixture.file, "renamed.txt")
        val newPath = store.find(item.id)!!.anchor!!.path
        assertEquals(oldPath.replace("f.txt", "renamed.txt"), newPath)
    }

    fun `test deleting the file marks the anchor lost`() {
        val item = openWithItem("g.txt", 2, 2)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.file.virtualFile.delete(this) }
        assertTrue(store.find(item.id)!!.anchor!!.lost)
    }

    fun `test reattach replaces the anchor with the selection`() {
        val item = openWithItem("h.txt", 2, 2)
        myFixture.editor.selectionModel.setSelection(text.indexOf("three"), text.indexOf("four") + 4)
        assertTrue(tracker.reattach(item.id, myFixture.editor))
        val anchor = store.find(item.id)!!.anchor!!
        assertEquals(3, anchor.startLine)
        assertEquals(4, anchor.endLine)
        assertEquals("three\nfour", anchor.text)
        myFixture.editor.selectionModel.removeSelection()
        assertFalse(tracker.reattach(item.id, myFixture.editor))
    }

    fun `test selection ending at a line start does not include that line`() {
        myFixture.configureByText("i.txt", text)
        myFixture.editor.selectionModel.setSelection(text.indexOf("two"), text.indexOf("three"))
        val anchor = EditorAnchors.fromSelection(project, myFixture.editor)!!
        assertEquals(2, anchor.startLine)
        assertEquals(2, anchor.endLine)
    }


    fun `test an anchor marked lost is not silently moved to an identical neighbour`() {
        val braces = "a\n}\n}\nb\n"
        val psi = myFixture.configureByText("j.txt", braces)
        val path = service.relativePath(psi.virtualFile)!!
        val item = store.create(Draft("t", anchor = AnchorResolver.capture(path, braces, 2, 2)))
        tracker.syncFile(psi.virtualFile)
        edit { myFixture.editor.document.deleteString(2, 4) }
        assertTrue(store.find(item.id)!!.anchor!!.lost)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(store.find(item.id)!!.anchor!!.lost)
    }

    fun `test bookkeeping does not overwrite an anchor changed on disk`() {
        val item = openWithItem("k.txt", 2, 2)
        val onDisk = Files.readString(store.file).replace("\"text\": \"two\"", "\"text\": \"from another branch\"")
        Files.writeString(store.file, onDisk)
        edit { myFixture.editor.document.insertString(0, "zero\n") }
        assertEquals("from another branch", store.find(item.id)!!.anchor!!.text)
    }
}
