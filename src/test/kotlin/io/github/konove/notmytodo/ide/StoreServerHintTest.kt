package io.github.konove.notmytodo.ide

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.channel.ChannelRegistration
import io.github.konove.notmytodo.settings.TodoSettings

class StoreServerHintTest : BasePlatformTestCase() {
    private val shown = ArrayList<Notification>()
    private val missing = StoreRegistration { ChannelRegistration.Outcome(1, "No MCP server found with name: notmytodo-store") }

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown.add(notification)
            }
        })
    }

    override fun tearDown() {
        try {
            TodoSettings.getInstance().update(TodoSettings.Values())
        } finally {
            super.tearDown()
        }
    }

    fun `test a missing server is said once a session, with a way to stop saying it`() {
        val hint = StoreServerHint()
        hint.check(project, missing)
        hint.check(project, missing)
        assertEquals(listOf(StoreServerHint.TITLE), shown.map { it.title })
        assertEquals(listOf("Don't Show Again"), shown.single().actions.map { it.templateText })
        assertFalse(hint.registered)
    }

    fun `test a registered server is said nothing about, and lets prompts send the agent to the tools`() {
        val hint = StoreServerHint()
        hint.check(project, StoreRegistration { ChannelRegistration.Outcome(0, "") })
        assertTrue(shown.isEmpty())
        assertTrue(hint.registered)
    }

    fun `test the hint is not shown once dismissed`() {
        TodoSettings.getInstance().update(TodoSettings.getInstance().values.apply { storeHintDismissed = true })
        StoreServerHint().check(project, missing)
        assertTrue(shown.isEmpty())
    }

    fun `test while not registered it asks again after five minutes, and the balloon still shows once`() {
        var now = 0L
        var asks = 0
        val hint = StoreServerHint().apply { clock = { now } }
        val counting = StoreRegistration { asks++; ChannelRegistration.Outcome(1, "No MCP server found with name: notmytodo-store") }
        hint.check(project, counting)
        now += 60_000
        hint.check(project, counting)
        assertEquals(1, asks)
        now += StoreServerHint.INTERVAL_MS
        hint.check(project, counting)
        assertEquals(2, asks)
        assertEquals(1, shown.size)
    }

    fun `test a server installed meanwhile is found when it asks again`() {
        var now = 0L
        val hint = StoreServerHint().apply { clock = { now } }
        hint.check(project, missing)
        now += StoreServerHint.INTERVAL_MS
        hint.check(project, StoreRegistration { ChannelRegistration.Outcome(0, "") })
        assertTrue(hint.registered)
    }

    fun `test once registered it does not ask again`() {
        var asks = 0
        val hint = StoreServerHint()
        val ok = StoreRegistration { asks++; ChannelRegistration.Outcome(0, "") }
        hint.check(project, ok)
        hint.clock = { Long.MAX_VALUE / 2 }
        hint.check(project, ok)
        assertEquals(1, asks)
    }
}
