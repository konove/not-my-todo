package io.github.konove.notmytodo.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class FixCommitsTest {
    private fun log(vararg commits: Pair<String, String>) = commits.joinToString("") { (hash, message) -> "$hash\u001f$message\n\u001e\n" }

    @Test
    fun `the newest commit that names an item is the one that fixed it`() {
        val found = FixCommits.parse(
            log(
                "ccc3333" to "Fix T-12: cache the path",
                "bbb2222" to "Tidy up\n\nAlso closes T-7 and T-12.",
                "aaa1111" to "Start on T-7",
            )
        )
        assertEquals(mapOf("T-12" to "ccc3333", "T-7" to "bbb2222"), found)
    }

    @Test
    fun `only whole item ids count`() {
        val found = FixCommits.parse(log("aaa1111" to "Bump LIST-12, XT-3 and T-0; see T-45x, t-9 and (T-8)."))
        assertEquals(mapOf("T-8" to "aaa1111"), found)
        assertEquals(emptyMap<String, String>(), FixCommits.parse(""))
    }
}
