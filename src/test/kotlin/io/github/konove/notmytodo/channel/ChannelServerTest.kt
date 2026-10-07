package io.github.konove.notmytodo.channel

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.settings.TodoSettings

class ChannelServerTest : BasePlatformTestCase() {
    private val server get() = project.service<ChannelServer>()

    override fun tearDown() {
        try {
            TodoSettings.getInstance().update(TodoSettings.Values())
        } finally {
            super.tearDown()
        }
    }

    fun `test with the channel off nothing listens and nothing is sent`() {
        server.sync()
        assertFalse(server.running)
        assertEquals(0, server.connected)
        assertEquals(0, server.withoutFlag)
        assertNull(server.error)
        assertFalse(server.send("x", listOf("T-1")))
    }

    fun `test the option switches listening on and off`() {
        // The service hears about settings only once it exists.
        val server = server
        TodoSettings.getInstance().update(TodoSettings.Values(channelEnabled = true))
        // A test project may have no directory on disk; then the reason is reported instead.
        assertTrue(server.running || server.error != null)
        assertFalse(server.send("nobody is connected", listOf("T-1")))

        TodoSettings.getInstance().update(TodoSettings.Values(channelEnabled = false))
        assertFalse(server.running)
        assertNull(server.error)
    }
}
