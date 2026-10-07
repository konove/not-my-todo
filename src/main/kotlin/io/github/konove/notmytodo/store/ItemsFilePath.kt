package io.github.konove.notmytodo.store

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Checks where the items file may live. */
object ItemsFilePath {
    /**
     * The file that [relative] names under the project root [base].
     * Throws [StoreException] saying what is wrong when it cannot be used.
     */
    fun resolve(base: Path, relative: String): Path {
        val text = relative.trim()
        if (text.isEmpty()) throw StoreException("the items file path must not be empty")
        val given = try {
            Path.of(text)
        } catch (e: InvalidPathException) {
            throw StoreException("the items file path is not a valid path: ${e.reason}")
        }
        if (given.isAbsolute) throw StoreException("the items file path must be relative to the project root")
        val root = base.normalize()
        val file = root.resolve(given).normalize()
        if (file == root || !file.startsWith(root)) throw StoreException("the items file must be inside the project")
        if (Files.isDirectory(file)) throw StoreException("$text is a directory; give the path of a file")
        return file
    }

    /**
     * Checks that [file], as [resolve] returned it for the project root [base], can be written.
     * Throws [StoreException] saying what is wrong when it cannot.
     */
    fun requireWritable(base: Path, file: Path) {
        val root = base.normalize()
        fun shown(path: Path): String = if (path == root) "the project root" else if (path.startsWith(root)) root.relativize(path).toString() else path.toString()
        if (Files.exists(file) && !Files.isWritable(file)) {
            throw StoreException("the items file cannot be written: ${shown(file)} is read-only")
        }
        var ancestor: Path? = file.parent
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.parent
        if (ancestor != null && (!Files.isDirectory(ancestor) || !Files.isWritable(ancestor))) {
            throw StoreException("the items file cannot be written there: ${shown(ancestor)} is not a writable directory")
        }
    }
}
