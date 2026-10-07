package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.model.Comment
import io.github.konove.notmytodo.model.Author
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
        store.create(Draft("anchored", anchors = listOf(AnchorResolver.capture(path, text, 2, 2))))
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
        val found = io.github.konove.notmytodo.model.TodoItem("b.txt:2", "TODO: tidy this up", anchors = listOf(anchor))
        assertTrue(CodeTodos.isCode(found))
        assertEquals("tidy this up", CodeTodos.trackedTitle(found))
        assertEquals(listOf(found), CodeTodos.filter(listOf(found), "TIDY b.txt"))
        assertEquals(emptyList<Any>(), CodeTodos.filter(listOf(found), "absent"))
        val pane = DetailPane(project)
        pane.show(found)
        pane.save()
        assertTrue(TodoService.getInstance(project).store.items.isEmpty())
    }

    private fun DetailPane.tags() = metaStrip.components.filterIsInstance<javax.swing.JLabel>().map { it.text }

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

    fun `test detail pane shows comments under the details and when there are none`() {
        val store = TodoService.getInstance(project).store
        val item = store.create(Draft("a"))
        val pane = DetailPane(project)
        pane.show(item)
        pane.show(store.comment(item.id, Author.AGENT, "found the **cause**"))
        val shown = pane.viewDetails.document.let { it.getText(0, it.length) }
        assertTrue(shown, shown.contains("Agent") && shown.contains("found the cause"))
        assertTrue(pane.viewDetailsScroll.isVisible)
    }

    fun `test item text lays comments out after the details`() {
        val item = io.github.konove.notmytodo.model.TodoItem(
            "T-1", "a", details = "the details",
            comments = listOf(Comment(Author.AGENT, "2026-10-06T09:00:00Z", "a *short* note"), Comment(Author.USER, "then", "mine")),
        )
        val html = ItemText.html(item, java.time.ZoneId.of("Europe/Berlin"))
        assertTrue(html, html.contains("Comments · 2"))
        assertTrue(html, html.contains("<icon src=\"AllIcons.Actions.Lightning\">&nbsp;<b>Agent</b>") && html.contains(">2026-10-06 11:00<"))
        assertTrue(html, html.contains("<icon src=\"AllIcons.General.User\">&nbsp;<b>Me</b>") && html.contains(">then<"))
        assertFalse(html, html.contains("<hr>"))
        assertFalse(ItemText.html(item.copy(comments = emptyList())).contains("Comments"))
        assertTrue(html, html.contains("a <em>short</em> note"))
        assertTrue(html.indexOf("the details") < html.indexOf("Comments"))
        assertEquals(1, Regex("<body>").findAll(html).count())
    }

    fun `test code sits beside the text when the pane is wide and under it when narrow`() {
        val service = TodoService.getInstance(project)
        val path = service.relativePath(myFixture.configureByText("a.txt", text).virtualFile)!!
        val store = service.store
        val anchored = store.create(Draft("anchored", details = "why", anchors = listOf(AnchorResolver.capture(path, text, 2, 2))))
        val pane = DetailPane(project)
        pane.show(anchored)
        fun layout(width: Int) {
            pane.setSize(width, 400)
            pane.doLayout()
            pane.columns.doLayout()
        }
        layout(1600)
        assertTrue(pane.codeBox.x > pane.textColumn.x + pane.textColumn.width - 1)
        assertEquals(pane.textColumn.y, pane.codeBox.y)
        assertEquals(pane.textColumn.height, pane.codeBox.height)
        assertTrue(pane.textColumn.width <= com.intellij.util.ui.JBUI.scale(760))
        assertTrue(pane.codeBox.x + pane.codeBox.width == pane.columns.width - pane.columns.insets.right)
        assertTrue(pane.whereLabel.text, pane.whereLabel.text.contains(">a.txt:2<"))
        layout(500)
        assertEquals(pane.textColumn.x, pane.codeBox.x)
        assertTrue(pane.codeBox.y >= pane.textColumn.y + pane.textColumn.height)
        pane.show(store.create(Draft("note", details = "why")))
        layout(1600)
        assertFalse(pane.codeBox.isVisible)
        assertEquals(pane.columns.width - pane.columns.insets.left - pane.columns.insets.right, pane.textColumn.width)
    }

    fun `test the facts line does not end with a separator`() {
        val store = TodoService.getInstance(project).store
        val item = store.create(Draft("a", tags = listOf("x")))
        val pane = DetailPane(project)
        pane.show(store.update(item.id) { it.copy(status = Status.FIXED) })
        val fixed = pane.viewMeta.getCharSequence(false).toString().trim()
        assertTrue(fixed, fixed.endsWith("note"))
        assertFalse(fixed, fixed.contains("#"))
        assertEquals(listOf("x"), pane.tags())
        pane.show(store.update(item.id) { it.copy(tags = listOf("y", "z")) })
        assertEquals(listOf("y", "z"), pane.tags())
        pane.show(store.update(item.id) { it.copy(status = Status.OPEN) })
        assertTrue(pane.viewMeta.getCharSequence(false).toString().trim().endsWith("agent can fix"))
    }

    fun `test a long path keeps its ends and the file name apart`() {
        assertEquals("src/main/kotlin/…/mcp/" to "TodoTools.kt", PathText.split("src/main/kotlin/io/github/konove/notmytodo/mcp/TodoTools.kt"))
        assertEquals("a/b/c/d/" to "e.txt", PathText.split("a/b/c/d/e.txt"))
        assertEquals("a/b/c/…/e/" to "f.txt", PathText.split("a/b/c/d/e/f.txt"))
        assertEquals("" to "a.txt", PathText.split("a.txt"))
    }

    fun `test the text column never lays its rows out wider than itself`() {
        val service = TodoService.getInstance(project)
        val path = service.relativePath(myFixture.configureByText("a.txt", text).virtualFile)!!
        val store = service.store
        val long = "A title long enough that it has to wrap when the column gets narrow, which it does here"
        val tags = (1..12).map { "a-rather-long-tag-$it" }
        val note = store.create(Draft(long, details = "why ".repeat(200), tags = tags))
        val anchored = store.create(Draft(long, details = "why ".repeat(200), tags = tags, anchors = listOf(AnchorResolver.capture(path, text, 2, 2))))
        val pane = DetailPane(project)
        fun deep(c: java.awt.Container) {
            // Without a window nothing is ever valid, so a resize does not invalidate by itself.
            c.invalidate()
            c.doLayout()
            c.components.filterIsInstance<java.awt.Container>().forEach(::deep)
        }
        fun layout(width: Int) {
            pane.setSize(width, 400)
            repeat(3) {
                deep(pane)
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }
        fun widest(c: java.awt.Container): Int =
            c.components.filter { it.isVisible }.maxOfOrNull { it.x + it.width } ?: 0
        pane.show(note)
        layout(1600)
        pane.show(anchored)
        layout(1600)
        assertTrue(pane.textColumn.components.filter { it.isVisible }.joinToString("\n") { "${it.javaClass.simpleName} ${it.bounds} pref=${it.preferredSize} min=${it.minimumSize}" }, widest(pane.textColumn) <= pane.textColumn.width)
        layout(500)
        assertTrue("${widest(pane.textColumn)} > ${pane.textColumn.width}", widest(pane.textColumn) <= pane.textColumn.width)
        assertTrue(pane.viewTitle.height > pane.viewTitle.getFontMetrics(pane.viewTitle.font).height)
    }

    fun `test the detail pane steps through the anchors of an item`() {
        val service = TodoService.getInstance(project)
        val path = service.relativePath(myFixture.configureByText("s.txt", text).virtualFile)!!
        val store = service.store
        val item = store.create(Draft("several", anchors = listOf(
            AnchorResolver.capture(path, text, 2, 2), io.github.konove.notmytodo.model.Anchor.file(path),
        )))
        val pane = DetailPane(project)
        pane.show(item)
        assertTrue(pane.whereLabel.text, pane.whereLabel.text.contains("1 of 2") && pane.whereLabel.text.contains(">s.txt:2<"))
        assertTrue(pane.anchorSteps.isVisible)
        pane.step(1)
        assertTrue(pane.whereLabel.text, pane.whereLabel.text.contains("2 of 2") && pane.whereLabel.text.contains(">s.txt<"))
        assertTrue(pane.whereLabel.text, pane.whereLabel.text.contains("whole file"))
        assertTrue(pane.codeBox.isVisible)
        pane.step(1)
        assertTrue(pane.whereLabel.text, pane.whereLabel.text.contains("1 of 2"))
        pane.removeAnchor()
        assertEquals(listOf(path), store.find(item.id)!!.anchors.map { it.place })
        assertFalse(pane.whereLabel.text, pane.whereLabel.text.contains(" of "))
        assertFalse(pane.anchorSteps.isVisible)
        pane.removeAnchor()
        assertFalse(pane.codeBox.isVisible)
    }

    fun `test the detail pane shows and edits the links of an item`() {
        val store = TodoService.getInstance(project).store
        val whole = store.create(Draft("whole"))
        val first = store.create(Draft("first", parent = whole.id))
        val second = store.create(Draft("second", blockedBy = listOf(first.id), parent = whole.id))
        val pane = DetailPane(project)
        var opened: String? = null
        pane.onOpen = { opened = it }

        pane.show(second)
        assertEquals("Blocked by ${first.id}  ·  Part of ${whole.id}", pane.viewLinks.getCharSequence(false).toString())
        assertTrue(pane.viewMeta.getCharSequence(false).toString().trim().endsWith("blocked"))
        pane.show(whole)
        assertEquals("Parts, 0/2 closed: ${first.id} ${second.id}", pane.viewLinks.getCharSequence(false).toString())
        pane.show(store.update(first.id) { it.copy(status = Status.DONE) })
        pane.show(store.find(second.id))
        assertTrue(pane.viewMeta.getCharSequence(false).toString().trim().endsWith("agent can fix"))

        val other = store.create(Draft("other"))
        pane.show(other)
        assertEquals("", pane.viewLinks.getCharSequence(false).toString())
        pane.linkFields[0].text = "${first.id} ${second.id}"
        pane.linkFields[1].text = second.id.lowercase()
        pane.save()
        assertEquals(listOf(first.id, second.id), store.find(other.id)!!.blockedBy)
        assertEquals(second.id, store.find(other.id)!!.duplicateOf)
        assertNull(opened)
    }
}
