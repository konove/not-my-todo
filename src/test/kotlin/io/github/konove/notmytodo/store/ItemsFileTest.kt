package io.github.konove.notmytodo.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ItemsFileTest {
    private val base: Path = Files.createTempDirectory("nmt-items")

    @Test
    fun `a relative path inside the project resolves`() {
        assertEquals(base.resolve(".todos/items.json"), ItemsFilePath.resolve(base, ".todos/items.json"))
        assertEquals(base.resolve("notes/todo.json"), ItemsFilePath.resolve(base, "  notes/./todo.json "))
    }

    @Test
    fun `paths that are empty, absolute, outside the project or a directory are refused`() {
        Files.createDirectories(base.resolve("dir"))
        for (bad in listOf("", "   ", "/etc/items.json", "../items.json", "a/../../items.json", ".", "dir")) {
            val e = assertThrows("for '$bad'", StoreException::class.java) { ItemsFilePath.resolve(base, bad) }
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `requireWritable refuses a parent that is a regular file`() {
        Files.writeString(base.resolve("README.md"), "x")
        val file = ItemsFilePath.resolve(base, "README.md/items.json")
        val e = assertThrows(StoreException::class.java) { ItemsFilePath.requireWritable(base, file) }
        assertEquals("the items file cannot be written there: README.md is not a writable directory", e.message)
    }

    @Test
    fun `requireWritable refuses a read-only directory`() {
        Assume.assumeTrue("posix" in base.fileSystem.supportedFileAttributeViews())
        val dir = Files.createDirectories(base.resolve("ro"))
        val before = Files.getPosixFilePermissions(dir)
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
            Assume.assumeFalse(Files.isWritable(dir))
            val file = ItemsFilePath.resolve(base, "ro/deeper/items.json")
            val e = assertThrows(StoreException::class.java) { ItemsFilePath.requireWritable(base, file) }
            assertEquals("the items file cannot be written there: ro is not a writable directory", e.message)
        } finally {
            Files.setPosixFilePermissions(dir, before)
        }
    }

    @Test
    fun `requireWritable accepts a path several missing directories deep under a writable one`() {
        ItemsFilePath.requireWritable(base, ItemsFilePath.resolve(base, "a/b/c/items.json"))
    }

    @Test
    fun `requireWritable refuses an existing read-only file`() {
        Assume.assumeTrue("posix" in base.fileSystem.supportedFileAttributeViews())
        val file = Files.writeString(base.resolve("items.json"), "[]")
        val before = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
            Assume.assumeFalse(Files.isWritable(file))
            val e = assertThrows(StoreException::class.java) { ItemsFilePath.requireWritable(base, file) }
            assertEquals("the items file cannot be written: items.json is read-only", e.message)
        } finally {
            Files.setPosixFilePermissions(file, before)
        }
    }

    @Test
    fun `requireWritable names the project root when the root is not writable`() {
        Assume.assumeTrue("posix" in base.fileSystem.supportedFileAttributeViews())
        val before = Files.getPosixFilePermissions(base)
        try {
            Files.setPosixFilePermissions(base, PosixFilePermissions.fromString("r-xr-xr-x"))
            Assume.assumeFalse(Files.isWritable(base))
            val file = ItemsFilePath.resolve(base, "items.json")
            val e = assertThrows(StoreException::class.java) { ItemsFilePath.requireWritable(base, file) }
            assertTrue(e.message, e.message!!.contains("the project root"))
        } finally {
            Files.setPosixFilePermissions(base, before)
        }
    }

    @Test
    fun `resolve accepts a path under a read-only directory`() {
        Assume.assumeTrue("posix" in base.fileSystem.supportedFileAttributeViews())
        val dir = Files.createDirectories(base.resolve("ro"))
        val before = Files.getPosixFilePermissions(dir)
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
            Assume.assumeFalse(Files.isWritable(dir))
            assertEquals(base.resolve("ro/deeper/items.json"), ItemsFilePath.resolve(base, "ro/deeper/items.json"))
        } finally {
            Files.setPosixFilePermissions(dir, before)
        }
    }

    @Test
    fun `resolve accepts an existing read-only file`() {
        Assume.assumeTrue("posix" in base.fileSystem.supportedFileAttributeViews())
        val file = Files.writeString(base.resolve("items.json"), "[]")
        val before = Files.getPosixFilePermissions(file)
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"))
            Assume.assumeFalse(Files.isWritable(file))
            assertEquals(file, ItemsFilePath.resolve(base, "items.json"))
        } finally {
            Files.setPosixFilePermissions(file, before)
        }
    }

    @Test
    fun `moveTo switches files, reads the new one and tells listeners`() {
        val first = base.resolve("a/items.json")
        val second = base.resolve("b/items.json")
        val store = ItemStore(first)
        store.create(Draft("in the first file"))
        ItemStore(second).create(Draft("in the second file"))
        var calls = 0
        store.addListener { calls++ }

        store.moveTo(second)

        assertEquals(second, store.file)
        assertEquals(listOf("in the second file"), store.items.map { it.title })
        assertEquals(1, calls)
        assertTrue(Files.exists(first))
        store.create(Draft("another"))
        assertEquals(2, ItemStore(second).items.size)
        assertEquals(1, ItemStore(first).items.size)
    }

    @Test
    fun `moveTo with move takes the file along when the new place is empty`() {
        val first = base.resolve("c/items.json")
        val second = base.resolve("d/deep/items.json")
        val store = ItemStore(first)
        store.create(Draft("travels"))

        store.moveTo(second, move = true)

        assertFalse(Files.exists(first))
        assertEquals(listOf("travels"), store.items.map { it.title })
        assertEquals(listOf("travels"), ItemStore(second).items.map { it.title })
    }

    @Test
    fun `moveTo with move never overwrites a file that is already there`() {
        val first = base.resolve("e/items.json")
        val second = base.resolve("f/items.json")
        val store = ItemStore(first)
        store.create(Draft("stays behind"))
        ItemStore(second).create(Draft("already here"))

        store.moveTo(second, move = true)

        assertTrue(Files.exists(first))
        assertEquals(listOf("already here"), store.items.map { it.title })
    }
}
