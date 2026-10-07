package io.github.konove.notmytodo.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.ide.CodeTodos
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.store.Draft
import java.nio.file.Files

/** The UI is otherwise checked by hand; these only prove the components build and react without throwing. */
class UiSmokeTest : BasePlatformTestCase() {
    private val text = "one\ntwo\nthree\n"

    override fun setUp() {
        super.setUp()
        val store = TodoService.getInstance(project).store
        Files.deleteIfExists(store.file)
        store.reload()
    }

    fun `test panel builds, selects an item and shows the error state`() {
        val service = TodoService.getInstance(project)
        val store = service.store
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        store.create(Draft("anchored", anchor = AnchorResolver.capture(path, text, 2, 2)))
        val note = store.create(Draft("note"))
        store.update(note.id) { it.copy(status = Status.FIXED) }
        val panel = TodoPanel(project)
        try {
            panel.select("T-1")
            panel.select("T-2")
            Files.writeString(store.file, "{ broken")
            store.reload()
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun `test capture dialog builds`() {
        val dialog = CaptureDialog(project, null)
        try {
            assertNotNull(dialog.preferredFocusedComponent)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun `test popup offers actions that fit the status`() {
        val store = TodoService.getInstance(project).store
        val open = store.create(Draft("open"))
        val labels = ItemPopup.buttons(project, open) {}.map { it.text }
        assertEquals(listOf("Fix with Claude", "Start", "Done", "Open in panel"), labels)
        val fixed = store.update(open.id) { it.copy(status = Status.FIXED) }
        assertEquals(listOf("Accept", "Reopen", "Open in panel"), ItemPopup.buttons(project, fixed) {}.map { it.text })
    }


    fun `test unsaved detail edits survive a refresh of the panel`() {
        val store = TodoService.getInstance(project).store
        store.create(Draft("first"))
        store.create(Draft("second"))
        val panel = TodoPanel(project)
        try {
            panel.select("T-1")
            panel.detail.titleField.text = "half typed"
            store.update("T-2") { it.copy(details = "changed elsewhere") }
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("half typed", panel.detail.titleField.text)
            assertEquals("T-1", panel.detail.currentId)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun `test save keeps a change someone else made to another field`() {
        val store = TodoService.getInstance(project).store
        val item = store.create(Draft("typo in titel"))
        val pane = DetailPane(project)
        pane.show(item)
        pane.titleField.text = "typo in title"
        pane.show(store.update(item.id) { it.copy(status = Status.FIXED) })
        pane.save()
        val saved = store.find(item.id)!!
        assertEquals("typo in title", saved.title)
        assertEquals(Status.FIXED, saved.status)
    }

    fun `test status buttons keep unsaved text`() {
        val store = TodoService.getInstance(project).store
        val item = store.create(Draft("a"))
        val pane = DetailPane(project)
        pane.show(item)
        pane.titleField.text = "b"
        pane.setStatus(Status.IN_PROGRESS)
        assertEquals("b", pane.titleField.text)
        assertEquals(Status.IN_PROGRESS, store.find(item.id)!!.status)
    }

    fun `test a TODO comment from the code is listed but never saved as an item`() {
        val anchor = io.github.konove.notmytodo.model.Anchor("b.txt", 2, 2, "TODO: tidy this up", emptyList(), emptyList())
        val found = io.github.konove.notmytodo.model.TodoItem("b.txt:2", "TODO: tidy this up", anchor = anchor)
        assertTrue(CodeTodos.isCode(found))
        assertEquals("tidy this up", CodeTodos.trackedTitle(found))
        assertEquals(listOf(found), CodeTodos.filter(listOf(found), "TIDY b.txt"))
        assertEquals(emptyList<Any>(), CodeTodos.filter(listOf(found), "absent"))
        val pane = DetailPane(project)
        pane.show(found)
        pane.save()
        assertTrue(TodoService.getInstance(project).store.items.isEmpty())
    }

    override fun tearDown() {
        try {
            TodoSettings.getInstance().update(TodoSettings.Values())
        } finally {
            super.tearDown()
        }
    }

    fun `test the Code comments entry follows its setting`() {
        val panel = TodoPanel(project, codeSupported = true)
        try {
            assertTrue(panel.navLabels.contains("Code comments"))
            TodoSettings.getInstance().update(TodoSettings.Values(codeComments = false))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertFalse(panel.navLabels.contains("Code comments"))
            assertTrue(panel.navLabels.isNotEmpty())
            TodoSettings.getInstance().update(TodoSettings.Values(codeComments = true))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(panel.navLabels.contains("Code comments"))
        } finally {
            Disposer.dispose(panel)
        }
    }
}
