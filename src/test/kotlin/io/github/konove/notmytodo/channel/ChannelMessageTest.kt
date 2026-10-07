package io.github.konove.notmytodo.channel

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChannelMessageTest {
    @Test
    fun `a message is a JSON-RPC notification with the prompt and the meta`() {
        val json = JsonParser.parseString(ChannelMessage.line("Fix T-12", mapOf("item_id" to "T-12"))).asJsonObject
        assertEquals("2.0", json["jsonrpc"].asString)
        assertEquals("notifications/claude/channel", json["method"].asString)
        assertFalse(json.has("id"))
        val params = json["params"].asJsonObject
        assertEquals("Fix T-12", params["content"].asString)
        assertEquals("T-12", params["meta"].asJsonObject["item_id"].asString)
    }

    @Test
    fun `a prompt with quotes, newlines, backslashes and other scripts stays one line and arrives unchanged`() {
        val prompt = "Fix \"this\"\nand\r\n\tthat \\ path C:\\dir\n```\n> 12  naïve — 日本語 \u2028 end\n```\n" + "x".repeat(200_000)
        val line = ChannelMessage.line(prompt, mapOf("item_ids" to "T-1,T-2"))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertFalse(line.contains('\u2028'))
        val params = JsonParser.parseString(line).asJsonObject["params"].asJsonObject
        assertEquals(prompt, params["content"].asString)
    }

    @Test
    fun `meta keys Claude Code would drop are left out`() {
        val line = ChannelMessage.line("x", mapOf("item_id" to "T-1", "item-id" to "bad", "" to "bad", "has space" to "bad"))
        val meta = JsonParser.parseString(line).asJsonObject["params"].asJsonObject["meta"].asJsonObject
        assertEquals(setOf("item_id"), meta.keySet())
    }

    @Test
    fun `no meta gives an empty object`() {
        val meta = JsonParser.parseString(ChannelMessage.line("x", emptyMap())).asJsonObject["params"].asJsonObject["meta"]
        assertEquals(0, meta.asJsonObject.size())
    }
}
