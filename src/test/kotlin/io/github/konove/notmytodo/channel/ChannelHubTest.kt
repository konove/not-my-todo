package io.github.konove.notmytodo.channel

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ChannelHubTest {
    private val root = Files.createTempDirectory("nmt-hub").toRealPath()
    private val project = Files.createDirectories(root.resolve("project"))
    private val cache = root.resolve("cache")
    private var hub = ChannelHub(project, cache)
    private val sockets = ArrayList<Socket>()

    // One reader per socket, kept, so nothing a reader has buffered is lost between reads.
    private val readers = HashMap<Socket, java.io.BufferedReader>()
    private fun reader(socket: Socket) = readers.getOrPut(socket) { socket.getInputStream().bufferedReader() }

    @After
    fun stop() {
        sockets.forEach { it.close() }
        hub.stop()
    }

    private val portFile get() = cache.resolve(BridgeMain.key(project.toString()))

    private fun open(): Socket {
        val port = Files.readAllLines(portFile)[0].toInt()
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        sockets += socket
        socket.soTimeout = 5_000
        return socket
    }

    private fun write(socket: Socket, text: String) = socket.getOutputStream().run {
        write(text.toByteArray())
        flush()
    }

    /** Connects the way the bridge does: challenge the hub, check its proof, then prove itself with [token]. */
    private fun connect(token: String? = null, flag: String = "yes"): Socket {
        val secret = Files.readAllLines(portFile)[1]
        val socket = open()
        val nonce = "0123456789abcdef0123456789abcdef"
        write(socket, "$nonce\n")
        assertEquals(BridgeMain.proof(secret, "ide", nonce), reader(socket).readLine())
        val hubNonce = reader(socket).readLine()
        write(socket, "${BridgeMain.proof(token ?: secret, "bridge", hubNonce)}\n$project\n$flag\n")
        return socket
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!check()) {
            if (System.nanoTime() > end) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    /** Whether the hub has closed [socket]. Closing with unread input can show up as a reset instead of an end. */
    private fun closedByHub(socket: Socket): Boolean = try {
        reader(socket).read() == -1
    } catch (_: java.net.SocketTimeoutException) {
        false
    } catch (_: java.io.IOException) {
        true
    }

    private fun contentOf(socket: Socket): String =
        JsonParser.parseString(reader(socket).readLine()).asJsonObject["params"].asJsonObject["content"].asString

    @Test
    fun `start publishes the port and a token only the owner can read, and stop removes them`() {
        assertFalse(hub.running)
        hub.start()
        hub.start()
        assertTrue(hub.running)
        val lines = Files.readAllLines(portFile)
        assertEquals(2, lines.size)
        assertTrue(lines[0].toInt() in 1..65535)
        assertEquals(32, lines[1].length)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(portFile)))
        }
        hub.stop()
        assertFalse(hub.running)
        assertFalse(Files.exists(portFile))

        hub.start()
        assertTrue("listening again uses a new token", Files.readAllLines(portFile)[1] != lines[1])
    }

    @Test
    fun `a bridge with the token is a session and receives the prompt with the item id`() {
        val changes = java.util.concurrent.atomic.AtomicInteger()
        hub.onChange = { changes.incrementAndGet() }
        hub.start()
        assertFalse(hub.send("nobody there", listOf("T-1")))
        val socket = connect()
        waitFor("the session") { hub.connected == 1 }
        waitFor("the change callback") { changes.get() >= 1 }

        assertTrue(hub.send("Fix \"T-1\"\nnow", listOf("T-1")))

        val message = JsonParser.parseString(reader(socket).readLine()).asJsonObject
        assertEquals("notifications/claude/channel", message["method"].asString)
        val params = message["params"].asJsonObject
        assertEquals("Fix \"T-1\"\nnow", params["content"].asString)
        assertEquals("T-1", params["meta"].asJsonObject["item_id"].asString)
    }

    @Test
    fun `several item ids travel together`() {
        hub.start()
        val socket = connect()
        waitFor("the session") { hub.connected == 1 }
        assertTrue(hub.send("Fix two", listOf("T-1", "T-2")))
        val meta = JsonParser.parseString(reader(socket).readLine()).asJsonObject["params"].asJsonObject["meta"].asJsonObject
        assertEquals("T-1,T-2", meta["item_ids"].asString)
        assertFalse(meta.has("item_id"))
    }

    @Test
    fun `the hub proves itself to the bridge without ever sending the token`() {
        hub.start()
        val secret = Files.readAllLines(portFile)[1]
        val first = open()
        write(first, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n")
        val proof = reader(first).readLine()
        val firstNonce = reader(first).readLine()
        assertEquals(BridgeMain.proof(secret, "ide", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), proof)
        assertFalse(proof.contains(secret))
        assertFalse(firstNonce.contains(secret))
        assertTrue(Regex("[0-9a-f]{32}").matches(firstNonce))

        val second = open()
        write(second, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n")
        reader(second).readLine()
        assertTrue("each connection gets a fresh nonce", reader(second).readLine() != firstNonce)
        assertEquals(0, hub.connected)
    }

    @Test
    fun `a bridge that answers the hub's challenge with the token itself, not a proof, is shut out`() {
        hub.start()
        val secret = Files.readAllLines(portFile)[1]
        val socket = open()
        write(socket, "0123456789abcdef0123456789abcdef\n")
        reader(socket).readLine()
        reader(socket).readLine()
        write(socket, "$secret\n$project\nyes\n")
        assertTrue(closedByHub(socket))
        assertEquals(0, hub.connected)
    }

    @Test
    fun `a wrong token is shut out`() {
        hub.start()
        val socket = connect(token = "not-the-token")
        assertTrue(closedByHub(socket))
        assertEquals(0, hub.connected)
        assertFalse(hub.send("x", listOf("T-1")))
    }

    @Test
    fun `the newest session gets the prompt, and the older one when the newest is gone`() {
        hub.start()
        val older = connect()
        waitFor("the first session") { hub.connected == 1 }
        val newer = connect()
        waitFor("the second session") { hub.connected == 2 }

        assertTrue(hub.send("first", listOf("T-1")))
        assertEquals("first", contentOf(newer))

        newer.close()
        waitFor("the newer session to go") { hub.connected == 1 }
        assertTrue(hub.send("second", listOf("T-1")))
        assertEquals("second", contentOf(older))
    }

    @Test
    fun `a session started without the channel flag is counted apart and gets nothing`() {
        hub.start()
        connect(flag = "no")
        waitFor("the session") { hub.withoutFlag == 1 }
        assertEquals(0, hub.connected)
        assertFalse(hub.send("x", listOf("T-1")))

        val unknown = connect(flag = "unknown")
        waitFor("the second session") { hub.connected == 1 }
        assertTrue(hub.send("y", listOf("T-1")))
        assertEquals("y", contentOf(unknown))
    }

    /** Starts the handshake as the bridge does and returns the socket and the hub's nonce, with the proof not yet sent. */
    private fun halfOpen(): Pair<Socket, String> {
        val secret = Files.readAllLines(portFile)[1]
        val socket = open()
        val nonce = "0123456789abcdef0123456789abcdef"
        write(socket, "$nonce\n")
        assertEquals(BridgeMain.proof(secret, "ide", nonce), reader(socket).readLine())
        return socket to reader(socket).readLine()
    }

    private fun assertShutOut(socket: Socket) {
        assertTrue(closedByHub(socket))
        assertEquals(0, hub.connected)
        assertFalse(hub.send("x", listOf("T-1")))
    }

    @Test
    fun `a bridge still in the handshake when the hub stops does not become a session`() {
        hub.start()
        val secret = Files.readAllLines(portFile)[1]
        val (socket, hubNonce) = halfOpen()
        hub.stop()
        runCatching { write(socket, "${BridgeMain.proof(secret, "bridge", hubNonce)}\n$project\nyes\n") }
        assertShutOut(socket)
    }

    @Test
    fun `a bridge still in the handshake when the hub stops and starts again is shut out`() {
        hub.start()
        val secret = Files.readAllLines(portFile)[1]
        val (socket, hubNonce) = halfOpen()
        hub.stop()
        hub.start()
        runCatching { write(socket, "${BridgeMain.proof(secret, "bridge", hubNonce)}\n$project\nyes\n") }
        assertShutOut(socket)
    }

    @Test
    fun `an over-long first line is shut out`() {
        hub.start()
        val socket = open()
        write(socket, "a".repeat(10_000))
        assertShutOut(socket)
    }

    @Test
    fun `a connection that drips bytes is closed at the deadline although each read was in time`() {
        hub = ChannelHub(project, cache, helloTimeoutMs = 300)
        hub.start()
        val socket = open()
        val start = System.nanoTime()
        var closed = false
        for (i in 0 until 30) {
            try {
                write(socket, "a")
            } catch (_: java.io.IOException) {
                closed = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(closed || closedByHub(socket))
        assertTrue("closed within about a second", System.nanoTime() - start < 2_000_000_000L)
        assertEquals(0, hub.connected)
    }

    @Test
    fun `a silent connection is closed after the deadline`() {
        hub = ChannelHub(project, cache, helloTimeoutMs = 300)
        hub.start()
        val socket = open()
        val start = System.nanoTime()
        assertTrue(closedByHub(socket))
        assertTrue(System.nanoTime() - start < 2_000_000_000L)
    }

    @Test
    fun `connections beyond the pending limit are closed at once, and room comes back when others end`() {
        hub = ChannelHub(project, cache, helloTimeoutMs = 60_000, maxPending = 2)
        hub.start()
        val first = open()
        val second = open()
        val third = open()
        assertTrue(closedByHub(third))
        first.close()
        second.close()
        // Room returns once the hub notices the two are gone; then a proper bridge gets in.
        waitFor("a session") {
            try {
                connect()
                hub.connected >= 1
            } catch (_: AssertionError) {
                false
            } catch (_: java.io.IOException) {
                false
            }
        }
    }

    @Test
    fun `stop closes the sessions`() {
        hub.start()
        val socket = connect()
        waitFor("the session") { hub.connected == 1 }
        hub.stop()
        assertTrue(closedByHub(socket))
        assertEquals(0, hub.connected)
    }
}
