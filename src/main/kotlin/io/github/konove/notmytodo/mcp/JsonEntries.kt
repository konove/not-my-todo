package io.github.konove.notmytodo.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tool parameter that is a JSON array of objects, kept as its JSON [text]. The tool's schema says
 * array of objects, so an agent passes the array as it is and escapes nothing. A text that holds the
 * same JSON, which is how these parameters were passed before, is read as well.
 */
class JsonEntries(val text: String) {
    companion object {
        /** The MCP server asks kotlinx.serialization for the serializer of a parameter's type, which looks for this function. */
        @Suppress("unused")
        fun serializer(): KSerializer<JsonEntries> = JsonEntriesSerializer
    }
}

object JsonEntriesSerializer : KSerializer<JsonEntries> {
    /** What the schema is made from. The objects are left open: the tool descriptions say what they take. */
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = listSerialDescriptor(buildClassSerialDescriptor("io.github.konove.notmytodo.mcp.JsonEntry"))

    override fun deserialize(decoder: Decoder): JsonEntries {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return JsonEntries(if (element is JsonPrimitive && element.isString) element.content else element.toString())
    }

    override fun serialize(encoder: Encoder, value: JsonEntries) = throw UnsupportedOperationException("a tool parameter is only read")
}
