package io.github.konove.notmytodo.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.settings.TodoSettings
import io.github.konove.notmytodo.store.Draft
import java.nio.file.Files

class EditorDecoratorTest : BasePlatformTestCase() {
    private val text = "one\ntwo\nthree\n"

    fun `test open anchored items get a mark and closed or lost ones do not`() {
        val service = TodoService.getInstance(project)
        val store = service.store
        Files.deleteIfExists(store.file)
        store.reload()
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        val open = store.create(Draft("open", anchor = AnchorResolver.capture(path, text, 1, 1)))
        val closed = store.create(Draft("closed", anchor = AnchorResolver.capture(path, text, 2, 2)))
        store.update(closed.id) { it.copy(status = Status.DONE) }
        store.create(Draft("lost", anchor = AnchorResolver.capture(path, text, 3, 3).copy(lost = true)))
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
        val store = service.store
        Files.deleteIfExists(store.file)
        store.reload()
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        val open = store.create(Draft("open", anchor = AnchorResolver.capture(path, text, 1, 1)))
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
}
