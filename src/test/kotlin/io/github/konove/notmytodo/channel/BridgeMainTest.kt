package io.github.konove.notmytodo.channel

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.TimeUnit

class BridgeMainTest {
    private fun request(id: String, method: String, params: String = "{}") =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    @Test
    fun `field reads top-level members and nothing nested`() {
        val json = """ {"jsonrpc":"2.0", "params":{"id":99,"s":"a \" } ] b","list":[1,{"id":5}]}, "id" : 7 ,"method":"ping"} """
        assertEquals("7", BridgeMain.field(json, "id"))
        assertEquals("\"ping\"", BridgeMain.field(json, "method"))
        assertEquals("""{"id":99,"s":"a \" } ] b","list":[1,{"id":5}]}""", BridgeMain.field(json, "params"))
        assertEquals("99", BridgeMain.field(BridgeMain.field(json, "params"), "id"))
        assertNull(BridgeMain.field(json, "absent"))
        assertEquals("\"abc-1\"", BridgeMain.field("""{"id":"abc-1"}""", "id"))
        assertEquals("null", BridgeMain.field("""{"id":null}""", "id"))
    }

    @Test
    fun `field gives null for anything that is not an object`() {
        for (bad in listOf("", "   ", "not json", "[1,2]", "{", """{"id":""", """{"id" 7}""", """{"a":"unterminated}""")) {
            assertNull("for '$bad'", BridgeMain.field(bad, "id"))
        }
        assertNull(BridgeMain.field(null, "id"))
    }

    @Test
    fun `initialize declares the channel and echoes the id and the protocol version`() {
        val reply = JsonParser.parseString(BridgeMain.reply(request("1", "initialize", """{"protocolVersion":"2025-03-26","capabilities":{}}"""))).asJsonObject
        assertEquals(1, reply["id"].asInt)
        val result = reply["result"].asJsonObject
        assertEquals("2025-03-26", result["protocolVersion"].asString)
        assertTrue(result["capabilities"].asJsonObject["experimental"].asJsonObject.has("claude/channel"))
        assertEquals("notmytodo", result["serverInfo"].asJsonObject["name"].asString)
        assertTrue(result["instructions"].asString.contains("TODO"))

        val text = JsonParser.parseString(BridgeMain.reply(request("\"init-1\"", "initialize"))).asJsonObject
        assertEquals("init-1", text["id"].asString)
        assertTrue(text["result"].asJsonObject["protocolVersion"].asString.isNotEmpty())
    }

    @Test
    fun `ping and the list methods get empty results`() {
        assertEquals("""{"jsonrpc":"2.0","id":2,"result":{}}""", BridgeMain.reply(request("2", "ping")))
        assertEquals("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}""", BridgeMain.reply(request("3", "tools/list")))
        assertEquals("""{"jsonrpc":"2.0","id":4,"result":{"prompts":[]}}""", BridgeMain.reply(request("4", "prompts/list")))
        assertEquals("""{"jsonrpc":"2.0","id":5,"result":{"resources":[]}}""", BridgeMain.reply(request("5", "resources/list")))
    }

    @Test
    fun `an unknown request gets method not found`() {
        val reply = JsonParser.parseString(BridgeMain.reply(request("6", "tools/call"))).asJsonObject
        assertEquals(6, reply["id"].asInt)
        assertEquals(-32601, reply["error"].asJsonObject["code"].asInt)
    }

