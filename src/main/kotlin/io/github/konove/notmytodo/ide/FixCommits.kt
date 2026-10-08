package io.github.konove.notmytodo.ide

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The commit that fixed an item which does not name one itself: the newest commit whose message
 * names the item's id. It is read from git and never written to the items file, so closing an item
 * needs no commit after the one that fixed it. A fixedIn stored on the item always wins.
 */
@Service(Service.Level.PROJECT)
class FixCommits(private val project: Project) {
    /** What git was last asked for, and the state of its log of HEAD at that time. */
    private class Read(val commits: Map<String, String>, val stamp: String?, val time: Long)

    @Volatile
    private var read: Read? = null

    /** True for an item that is closed as fixed and does not say in which commit. */
    fun derives(item: TodoItem): Boolean =
        item.fixedIn == null && (item.status == Status.FIXED || item.status == Status.DONE)

    /** [item] with the commit that fixed it filled in, as far as it is known without asking git again. */
    fun shown(item: TodoItem): TodoItem {
        if (!derives(item)) return item
        return read?.commits?.get(item.id)?.let { item.copy(fixedIn = it) } ?: item
    }

    /**
     * Asks git again when there were commits since it was last asked, and returns true when the
     * answer is another one. It starts a process: not for the event thread.
     */
    fun refresh(): Boolean {
        val base = project.basePath ?: return false
        val stamp = stamp(Path.of(base))
        val last = read
        val now = System.currentTimeMillis()
        // Without a stamp there is no telling whether anything was committed, so git is asked, but not all the time.
        val fresh = last != null && if (stamp != null) stamp == last.stamp else last.stamp == null && now - last.time < UNSTAMPED_MS
        if (fresh) return false
        val commits = log(base)?.let(::parse).orEmpty()
        read = Read(commits, stamp, now)
        return commits != last?.commits.orEmpty()
    }

    /** Does [refresh] in the background and calls [changed] on the event thread when there is something new to show. */
    fun refreshLater(changed: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed && refresh()) {
                ApplicationManager.getApplication().invokeLater({ changed() }, project.disposed)
            }
        }
    }

    /**
     * The id of the commit that [name] stands for now, when it is a name such as HEAD and not an id
     * already. Null when git knows no such commit. It starts a process: not for the event thread.
     */
    fun resolve(name: String): String? {
        val base = project.basePath ?: return null
        val ref = name.trim()
        if (ref.isEmpty() || ref.startsWith("-") || ID_LIKE.matches(ref)) return null
        return try {
            val command = GeneralCommandLine("git", "rev-parse", "--verify", "--quiet", "--short", "$ref^{commit}").withWorkDirectory(base)
            CapturingProcessHandler(command).runProcess(TIMEOUT_MS).takeIf { !it.isTimeout && it.exitCode == 0 }
                ?.stdout?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: ExecutionException) {
            null
        }
    }

    private fun log(base: String): String? = try {
        // The ids are looked for again in parse; the filter only keeps the output small.
        val command = GeneralCommandLine("git", "log", "--grep=T-[0-9]", "--format=%h$FIELD%B$RECORD").withWorkDirectory(base)
        CapturingProcessHandler(command).runProcess(TIMEOUT_MS).takeIf { !it.isTimeout && it.exitCode == 0 }?.stdout
    } catch (_: ExecutionException) {
        // No git, or no such directory: nothing is known then.
        null
    }

    /** Changes with every commit, checkout and reset. Null when there is no such log to look at. */
    private fun stamp(base: Path): String? = try {
        val dot = base.resolve(".git")
        // In a linked worktree .git is a file that names the directory.
        val dir = if (Files.isRegularFile(dot)) {
            Files.readString(dot).trim().removePrefix("gitdir:").trim().let { base.resolve(it).normalize() }
        } else dot
        val log = dir.resolve("logs").resolve("HEAD")
        "${Files.getLastModifiedTime(log).toMillis()}:${Files.size(log)}"
    } catch (_: IOException) {
        null
    }

    companion object {
        private const val TIMEOUT_MS = 5000
        private const val UNSTAMPED_MS = 5000
        private const val FIELD = "%x1f"
        private const val RECORD = "%x1e"
        private val ID_LIKE = Regex("[0-9a-fA-F]{7,40}")
        private val ID = Regex("\\bT-[1-9][0-9]*\\b")

        /** The commit of each item id named in [log], which is the output of `git log`, newest commit first. */
        internal fun parse(log: String): Map<String, String> {
            val commits = HashMap<String, String>()
            for (record in log.split('\u001e')) {
                val hash = record.substringBefore('\u001f', "").trim()
                if (hash.isEmpty()) continue
                ID.findAll(record.substringAfter('\u001f')).forEach { commits.putIfAbsent(it.value, hash) }
            }
            return commits
        }
    }
}
