package io.github.konove.notmytodo.channel

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The IDE's end of the channel for one project: a loopback socket that the bridges of running
 * Claude Code sessions connect to. Its port and a token are published in a file under
 * [cacheDir] that only the user can read. The hub and a bridge each prove to the other that
 * they know the token, without sending it, so no other local process can put text in front
 * of Claude or pose as the IDE.
 *
 * What a connection can cost before it has proved anything is bounded: the whole opening
 * exchange must be done within [helloTimeoutMs] of the connection, each line has a size cap,
 * and at most [maxPending] connections may be in that exchange at once; further ones are closed
 * at once. A connection that is still in the exchange when the hub stops never becomes a session.
 */
class ChannelHub(
    private val projectDir: Path,
    private val cacheDir: Path,
    private val helloTimeoutMs: Int = 5_000,
    private val maxPending: Int = 16,
) {
    /** One connected bridge. [flag] is `yes`, `no` or `unknown`: whether Claude Code loaded the channel. */
    private class Session(val socket: Socket, val cwd: String, val flag: String)

    private val random = SecureRandom()

    // A new one every time listening starts, so the token in a port file left behind by a
    // crash is known to no live listener. Written and read under the hub's monitor.
    private var token = randomHex()

    /** 128 random bits as 32 hex characters. */
    private fun randomHex(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // In the order of connecting; the last is the newest.
    private val sessions = CopyOnWriteArrayList<Session>()
    private var server: ServerSocket? = null
    private var portFile: Path? = null

    // Connections accepted that have not yet finished the opening exchange.
    private val pending = AtomicInteger()

    /** Called on a background thread when a session comes or goes. */
    @Volatile
    var onChange: () -> Unit = {}

    val running: Boolean @Synchronized get() = server != null

    /** Sessions that can receive a prompt. */
    val connected: Int get() = sessions.count { it.flag != "no" }

    /** Sessions whose Claude Code was started without the channel flag, so it would drop what is sent. */
    val withoutFlag: Int get() = sessions.count { it.flag == "no" }

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (server != null) return
        val file = cacheDir.resolve(BridgeMain.key(projectDir.toRealPath().toString()))
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        token = randomHex()
        try {
            publish(file, socket.localPort)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        server = socket
        portFile = file
        thread(isDaemon = true, name = "Not My TODO channel") { accept(socket) }
    }

    fun stop() {
        val dropped = synchronized(this) {
            val socket = server ?: return
            server = null
            runCatching { socket.close() }
            portFile?.let { runCatching { Files.deleteIfExists(it) } }
            portFile = null
            sessions.toList().also { sessions.clear() }
        }
        dropped.forEach { runCatching { it.socket.close() } }
        if (dropped.isNotEmpty()) onChange()
    }

    /**
     * Puts [prompt] in front of Claude in the newest session that can receive it.
     * Returns false when there is none.
     */
    fun send(prompt: String, itemIds: List<String>): Boolean {
        val meta = mapOf((if (itemIds.size == 1) "item_id" else "item_ids") to itemIds.joinToString(","))
        val line = (ChannelMessage.line(prompt, meta) + "\n").toByteArray()
        for (session in sessions.reversed()) {
            if (session.flag == "no") continue
            try {
                synchronized(session) {
                    session.socket.getOutputStream().apply {
                        write(line)
                        flush()
                    }
                }
                return true
            } catch (_: IOException) {
                // It went away; closing it makes its reader drop it.
                runCatching { session.socket.close() }
            }
        }
        return false
    }

    private fun publish(file: Path, port: Int) {
        Files.createDirectories(file.parent)
        Files.deleteIfExists(file)
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system; the user's cache directory is private to them there.
            Files.createFile(file)
        }
        Files.writeString(file, "$port\n$token\n")
    }

    private fun accept(server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            if (pending.incrementAndGet() > maxPending) {
                pending.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(helloTimeoutMs.toLong())
            try {
                thread(isDaemon = true, name = "Not My TODO channel session") { serve(socket, server, deadline) }
            } catch (e: Throwable) {
                pending.decrementAndGet()
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Answers the bridge's challenge, checks its answer to ours, then waits for it to go away.
     * Each side proves it knows the token from the port file without sending it; see [BridgeMain.proof].
     * [from] is the listener the connection came in on: it becomes a session only if that is still
     * the hub's listener, and the token used throughout is the one of that listener.
     */
    private fun serve(socket: Socket, from: ServerSocket, deadline: Long) {
        var session: Session? = null
        var pendingHeld = true
        fun release() {
            if (pendingHeld) {
                pendingHeld = false
                pending.decrementAndGet()
            }
        }
        try {
            val secret = synchronized(this) { if (server === from) token else null } ?: return
            val input = BufferedInputStream(socket.getInputStream())
            val bridgeNonce = readLine(socket, input, deadline, MAX_NONCE) ?: return
            val nonce = randomHex()
            socket.getOutputStream().apply {
                write("${BridgeMain.proof(secret, "ide", bridgeNonce)}\n$nonce\n".toByteArray())
                flush()
            }
            val given = readLine(socket, input, deadline, MAX_PROOF) ?: return
            if (!MessageDigest.isEqual(given.toByteArray(), BridgeMain.proof(secret, "bridge", nonce).toByteArray())) {
                LOG.log(System.Logger.Level.WARNING, "A connection to the Not My TODO channel could not prove it knows the token and was closed")
                return
            }
            val cwd = readLine(socket, input, deadline, MAX_CWD) ?: return
            val flag = readLine(socket, input, deadline, MAX_FLAG) ?: return
            socket.soTimeout = 0
            val added = Session(socket, cwd, flag)
            val accepted = synchronized(this) {
                (server === from).also { if (it) sessions += added }
            }
            release()
            if (!accepted) return
            session = added
            onChange()
            // The bridge sends nothing more; this returns when it closes the connection.
            while (input.read() >= 0) Unit
        } catch (_: IOException) {
        } finally {
            release()
            runCatching { socket.close() }
            if (session != null && sessions.remove(session)) onChange()
        }
    }

    /**
     * One line of at most [cap] bytes, without its line end, or null at the end of the stream,
     * when the line is longer, or when [deadline] (a [System.nanoTime] value) passes first.
     * The deadline is for the whole exchange, not per read, so a slow trickle cannot hold the connection.
     */
    private fun readLine(socket: Socket, input: BufferedInputStream, deadline: Long, cap: Int): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMs <= 0) return null
            socket.soTimeout = leftMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (line.size() >= cap) return null
            line.write(b)
        }
        val text = line.toString(StandardCharsets.UTF_8)
        return if (text.endsWith("\r")) text.dropLast(1) else text
    }

    private companion object {
        const val MAX_NONCE = 64
        const val MAX_PROOF = 64
        const val MAX_CWD = 4096
        const val MAX_FLAG = 16
        val LOG: System.Logger = System.getLogger(ChannelHub::class.java.name)
    }
}
