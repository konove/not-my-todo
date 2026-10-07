package io.github.konove.notmytodo

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixtureSmokeTest : BasePlatformTestCase() {
    fun `test fixture opens a text file`() {
        val file = myFixture.configureByText("a.txt", "one\ntwo\n")
        assertEquals(2, myFixture.editor.document.getLineNumber(4) + 1)
        assertTrue(file.virtualFile.isValid)
    }
}
