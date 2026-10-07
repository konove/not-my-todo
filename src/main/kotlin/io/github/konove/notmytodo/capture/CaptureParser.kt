package io.github.konove.notmytodo.capture

import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Tags

data class Captured(val title: String, val priority: Priority?, val tags: List<String>)

/** Parses one typed line: `!p1`..`!p3` set the priority, `#word` adds a tag, the rest is the title. */
object CaptureParser {
    private val priorityToken = Regex("![pP][123]")
    private val tagToken = Regex("#[\\p{L}\\p{N}_-]+")

    fun parse(input: String): Captured? {
        var priority: Priority? = null
        val tags = ArrayList<String>()
        val title = ArrayList<String>()
        for (word in input.trim().split(Regex("\\s+"))) {
            when {
                word.isEmpty() -> {}
                priorityToken.matches(word) -> priority = Priority.fromJson(word.drop(1))
                tagToken.matches(word) -> tags += word
                else -> title += word
            }
        }
        if (title.isEmpty()) return null
        return Captured(title.joinToString(" "), priority, Tags.normalizeAll(tags))
    }
}
