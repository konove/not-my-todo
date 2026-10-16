package io.github.konove.notmytodo

import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.ide.TodoService
import java.nio.file.Files

/** Leaves the project with no items, as each test expects to find it. */
object TestStore {
    fun reset(project: Project) {
        val service = TodoService.getInstance(project)
        Files.deleteIfExists(service.store.file)
        service.reload()
    }
}