    @Test
    fun `notifications, responses and rubbish get no reply`() {
        assertNull(BridgeMain.reply("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(BridgeMain.reply("""{"jsonrpc":"2.0","id":9,"result":{}}"""))
        assertNull(BridgeMain.reply("rubbish"))
        assertNull(BridgeMain.reply(""))
        // The request id is the top-level one, never one inside params.
        assertNull(BridgeMain.reply("""{"jsonrpc":"2.0","method":"notifications/x","params":{"id":3}}"""))
    }

    @Test
    fun `the key is a stable hash of the path`() {
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", BridgeMain.key("test"))
        assertTrue(BridgeMain.key("/a") != BridgeMain.key("/a/"))
    }

    @Test
    fun `the nearest project above the working directory is found`() {
        val root = Files.createTempDirectory("nmt-find").toRealPath()
        val cache = Files.createDirectories(root.resolve("cache"))
        val outer = Files.createDirectories(root.resolve("outer"))
        val inner = Files.createDirectories(outer.resolve("inner"))
        val deep = Files.createDirectories(inner.resolve("src/deep"))
        assertNull(BridgeMain.find(cache, deep))
        val outerFile = Files.writeString(cache.resolve(BridgeMain.key(outer.toString())), "1\na\n")
        assertEquals(outerFile, BridgeMain.find(cache, deep))
        val innerFile = Files.writeString(cache.resolve(BridgeMain.key(inner.toString())), "2\nb\n")
        assertEquals(innerFile, BridgeMain.find(cache, deep))
        assertEquals(innerFile, BridgeMain.find(cache, inner))
        assertEquals(outerFile, BridgeMain.find(cache, outer))
        assertNull(BridgeMain.find(cache, root))
    }

    @Test
    fun `the flag is yes when any parent has it, unknown when one cannot be read, else no`() {
        val with = Optional.of("claude --dangerously-load-development-channels server:notmytodo")
        val without = Optional.of("claude --model opus")
        val hidden = Optional.empty<String>()
        assertEquals("yes", BridgeMain.flag(listOf(with)))
        assertEquals("yes", BridgeMain.flag(listOf(Optional.of("/bin/sh bridge"), with, hidden)))
        assertEquals("no", BridgeMain.flag(listOf(without, Optional.of("zsh"))))
        assertEquals("unknown", BridgeMain.flag(listOf(without, hidden)))
        assertEquals("unknown", BridgeMain.flag(emptyList()))
        assertEquals("no", BridgeMain.flag(listOf(Optional.of("claude --channels server:other"))))
    }

    @Test(timeout = 60_000)
    fun `the process shakes hands alone, reaches the nearest project, relays its lines and reconnects`() {
        val root = Files.createTempDirectory("nmt-bridge").toRealPath()
        val cache = Files.createDirectories(root.resolve("cache"))
        val outer = Files.createDirectories(root.resolve("outer"))
        val inner = Files.createDirectories(outer.resolve("inner"))
        val cwd = Files.createDirectories(inner.resolve("src/deep"))
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { wrong ->
            ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { ide ->
                ide.soTimeout = 20_000
                Files.writeString(cache.resolve(BridgeMain.key(outer.toString())), "${wrong.localPort}\nouter-token\n")
                Files.writeString(cache.resolve(BridgeMain.key(inner.toString())), "${ide.localPort}\nsecret\n")
                val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
                val classes = PathManager.getJarPathForClass(BridgeMain::class.java)!!
                val process = ProcessBuilder(java, "-Dnotmytodo.retryMs=200", "-cp", classes, BridgeMain::class.java.name, cache.toString())
                    .directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                try {
                    val toBridge = process.outputStream.bufferedWriter()
                    val fromBridge = process.inputStream.bufferedReader()
                    toBridge.write(request("1", "initialize", """{"protocolVersion":"2025-06-18"}""") + "\n")
                    toBridge.flush()
                    assertTrue(fromBridge.readLine().contains("\"claude/channel\""))

                    val hex = Regex("[0-9a-f]{32}")
                    val seen = StringBuilder()
                    val firstNonce = ide.accept().use { socket ->
                        val hello = socket.getInputStream().bufferedReader()
                        val nonceB = hello.readLine()
                        assertTrue(hex.matches(nonceB))
                        seen.append(nonceB)
                        socket.getOutputStream().apply {
                            write((BridgeMain.proof("secret", "ide", nonceB) + "\nhostnonce1\n").toByteArray())
                            flush()
                        }
                        val proof = hello.readLine()
                        val dir = hello.readLine()
                        val flag = hello.readLine()
                        seen.append(proof).append(dir).append(flag)
                        assertEquals(BridgeMain.proof("secret", "bridge", "hostnonce1"), proof)
                        assertEquals(cwd.toString(), dir)
                        assertTrue(flag in setOf("yes", "no", "unknown"))
                        assertTrue("the token never crosses the socket", !seen.contains("secret"))
                        socket.getOutputStream().apply {
                            write("{\"relayed\":\"naïve 日本語\"}\n".toByteArray())
                            flush()
                        }
                        assertEquals("{\"relayed\":\"naïve 日本語\"}", fromBridge.readLine())
                        nonceB
                    }
                    // The IDE went away; the bridge comes back by itself.
                    ide.accept().use { socket ->
                        val second = socket.getInputStream().bufferedReader().readLine()
                        assertTrue(hex.matches(second))
                        assertTrue("a fresh nonce for every connection", second != firstNonce)
                    }
                    toBridge.close()
                    assertTrue("the bridge exits when Claude Code closes its input", process.waitFor(10, TimeUnit.SECONDS))
                } finally {
                    process.destroyForcibly()
                }
            }
        }
    }

    @Test
    fun `proof is an HMAC of the role and the nonce under the token`() {
        assertEquals("9b921f75a88493fefd706f5270137b7e9d1f33f0e7e00807b1f28ca243bf0c1a", BridgeMain.proof("secret", "ide", "abc"))
        assertEquals("e411634be670064701c2f1f7b3a1806e275fa191c1a610a48f48e8362651a37b", BridgeMain.proof("secret", "bridge", "abc"))
        assertTrue(BridgeMain.proof("secret", "ide", "abc") != BridgeMain.proof("other", "ide", "abc"))
    }

    @Test(timeout = 60_000)
    fun `a listener that cannot prove it holds the token gets nothing and is never relayed`() {
        val root = Files.createTempDirectory("nmt-impostor").toRealPath()
        val cache = Files.createDirectories(root.resolve("cache"))
        val cwd = Files.createDirectories(root.resolve("proj"))
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { fake ->
            fake.soTimeout = 20_000
            Files.writeString(cache.resolve(BridgeMain.key(cwd.toString())), "${fake.localPort}\nsecret\n")
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classes = PathManager.getJarPathForClass(BridgeMain::class.java)!!
            val process = ProcessBuilder(java, "-Dnotmytodo.retryMs=200", "-cp", classes, BridgeMain::class.java.name, cache.toString())
                .directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try {
                val toBridge = process.outputStream.bufferedWriter()
                val fromBridge = process.inputStream.bufferedReader()
                toBridge.write(request("1", "initialize") + "\n")
                toBridge.flush()
                assertTrue(fromBridge.readLine().contains("\"claude/channel\""))

                fake.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    val nonceB = reader.readLine()
                    socket.getOutputStream().apply {
                        write((BridgeMain.proof("not-the-token", "ide", nonceB) + "\nhostnonce\n{\"injected\":true}\n").toByteArray())
                        flush()
                    }
                    val further = try {
                        reader.readLine()
                    } catch (e: java.net.SocketException) {
                        null
                    }
                    assertNull("the bridge sends nothing more to an impostor", further)
                }
                toBridge.write(request("2", "ping") + "\n")
                toBridge.flush()
                assertEquals("""{"jsonrpc":"2.0","id":2,"result":{}}""", fromBridge.readLine())
            } finally {
                process.destroyForcibly()
            }
        }
    }
}
