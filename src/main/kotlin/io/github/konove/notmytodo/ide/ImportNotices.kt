package io.github.konove.notmytodo.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Says what became of the items file of 0.2: once when it was imported, and once an IDE session when
 * it was not, however often the project is opened again.
 */
internal class ImportNotices(private val project: Project) {
    private val told = ConcurrentHashMap.newKeySet<ProjectOpener.Legacy>()

    fun tell(key: String, base: Path, legacy: ProjectOpener.Legacy) {
        if (!told.add(legacy)) return
        val name = shown(base, legacy.file)
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
        val notification = when (legacy) {
            is ProjectOpener.Legacy.Imported -> group.createNotification(
                "Moved ${legacy.count} ${if (legacy.count == 1) "item" else "items"} into the store",
                "The items of $name are now in project $key of not-my-todo-store, where Claude Code sessions reach them " +
                    "through the notmytodo-store server. The file is no longer read.",
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring("Delete the File") { delete(project, legacy.file) })
                .addAction(NotificationAction.createSimpleExpiring("Keep It") {})
            is ProjectOpener.Legacy.Clash -> group.createNotification(
                "The items of $name were not moved into the store",
                "Project $key already has ${legacy.ids.joinToString(", ")}. Nothing was written, and the items stay in the file.",
                NotificationType.WARNING,
            )
            is ProjectOpener.Legacy.Elsewhere -> group.createNotification(
                "The items of $name were not moved into the store",
                "Project $key already holds the items moved there from ${legacy.other}.",
                NotificationType.INFORMATION,
            )
        }
        notification.notify(project)
    }

    companion object {
        /** [file] as the user knows it: relative to the project's folder when it is in it. */
        fun shown(base: Path, file: Path): String =
            if (file.startsWith(base)) base.relativize(file).joinToString("/") else file.toString()

        /** Deletes the items file, and its .todos folder when nothing else is left in it, through the IDE so that version control sees them go. */
        fun delete(project: Project, file: Path) {
            val found = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
            try {
                WriteCommandAction.writeCommandAction(project).withName("Delete the Items File").run<IOException> {
                    val folder = found.parent
                    found.delete(ImportNotices::class.java)
                    if (folder != null && folder.name == ".todos" && folder.children.isEmpty()) folder.delete(ImportNotices::class.java)
                }
            } catch (e: IOException) {
                Messages.showErrorDialog(project, "${file.fileName} could not be deleted: ${e.message}", "Not My TODO")
            }
        }
    }
}
