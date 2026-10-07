package io.github.konove.notmytodo.channel

import com.google.gson.JsonObject

/** Builds what the IDE sends to Claude Code through the bridge. One message is one line. */
object ChannelMessage {
    const val METHOD = "notifications/claude/channel"

    // Claude Code silently drops meta keys with any other character.
    private val KEY = Regex("[A-Za-z0-9_]+")

    /** The notification that puts [prompt] in front of Claude, as one line of JSON with no line end. */
    fun line(prompt: String, meta: Map<String, String>): String {
        val params = JsonObject()
        params.addProperty("content", prompt)
        params.add("meta", JsonObject().apply { meta.filterKeys(KEY::matches).forEach { (key, value) -> addProperty(key, value) } })
        val message = JsonObject()
        message.addProperty("jsonrpc", "2.0")
        message.addProperty("method", METHOD)
        message.add("params", params)
        return message.toString()
    }
}
