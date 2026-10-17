package io.github.konove.notmytodo.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Says what became of the items file of 0.2: once when it was imported, and once an IDE session when
 * it was not, or when the file changed after its import, however often the project is opened again.
 */
internal class ImportNotices(private val project: Project) {
    private val told = ConcurrentHashMap.newKeySet<ProjectOpener.Legacy>()

    /** Says what became of [legacy]. [again] imports the file with new ids, for a clash. */
    fun tell(key: String, base: Path, legacy: ProjectOpener.Legacy, again: () -> Unit) {
        // Ignore is kept across sessions by the hash, so only another edit of the file is said again.
        if (legacy is ProjectOpener.Legacy.Changed && PropertiesComponent.getInstance(project).getValue(IGNORED) == legacy.sha256) return
        if (!told.add(legacy)) return
        val name = shown(base, legacy.file)
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Not My TODO")
        val open = NotificationAction.createSimple("Open the File") { openFile(project, legacy.file) }
        val delete = NotificationAction.createSimpleExpiring("Delete the File") { delete(project, legacy.file) }
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
                "Project $key already has ${legacy.ids.joinToString(", ")}. Nothing was written, and the items stay in the file. " +
                    "Import them with new ids, after the project's last item.",
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring("Import with New Ids") { again() }).addAction(open).addAction(delete)
            is ProjectOpener.Legacy.Elsewhere -> group.createNotification(
                "The items of $name were not moved into the store",
                "Project $key already holds the items moved there from ${legacy.other}.",
                NotificationType.INFORMATION,
            ).addAction(open).addAction(delete)
            is ProjectOpener.Legacy.Changed -> group.createNotification(
                "The items file of $name changed",
                "The items file of $name changed after its items were moved to the store. The store does not read it.",
                NotificationType.INFORMATION,
            ).addAction(open).addAction(delete).addAction(NotificationAction.createSimpleExpiring("Ignore") {
                PropertiesComponent.getInstance(project).setValue(IGNORED, legacy.sha256)
            })
        }
        notification.notify(project)
    }

    companion object {
        /** The hash of the changed items file the user said to ignore, in the IDE project's properties. */
        const val IGNORED = "notmytodo.ignoredItemsFile"

        /** Opens [file] in an editor. */
        fun openFile(project: Project, file: Path) {
            val found = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
            FileEditorManager.getInstance(project).openFile(found, true)
        }

        /** [file] as the user knows it: relative to the project's folder when it is in it. */
        fun shown(base: Path, file: Path): String =
            if (file.startsWith(base)) base.relativize(file).joinToString("/") else file.toString()

        /**
         * Deletes the items file, and its .todos folder when nothing else is left in it, through the IDE so
         * that version control sees them go. Whether the folder is empty is asked of the disk, not of the
         * IDE, which may not have seen a file written there since: deleting a folder takes all it holds.
         */
        fun delete(project: Project, file: Path) {
            val found = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
            try {
                WriteCommandAction.writeCommandAction(project).withName("Delete the Items File").run<IOException> {
                    val folder = found.parent
                    found.delete(ImportNotices::class.java)
                    if (folder != null && folder.name == ".todos" && empty(folder.toNioPath())) folder.delete(ImportNotices::class.java)
                }
            } catch (e: IOException) {
                Messages.showErrorDialog(project, "${file.fileName} could not be deleted: ${e.message}", "Not My TODO")
            }
        }

        /** False for a folder that cannot be listed, so that it is left. */
        private fun empty(folder: Path): Boolean = try {
            Files.list(folder).use { it.findAny().isEmpty }
        } catch (_: IOException) {
            false
        }
    }
}
