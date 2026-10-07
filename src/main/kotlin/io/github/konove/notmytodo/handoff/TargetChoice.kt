package io.github.konove.notmytodo.handoff

import io.github.konove.notmytodo.settings.FixTarget

/** Decides where a prompt goes when the wanted target may not be there. */
object TargetChoice {
    data class Availability(val channelOn: Boolean, val sessionConnected: Boolean, val terminal: Boolean)

    /** [reason] is why the wanted target was passed over; null when it was used. */
    data class Choice(val target: FixTarget, val reason: String?)

    /** Why [target] cannot be used now, or null when it can. */
    fun reason(target: FixTarget, a: Availability): String? = when (target) {
        FixTarget.SESSION -> when {
            !a.channelOn -> "Sending to a running session is switched off in the settings."
            !a.sessionConnected -> "No Claude Code session is connected to this project."
            else -> null
        }
        FixTarget.TERMINAL -> if (a.terminal) null else "The Terminal plugin is not available."
        FixTarget.CLIPBOARD -> null
    }

    /** [wanted] if it can be used, or else the next one after it that can. The clipboard always can. */
    fun choose(wanted: FixTarget, a: Availability): Choice {
        val target = FixTarget.entries.dropWhile { it != wanted }.first { reason(it, a) == null }
        return Choice(target, if (target == wanted) null else reason(wanted, a))
    }
}
