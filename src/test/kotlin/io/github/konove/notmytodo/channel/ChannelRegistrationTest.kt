package io.github.konove.notmytodo.channel

import io.github.konove.notmytodo.channel.ChannelRegistration.Outcome
import io.github.konove.notmytodo.channel.ChannelRegistration.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class ChannelRegistrationTest {
    private val dir = Files.createTempDirectory("nmt-reg")
    private val calls = ArrayList<List<String>>()

    private fun registration(answer: (List<String>) -> Outcome?) = ChannelRegistration(dir) { args ->
        calls += args
        answer(args)
    }

    @Test
    fun `the launcher script runs the bridge with the given java, quoting awkward paths`() {
        val sh = ChannelRegistration.launcherText(Path.of("/opt/my ide/jbr/bin/java"), Path.of("/home/it's me/plugin.jar"), windows = false)
        assertEquals(
            "#!/bin/sh\nexec '/opt/my ide/jbr/bin/java' -cp '/home/it'\\''s me/plugin.jar' io.github.konove.notmytodo.channel.BridgeMain\n",
            sh,
        )
        val cmd = ChannelRegistration.launcherText(Path.of("C:\\ide\\jbr\\bin\\java.exe"), Path.of("C:\\p\\plugin.jar"), windows = true)
        assertEquals(
            "@echo off\r\nchcp 65001>nul\r\n\"C:\\ide\\jbr\\bin\\java.exe\" -cp \"C:\\p\\plugin.jar\" io.github.konove.notmytodo.channel.BridgeMain\r\n",
            cmd,
        )
    }

    @Test
    fun `the Windows launcher doubles percent signs and keeps non-ASCII characters`() {
        val cmd = ChannelRegistration.launcherText(Path.of("C:\\Users\\Zoë 100%\\jbr\\bin\\java.exe"), Path.of("C:\\p\\100%\\plugin.jar"), windows = true)
        assertTrue(cmd.contains("\"C:\\Users\\Zoë 100%%\\jbr\\bin\\java.exe\""))
        assertTrue(cmd.contains("-cp \"C:\\p\\100%%\\plugin.jar\""))
    }

    @Test
    fun `writeLauncher creates an executable file and replaces an older one`() {
        val registration = registration { null }
        Files.createDirectories(registration.launcher.parent)
        Files.writeString(registration.launcher, "old")
        val file = registration.writeLauncher(Path.of("/jbr/bin/java"), Path.of("/plugin.jar"))
        assertEquals(registration.launcher, file)
        assertTrue(Files.readString(file).contains("'/jbr/bin/java' -cp '/plugin.jar'"))
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) assertTrue(Files.isExecutable(file))
    }

    @Test
    fun `writeLauncher leaves an up to date file alone, replaces a stale one and leaves no temporary file`() {
        val registration = registration { null }
        val file = registration.writeLauncher(Path.of("/jbr/bin/java"), Path.of("/plugin.jar"))
        val old = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L)
        Files.setLastModifiedTime(file, old)
        registration.writeLauncher(Path.of("/jbr/bin/java"), Path.of("/plugin.jar"))
        assertEquals(old, Files.getLastModifiedTime(file))
        registration.writeLauncher(Path.of("/other/java"), Path.of("/plugin.jar"))
        assertTrue(Files.readString(file).contains("'/other/java' -cp '/plugin.jar'"))
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) assertTrue(Files.isExecutable(file))
        Files.list(dir).use { assertEquals(listOf(file), it.toList()) }
    }

    @Test
    fun `writeLauncher replaces a launcher that is not valid text`() {
        val registration = registration { null }
        Files.createDirectories(registration.launcher.parent)
        Files.write(registration.launcher, byteArrayOf(0xC3.toByte(), 0x28))
        val file = registration.writeLauncher(Path.of("/jbr/bin/java"), Path.of("/plugin.jar"))
        assertTrue(Files.readString(file).contains("'/jbr/bin/java' -cp '/plugin.jar'"))
    }

    @Test
    fun `status follows what claude says`() {
        assertEquals(Status.REGISTERED, registration { Outcome(0, "notmytodo: ...") }.status())
        assertEquals(Status.NOT_REGISTERED, registration { Outcome(1, "No MCP server named \"notmytodo\".") }.status())
        assertEquals(Status.NO_CLAUDE, registration { null }.status())
        assertEquals(listOf("mcp", "get", "notmytodo"), calls.last())
    }

    @Test
    fun `register replaces an older registration and points at the launcher`() {
        val registration = registration { Outcome(0, "") }
        assertNull(registration.register())
        assertEquals(
            listOf(
                listOf("mcp", "remove", "--scope", "user", "notmytodo"),
                listOf("mcp", "add", "--scope", "user", "notmytodo", "--", registration.launcher.toString()),
            ),
            calls,
        )
    }

    @Test
    fun `register reports what claude said when adding fails, whatever removing did`() {
        val registration = registration { args -> if ("add" in args) Outcome(1, "it did not work") else Outcome(1, "nothing to remove") }
        assertEquals("it did not work", registration.register())
    }

    @Test
    fun `unregister removes and reports failure`() {
        assertNull(registration { Outcome(0, "") }.unregister())
        assertEquals(listOf("mcp", "remove", "--scope", "user", "notmytodo"), calls.last())
        assertEquals("no such server", registration { Outcome(1, "no such server") }.unregister())
    }

    @Test
    fun `without claude both say so`() {
        assertEquals("Claude Code was not found.", registration { null }.register())
        assertEquals("Claude Code was not found.", registration { null }.unregister())
    }
}
