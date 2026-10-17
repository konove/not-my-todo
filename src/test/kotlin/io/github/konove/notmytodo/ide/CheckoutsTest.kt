package io.github.konove.notmytodo.ide

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.TestStore
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

    fun `test an item with a checkout here asks for the window of its checkout`() {
        val folder = Files.createTempDirectory("nmt-checkout")
        other("infra", folder)
        Checkouts.goTo(project, "infra/T-1")
        assertEquals(listOf(folder to "infra/T-1"), asked)
    }

    fun `test an item with no checkout here opens nothing`() {
        other("infra", null)
        Checkouts.goTo(project, "infra/T-1")
        Checkouts.goTo(project, "inbox/T-1")
        assertTrue(asked.isEmpty())
    }
}
