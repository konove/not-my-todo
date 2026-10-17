package io.github.konove.notmytodo

import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.ide.StoreService
import io.github.konove.notmytodo.ide.StoreView
import io.github.konove.notmytodo.ide.TodoService
import java.nio.file.Path

/** Empties the store the tests run on, and opens the project's store project again, made anew with no items. */
object TestStore {
    fun reset(project: Project) {
        val root = StoreService.getInstance().store.root
        // Never the user's own store: the build points the tests at one of their own.
        val own = System.getProperty(StoreService.ROOT_PROPERTY)
        check(own != null && root == Path.of(own)) { "the tests are not running on a store of their own: $root" }
        root.resolve("projects").toFile().deleteRecursively()
        TodoService.getInstance(project).reopen()
        // The snapshot outlives a test, and the reopen only asks for its read, on a pooled thread.
        StoreView.getInstance(project).load()
    }
}
