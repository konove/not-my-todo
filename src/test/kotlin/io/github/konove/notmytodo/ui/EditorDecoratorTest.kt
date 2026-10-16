package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.TestStore
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.store.Draft

class EditorDecoratorTest : BasePlatformTestCase() {
    private val text = "one\ntwo\nthree\n"

    fun `test open anchored items get a mark and closed or lost ones do not`() {
        val service = TodoService.getInstance(project)
        TestStore.reset(project)
        val store = service.store
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        val open = store.create(Draft("open", anchors = listOf(AnchorResolver.capture(path, text, 1, 1))))
        val closed = store.create(Draft("closed", anchors = listOf(AnchorResolver.capture(path, text, 2, 2))))
        store.update(closed.id) { it.copy(status = Status.DONE) }
        store.create(Draft("lost", anchors = listOf(AnchorResolver.capture(path, text, 3, 3).copy(lost = true))))
        store.create(Draft("note"))

        val decorator = project.service<EditorDecorator>()
        decorator.start()
        decorator.refresh()

        assertEquals(listOf(open.id), decorator.decoratedIds(myFixture.editor.document))
    }

    override fun tearDown() {
        try {
            TodoSettings.getInstance().update(TodoSettings.Values())
        } finally {
            super.tearDown()
        }
    }

    fun `test switching the marks off removes them and on brings them back`() {
        val service = TodoService.getInstance(project)
        TestStore.reset(project)
        val store = service.store
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        val open = store.create(Draft("open", anchors = listOf(AnchorResolver.capture(path, text, 1, 1))))
        val decorator = project.service<EditorDecorator>()
        decorator.start()
        decorator.refresh()
        assertEquals(listOf(open.id), decorator.decoratedIds(myFixture.editor.document))

        TodoSettings.getInstance().update(TodoSettings.Values(editorMarks = false))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(emptyList<String>(), decorator.decoratedIds(myFixture.editor.document))

        TodoSettings.getInstance().update(TodoSettings.Values(editorMarks = true))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(open.id), decorator.decoratedIds(myFixture.editor.document))
    }

    // The IDE keeps the mark of the last tooltip it showed; a renderer left on it would keep the plugin from unloading.
    fun `test a removed mark lets go of its renderer`() {
        val service = TodoService.getInstance(project)
        TestStore.reset(project)
        val store = service.store
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        store.create(Draft("open", anchors = listOf(AnchorResolver.capture(path, text, 1, 1))))
        val decorator = project.service<EditorDecorator>()
        decorator.start()
        decorator.refresh()
        val mark = DocumentMarkupModel.forDocument(myFixture.editor.document, project, true).allHighlighters
            .single { it.gutterIconRenderer is TodoGutterRenderer }

        TodoSettings.getInstance().update(TodoSettings.Values(editorMarks = false))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(mark.isValid)
        assertNull(mark.gutterIconRenderer)
    }

    // Deleting its lines makes the IDE drop a mark by itself, and a dropped mark throws when its renderer is set.
    fun `test a mark whose lines were deleted is cleared without an error`() {
        val service = TodoService.getInstance(project)
        TestStore.reset(project)
        val store = service.store
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        store.create(Draft("open", anchors = listOf(AnchorResolver.capture(path, text, 1, 1))))
        val decorator = project.service<EditorDecorator>()
        decorator.start()
        decorator.refresh()
        val document = myFixture.editor.document
        val mark = DocumentMarkupModel.forDocument(document, project, true).allHighlighters
            .single { it.gutterIconRenderer is TodoGutterRenderer }

        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(0, document.textLength) }
        assertFalse(mark.isValid)

        decorator.refresh()
        assertEquals(emptyList<String>(), decorator.decoratedIds(document))
    }

    fun `test every anchored range of an item gets a mark and a whole file gets none`() {
        val service = TodoService.getInstance(project)
        TestStore.reset(project)
        val store = service.store
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        val two = store.create(Draft("two", anchors = listOf(
            AnchorResolver.capture(path, text, 1, 1), io.github.konove.notmytodo.model.Anchor.file(path), AnchorResolver.capture(path, text, 3, 3),
        )))
        store.create(Draft("whole", anchors = listOf(io.github.konove.notmytodo.model.Anchor.file(path))))
        val decorator = project.service<EditorDecorator>()
        decorator.start()
        decorator.refresh()
        assertEquals(listOf(two.id, two.id), decorator.decoratedIds(myFixture.editor.document))
    }
}
