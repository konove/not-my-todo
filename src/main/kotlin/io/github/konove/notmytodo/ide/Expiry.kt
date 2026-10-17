package io.github.konove.notmytodo.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer

/**
 * True once this is disposed, for what it asks the event thread to do later. Unloading the plugin drops the tasks
 * that have expired; one that expires only with the project waits behind a modal dialog, such as the one the
 * plugin is uninstalled from, keeps its classes from unloading, and runs once the dialog closes against the
 * services of the plugin installed since.
 */
fun Disposable.expiry(): Condition<Any?> {
    val checked = Disposer.newCheckedDisposable(this)
    return Condition { checked.isDisposed }
}
