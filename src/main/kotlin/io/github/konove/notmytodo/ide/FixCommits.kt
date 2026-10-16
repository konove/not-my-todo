package io.github.konove.notmytodo.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.model.TodoItem
import java.nio.file.Path
import io.github.konove.notmytodo.store.FixCommits as CommitLog

/**
 * The commit that fixed an item which does not name one itself, read from git in the checkout of
 * the store project, as the store's server reads it. A fixedIn stored on the item always wins.
 */
@Service(Service.Level.PROJECT)
class FixCommits(private val project: Project) {
    /** The log of the checkout last asked about; another one after the project was opened at another root. */
    @Volatile
    private var log: Pair<Path, CommitLog>? = null

    private val current: CommitLog
        get() {
            val root = TodoService.getInstance(project).root
            log?.takeIf { it.first == root }?.let { return it.second }
            return CommitLog(root, StoreService.getInstance().store.git).also { log = root to it }
        }

    /** True for an item that is closed as fixed and does not say in which commit. */
    fun derives(item: TodoItem): Boolean = current.derives(item)

    /** [item] with the commit that fixed it filled in, as far as it is known without asking git again. */
    fun shown(item: TodoItem): TodoItem = current.shown(item)

    /** Asks git again when there were commits since it was last asked; true when the answer is another one. It starts a process: not for the event thread. */
    fun refresh(): Boolean = current.refresh()

    /** Does [refresh] in the background and calls [changed] on the event thread when there is something new to show. */
    fun refreshLater(changed: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed && refresh()) {
                ApplicationManager.getApplication().invokeLater({ changed() }, project.disposed)
            }
        }
    }
}
