package io.github.konove.notmytodo.handoff

/** Puts ready-made phrases into the "Done when" text and takes them out again, joined by "and". */
object DonePhrases {
    private val joinBefore = Regex("""[\s,]*(\band)?[\s,]*$""")
    private val joinAfter = Regex("""^[\s,]*(and\b)?[\s,]*""")

    fun has(text: String, phrase: String): Boolean = text.contains(phrase)

    /** [text] with [phrase] added at the end, or taken out if it is there. What else was typed stays. */
    fun toggle(text: String, phrase: String): String {
        val at = text.indexOf(phrase)
        if (at < 0) return if (text.isBlank()) phrase else "${text.trim()} and $phrase"
        val before = text.substring(0, at).replace(joinBefore, "")
        val after = text.substring(at + phrase.length).replace(joinAfter, "")
        return if (before.isEmpty() || after.isEmpty()) before + after else "$before and $after"
    }
}
