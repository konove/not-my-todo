package io.github.konove.notmytodo.handoff

import org.junit.Assert.assertEquals
import org.junit.Test

class DonePhrasesTest {
    @Test
    fun `a phrase is added with and, after what is there`() {
        assertEquals("the tests pass", DonePhrases.toggle("", "the tests pass"))
        assertEquals("it is fast and the tests pass", DonePhrases.toggle(" it is fast ", "the tests pass"))
    }

    @Test
    fun `a phrase is taken out with its joining word, wherever it stands`() {
        assertEquals("b and c", DonePhrases.toggle("a and b and c", "a"))
        assertEquals("a and c", DonePhrases.toggle("a and b and c", "b"))
        assertEquals("a and b", DonePhrases.toggle("a and b and c", "c"))
        assertEquals("a and c", DonePhrases.toggle("a, b, c", "b"))
        assertEquals("", DonePhrases.toggle("the tests pass", "the tests pass"))
    }

    @Test
    fun `a phrase that has and in it is still one phrase`() {
        val phrase = "it builds and runs"
        val text = DonePhrases.toggle("the tests pass", phrase)
        assertEquals("the tests pass and it builds and runs", text)
        assertEquals("the tests pass", DonePhrases.toggle(text, phrase))
    }
}
