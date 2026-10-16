package io.github.konove.notmytodo.ide

import io.github.konove.notmytodo.channel.ChannelRegistration

/** Whether Claude Code has the notmytodo-store server, which gives its sessions the todo tools. */
class StoreRegistration(
    private val run: (List<String>) -> ChannelRegistration.Outcome? = { ChannelRegistration.runClaude(it) },
) {
    /**
     * Asks Claude Code. Takes a moment; do not call on the event thread. A claude that did not answer
     * or could not be started (exit -1) has said nothing about the server: that is NO_CLAUDE too, so
     * that no balloon tells the user to install a server they may have.
     */
    fun status(): ChannelRegistration.Status {
        val outcome = run(listOf("mcp", "get", NAME)) ?: return ChannelRegistration.Status.NO_CLAUDE
        return when (outcome.exit) {
            0 -> ChannelRegistration.Status.REGISTERED
            -1 -> ChannelRegistration.Status.NO_CLAUDE
            else -> ChannelRegistration.Status.NOT_REGISTERED
        }
    }

    companion object {
        /** The name the server of not-my-todo-store is registered under. */
        const val NAME = "notmytodo-store"
    }
}
