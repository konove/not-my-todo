package io.github.konove.notmytodo

import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.ide.StoreService
import io.github.konove.notmytodo.ide.StoreView
import io.github.konove.notmytodo.ide.TodoService
import java.nio.file.Path

/** Empties the store the tests run on and opens the project's store project again: made anew with no items, or with [made] false not made at all. */
object TestStore {
    fun reset(project: Project, made: Boolean = true) {
        val stores = StoreService.getInstance().store
        val root = stores.root
        // Never the user's own store: the build points the tests at one of their own.
        val own = System.getProperty(StoreService.ROOT_PROPERTY)
        check(own != null && root == Path.of(own)) { "the tests are not running on a store of their own: $root" }
        root.resolve("projects").toFile().deleteRecursively()
        val service = TodoService.getInstance(project)
        // Opening a folder no longer makes its project; most tests write to it at once, as if it had one.
        if (made) stores.ensure(stores.resolve(service.base))
        service.reopen()
        // The snapshot outlives a test, and the reopen only asks for its read, on a pooled thread.
        StoreView.getInstance(project).load()
    }
}
