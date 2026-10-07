package io.github.konove.notmytodo.channel

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import io.github.konove.notmytodo.handoff.ClaudeLauncher
import java.io.File
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Makes the bridge known to Claude Code. Claude Code is told to start a small launcher script
 * at a path that never changes; the script is rewritten with the current paths of the IDE's
 * Java and of this plugin, so updating either does not break the registration.
 */
class ChannelRegistration(
    private val configDir: Path = defaultConfigDir(),
    private val run: (List<String>) -> Outcome? = { runClaude(it) },
) {
    enum class Status { REGISTERED, NOT_REGISTERED, NO_CLAUDE }

    /** How a `claude` command ended. */
    data class Outcome(val exit: Int, val output: String)

    val launcher: Path get() = configDir.resolve(if (SystemInfo.isWindows) "bridge.cmd" else "bridge")

    val launcherExists: Boolean get() = Files.exists(launcher)

    @Throws(IOException::class)
    fun writeLauncher(java: Path = bundledJava(), classes: Path = bridgeClasses()): Path {
        val file = launcher
        Files.createDirectories(file.parent)
        val text = launcherText(java, classes, SystemInfo.isWindows)
        val current = try {
            if (Files.exists(file)) Files.readString(file) else null
        } catch (e: CharacterCodingException) {
            null // not text, so it is not ours: replace it
        }
        if (current == text) return file
        // Written beside the launcher and moved over it, so Claude Code never starts a half-written script.
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, text)
            val executable = temporary.toFile().setExecutable(true)
            if (!executable && "posix" in file.fileSystem.supportedFileAttributeViews()) throw IOException("$file could not be made executable")
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return file
    }

    /** Asks Claude Code. Takes a moment; do not call on the event thread. */
    fun status(): Status {
        val outcome = run(listOf("mcp", "get", NAME)) ?: return Status.NO_CLAUDE
        return if (outcome.exit == 0) Status.REGISTERED else Status.NOT_REGISTERED
    }

    /** Registers the launcher for every project of this user. Returns what went wrong, or null. */
    fun register(): String? {
        // An older registration may point elsewhere; adding over it would fail.
        run(listOf("mcp", "remove", "--scope", "user", NAME)) ?: return NO_CLAUDE
        return problem(run(listOf("mcp", "add", "--scope", "user", NAME, "--", launcher.toString())))
    }

    fun unregister(): String? = problem(run(listOf("mcp", "remove", "--scope", "user", NAME)))

    private fun problem(outcome: Outcome?): String? = when {
        outcome == null -> NO_CLAUDE
        outcome.exit == 0 -> null
        else -> outcome.output.ifBlank { "claude ended with exit code ${outcome.exit}." }
    }

    companion object {
        private const val NAME = ClaudeLauncher.CHANNEL_NAME
        private const val NO_CLAUDE = "Claude Code was not found."
        private const val TIMEOUT_MS = 30_000

        fun launcherText(java: Path, classes: Path, windows: Boolean): String {
            val main = BridgeMain::class.java.name
            if (windows) {
                // cmd expands %VAR% even inside quotes, so a literal % is doubled; the file is UTF-8, so cmd is switched to it.
                fun escape(path: Path) = path.toString().replace("%", "%%")
                return "@echo off\r\nchcp 65001>nul\r\n\"${escape(java)}\" -cp \"${escape(classes)}\" $main\r\n"
            }
            fun quote(path: Path) = "'" + path.toString().replace("'", "'\\''") + "'"
            // exec, so that Claude Code is the bridge's direct parent.
            return "#!/bin/sh\nexec ${quote(java)} -cp ${quote(classes)} $main\n"
        }

        fun defaultConfigDir(): Path {
            val home = System.getProperty("user.home")
            val base = when {
                SystemInfo.isWindows -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming"
                SystemInfo.isMac -> "$home/Library/Application Support"
                else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
            }
            return Path.of(base, "not-my-todo")
        }

        /** The IDE runs on its bundled Java, so this is it. */
        fun bundledJava(): Path = Path.of(System.getProperty("java.home"), "bin", if (SystemInfo.isWindows) "java.exe" else "java")

        /** The jar, or the directory of classes when run from the build, that holds the bridge. */
        fun bridgeClasses(): Path = Path.of(
            PathManager.getJarPathForClass(BridgeMain::class.java) ?: throw IOException("the plugin's own classes could not be located")
        )

        /** Runs `claude` with [args]. Null when Claude Code is not installed. */
        fun runClaude(args: List<String>): Outcome? {
            val claude = findClaude() ?: return null
            return try {
                val output = CapturingProcessHandler(GeneralCommandLine(listOf(claude) + args)).runProcess(TIMEOUT_MS)
                if (output.isTimeout) Outcome(-1, "claude did not answer in ${TIMEOUT_MS / 1000} seconds.")
                else Outcome(output.exitCode, (output.stdout + output.stderr).trim())
            } catch (e: ExecutionException) {
                Outcome(-1, e.message ?: "claude could not be started.")
            }
        }

        /** An IDE started from the desktop often lacks the shell's PATH, so the usual install places are tried too. */
        private fun findClaude(): String? {
            val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }.map { "$it/claude" }
            val home = System.getProperty("user.home")
            val usual = listOf("$home/.local/bin/claude", "$home/.claude/local/claude", "/usr/local/bin/claude", "/opt/homebrew/bin/claude")
            return (onPath + usual).firstOrNull { Files.isExecutable(Path.of(it)) }
        }
    }
}
