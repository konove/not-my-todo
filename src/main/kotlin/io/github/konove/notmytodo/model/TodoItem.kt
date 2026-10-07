package io.github.konove.notmytodo.model

enum class Priority {
    P1, P2, P3;

    val json: String get() = name.lowercase()

    companion object {
        fun fromJson(s: String): Priority? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

enum class Status(val label: String) {
    OPEN("Open"), IN_PROGRESS("In progress"), FIXED("Fixed, review"), DONE("Done"), WONT_FIX("Won't fix");

    val json: String get() = name.lowercase()

    override fun toString(): String = label

    companion object {
        fun fromJson(s: String): Status? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

enum class Author {
    USER, AGENT;

    val json: String get() = name.lowercase()

    companion object {
        fun fromJson(s: String): Author? = entries.firstOrNull { it.json == s.trim().lowercase() }
    }
}

/** A place in a file. Lines are 1-based and inclusive; [path] is project-relative with forward slashes. */
data class Anchor(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val before: List<String>,
    val after: List<String>,
    val lost: Boolean = false,
)

data class TodoItem(
    val id: String,
    val title: String,
    val details: String = "",
    val priority: Priority = Priority.P2,
    val tags: List<String> = emptyList(),
    val status: Status = Status.OPEN,
    val author: Author = Author.USER,
    val created: String = "",
    val updated: String = "",
    val anchor: Anchor? = null,
) {
    val number: Int get() = ItemId.number(id)
    val isClosed: Boolean get() = status == Status.DONE || status == Status.WONT_FIX

    /** Marked with the [Tags.NEEDS_DECISION] tag: an agent must not fix this until I have decided something. */
    val needsDecision: Boolean get() = Tags.NEEDS_DECISION in tags
}

data class TodoFile(
    val version: Int = 1,
    val nextId: Int = 1,
    val items: List<TodoItem> = emptyList(),
)

object ItemId {
    private val pattern = Regex("T-([1-9][0-9]*)")

    fun of(n: Int): String = "T-$n"

    fun parse(id: String): Int? = pattern.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()

    fun number(id: String): Int = parse(id) ?: throw IllegalArgumentException("not an item id: $id")
}

object Tags {
    const val NEEDS_DECISION = "needs-decision"

    fun normalize(raw: String): String? =
        raw.trim().removePrefix("#").lowercase().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    fun normalizeAll(raw: List<String>): List<String> = raw.mapNotNull(::normalize).distinct()

    fun parseList(text: String): List<String> = normalizeAll(text.split(Regex("[\\s,]+")))
}
