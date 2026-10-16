package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.channel.ChannelRegistration
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreRegistrationTest {
    @Test
    fun `a server Claude Code knows is registered`() {
        val asked = ArrayList<List<String>>()
        val registration = StoreRegistration {
            asked += it
            ChannelRegistration.Outcome(0, "notmytodo-store:\n  Status: connected")
        }
        assertEquals(ChannelRegistration.Status.REGISTERED, registration.status())
        assertEquals(listOf(listOf("mcp", "get", "notmytodo-store")), asked)
    }

    @Test
    fun `a server Claude Code does not know is not, and no Claude Code is said apart`() {
        assertEquals(
            ChannelRegistration.Status.NOT_REGISTERED,
            StoreRegistration { ChannelRegistration.Outcome(1, "No MCP server found with name: notmytodo-store") }.status(),
        )
        assertEquals(ChannelRegistration.Status.NO_CLAUDE, StoreRegistration { null }.status())
    }

    @Test
    fun `a claude that hangs or cannot be started is not taken to lack the server`() {
        assertEquals(
            ChannelRegistration.Status.NO_CLAUDE,
            StoreRegistration { ChannelRegistration.Outcome(-1, "claude did not answer in 10 seconds.") }.status(),
        )
    }
}
