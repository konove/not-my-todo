package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.Store
import io.github.konove.notmytodo.store.StoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProjectOpenerTest {
    private val tmp = Files.createTempDirectory("nmt-opener")
    private val store = Store(tmp.resolve("store"), home = tmp.resolve("home"))
    private val opener = ProjectOpener(store)
    private val json = """{"version":2,"nextId":9,"items":[""" +
        """{"id":"T-2","title":"two","status":"fixed"},""" +
        """{"id":"T-5","title":"five","anchors":[{"path":"src/a.txt","startLine":1,"endLine":1,"text":"x","before":[],"after":[]}]}]}"""

    /** A folder git would take for a repository, with [remote] as its origin. */
    private fun repo(name: String, remote: String? = null): Path {
        val dir = Files.createDirectories(tmp.resolve(name).resolve(".git")).parent
        remote?.let { Files.writeString(dir.resolve(".git/config"), "[remote \"origin\"]\n\turl = $it\n") }
        return dir
    }

    private fun itemsFile(folder: Path, text: String = json): Path =
        folder.resolve(".todos/items.json").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, text)
        }

    @Test
    fun `a folder with no items file gets its project and nothing else`() {
        val folder = repo("plain")
        val opened = opener.open(folder, folder.resolve(".todos/items.json"))
        assertNull(opened.legacy)
        assertEquals("plain", opened.items.key)
        assertEquals(folder, opened.root)
        assertEquals(emptyList<Any>(), opened.items.items)
    }

    @Test
    fun `a project is made for a folder and its items file is imported, with the statuses the IDE held`() {
        val folder = repo("app", "git@github.com:me/app.git")
        val file = itemsFile(folder)
        val opened = opener.open(folder, file, mapOf("T-2" to (Status.FIXED to Status.DONE)))
        assertEquals(ProjectOpener.Legacy.Imported(file, 2), opened.legacy)
        assertEquals("app", opened.items.key)
        assertEquals(listOf("T-2", "T-5"), opened.items.items.map { it.id })
        assertEquals(Status.DONE, opened.items.find("T-2")!!.status)
        assertEquals("T-9", opened.items.create(Draft("next")).id)
        assertEquals(json, Files.readString(file))
    }

    @Test
    fun `a held status the file has moved on from is not applied`() {
        val folder = repo("moved")
        val opened = opener.open(folder, itemsFile(folder), mapOf("T-2" to (Status.OPEN to Status.DONE)))
        assertEquals(Status.FIXED, opened.items.find("T-2")!!.status)
    }

    @Test
    fun `the file is imported once`() {
        val folder = repo("once")
        val file = itemsFile(folder)
        opener.open(folder, file).items.delete("T-5")
        val again = opener.open(folder, file)
        assertNull(again.legacy)
        assertEquals(listOf("T-2"), again.items.items.map { it.id })
    }

    @Test
    fun `an items file that cannot be read makes no project`() {
        val folder = repo("broken")
        val file = itemsFile(folder, "{ broken")
        val e = assertThrows(StoreException::class.java) { opener.open(folder, file) }
        assertTrue(e.message!!, e.message!!.contains("cannot be imported"))
        assertEquals(emptyList<Any>(), store.projects())
        Files.writeString(file, json)
        assertEquals(ProjectOpener.Legacy.Imported(file, 2), opener.open(folder, file).legacy)
    }

    @Test
    fun `a kept items file that broke after the import is not read again`() {
        val folder = repo("kept")
        val file = itemsFile(folder)
        opener.open(folder, file)
        Files.writeString(file, "{ broken")
        val again = opener.open(folder, file)
        assertNull(again.legacy)
        assertEquals(listOf("T-2", "T-5"), again.items.items.map { it.id })
    }

    @Test
    fun `anchors are made relative to the git root when the IDE project is a folder inside the repository`() {
        val folder = repo("mono")
        val sub = Files.createDirectories(folder.resolve("apps/web"))
        val opened = opener.open(sub, itemsFile(sub))
        assertEquals(folder, opened.root)
        assertEquals("apps/web/src/a.txt", opened.items.find("T-5")!!.anchors.single().path)
    }

    @Test
    fun `an id the project already has stops the import, and nothing is written`() {
        val folder = repo("clash")
        val first = opener.open(folder, folder.resolve(".todos/items.json"))
        first.items.create(Draft("one"))
        first.items.create(Draft("two"))
        val file = itemsFile(folder)
        val opened = opener.open(folder, file)
        assertEquals(ProjectOpener.Legacy.Clash(file, listOf("T-2")), opened.legacy)
        assertEquals(listOf("one", "two"), opened.items.items.map { it.title })
    }

    @Test
    fun `another clone with the same file says nothing, and one with other items says they were not imported`() {
        val a = repo("one/app", "git@github.com:me/app.git")
        val fa = itemsFile(a)
        opener.open(a, fa)
        val b = repo("two/app", "https://github.com/me/app")
        assertNull(opener.open(b, itemsFile(b)).legacy)
        val c = repo("three/app", "git@github.com:me/app.git")
        val fc = itemsFile(c, json.replace("five", "FIVE"))
        assertEquals(ProjectOpener.Legacy.Elsewhere(fc, fa.toString()), opener.open(c, fc).legacy)
    }

    @Test
    fun `a second items file at the home folder is not imported into Inbox beside the first`() {
        // Only the home folder itself resolves to Inbox: a folder in it that is no repository is a project of its own.
        val home = Files.createDirectories(tmp.resolve("home"))
        val first = itemsFile(home)
        assertEquals(ProjectOpener.Legacy.Imported(first, 2), opener.open(home, first).legacy)
        val other = home.resolve(".todos/other.json")
        Files.writeString(other, json.replace("five", "FIVE"))
        val opened = opener.open(home, other)
        assertEquals(ProjectOpener.Legacy.Elsewhere(other, first.toString()), opened.legacy)
        assertEquals(listOf("two", "five"), opened.items.items.map { it.title })
    }

    @Test
    fun `rebase leaves anchors alone when the folders are the same or unrelated`() {
        val item = io.github.konove.notmytodo.model.TodoItem(id = "T-1", title = "t")
        assertEquals(item, ProjectOpener.rebase(tmp, tmp)(item))
        assertEquals(item, ProjectOpener.rebase(tmp.resolve("a"), tmp.resolve("b"))(item))
    }
}
