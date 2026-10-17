package io.github.konove.notmytodo.ide

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import java.nio.file.Path

/**
 * The code of another project's item is in that project's checkout, which is another IDE project:
 * it is shown in that project's window, opened when it is not, after asking.
 */
object Checkouts {
    /** The open IDE project whose repository root is [checkout]: a window opened at a subfolder of the checkout counts. */
    fun openIn(checkout: Path, open: List<Project> = ProjectManager.getInstance().openProjects.toList()): Project? {
        val wanted = checkout.toAbsolutePath().normalize()
        return open.firstOrNull { !it.isDisposed && TodoService.getInstance(it).root.toAbsolutePath().normalize() == wanted }
    }

    /**
     * The window [checkout] is open in, brought to the front, or a new one after asking; null when
     * the user says no. [ref] is the item's id as it is asked about. Replaced in tests, which cannot open windows.
     */
    internal var window: (Project, Path, String) -> Project? = { project, checkout, ref ->
        openIn(checkout)?.also { ProjectUtil.focusProjectWindow(it, true) }
            ?: ProjectUtil.findAndFocusExistingProjectForPath(checkout) ?: run {
            val answer = Messages.showYesNoDialog(
                project, "$ref is in $checkout. Open it in a new window and go to its code?", "Not My TODO", null,
            )
            if (answer == Messages.YES) ProjectUtil.openOrImport(checkout, null, true) else null
        }
    }

    /**
     * Goes to the code of [viewId], an item of another project, at its anchor [index]. With no checkout here there is
     * nowhere to go. The anchor is named by its place in the list, since that window may have moved its lines.
     */
    fun goTo(project: Project, viewId: String, index: Int = 0) {
        val view = StoreView.getInstance(project)
        val ref = ViewIds.split(viewId, view.home) ?: return
        val checkout = view.checkout(ref.project!!) ?: return
        val there = window(project, checkout, viewId) ?: return
        // That window has its own store project, the one of the checkout, where the item has its bare id.
        // A checkout may be recorded under two projects; only the window of this one has its item.
        val store = TodoService.getInstance(there).store
        if (store.key != ref.project) return
        store.find(ref.id)?.let { ItemActions.navigate(there, it, it.anchors.getOrNull(index)) }
    }
}
