package io.github.konove.notmytodo.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.TestStore
import io.github.konove.notmytodo.anchor.AnchorResolver
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ProjectRecord
import java.nio.file.Files
import java.nio.file.Path

class CheckoutsTest : BasePlatformTestCase() {
    private val asked = ArrayList<Pair<Path, String>>()
    private lateinit var real: (Project, Path, String) -> Project?

    override fun setUp() {
        super.setUp()
        TestStore.reset(project)
        real = Checkouts.window
        // A test cannot open a window: the window asked for is recorded, and none is given.
        Checkouts.window = { _, path, ref -> asked += path to ref; null }
    }

    override fun tearDown() {
        try {
            Checkouts.window = real
        } finally {
            super.tearDown()
        }
    }

    private fun other(key: String, checkout: Path?) {
        val stores = StoreService.getInstance().store
        stores.dir(key).writeRecord(ProjectRecord(key, checkouts = listOfNotNull(checkout?.toString())))
        stores.items(key).create(Draft("theirs"))
        StoreView.getInstance(project).load()
    }

    fun `test the window of a checkout is found by its repository root, not only by its folder`() {
        val root = TodoService.getInstance(project).root
        assertSame(project, Checkouts.openIn(root, listOf(project)))
        assertNull(Checkouts.openIn(root.resolve("elsewhere"), listOf(project)))
    }

    fun `test the unsaved document of another checkout's file is saved before its prompt is read`() {
        val dir = Files.createTempDirectory("nmt-save")
        val file = Files.writeString(dir.resolve("a.txt"), "old\n")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        val document = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("new\n") }
        ItemActions.saveIn(dir, listOf("a.txt"))
        // On a cold IDE the write to the disk was seen to trail the save by a moment.
        val end = System.currentTimeMillis() + 5000
        while (Files.readString(file) != "new\n" && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("new\n", Files.readString(file))
    }

    fun `test an item with a checkout here asks for the window of its checkout`() {
        val folder = Files.createTempDirectory("nmt-checkout")
        other("infra", folder)
        Checkouts.goTo(project, "infra/T-1")
        assertEquals(listOf(folder to "infra/T-1"), asked)
    }

    fun `test an item with no checkout here opens nothing`() {
        other("infra", null)
        val stores = StoreService.getInstance().store
        stores.inbox()
        stores.items(io.github.konove.notmytodo.store.Store.INBOX).create(Draft("loose"))
        StoreView.getInstance(project).load()
        Checkouts.goTo(project, "infra/T-1")
        Checkouts.goTo(project, "inbox/T-1")
        assertTrue(asked.isEmpty())
    }

    fun `test a note of another project asks for no window, though it has a checkout`() {
        other("infra", Files.createTempDirectory("nmt-checkout"))
        ItemActions.navigate(project, StoreView.getInstance(project).find("infra/T-1")!!)
        assertTrue(asked.isEmpty())
    }

    fun `test Jump on an anchor goes to that anchor in the window of the checkout`() {
        val service = TodoService.getInstance(project)
        val a = myFixture.addFileToProject("a.txt", "one\ntwo\n").virtualFile
        val b = myFixture.addFileToProject("b.txt", "one\ntwo\nthree\n").virtualFile
        val anchors = listOf(
            AnchorResolver.capture(service.relativePath(a)!!, "one\ntwo\n", 1, 1),
            AnchorResolver.capture(service.relativePath(b)!!, "one\ntwo\nthree\n", 3, 3),
        )
        service.store.create(Draft("two places", anchors = anchors))
        // The window given is this one, and the item, named as another project would name it, is its own there.
        Checkouts.window = { p, path, ref -> asked += path to ref; p }
        val item = service.store.find("T-1")!!.copy(id = "${service.store.key}/T-1")
        ItemActions.navigate(project, item, item.anchors[1])
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(b, FileDocumentManager.getInstance().getFile(editor.document))
        assertEquals(2, editor.caretModel.logicalPosition.line)
    }
}
