package com.cystem.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTitlesTest {
    @Test
    fun collapsesWhitespace() {
        assertEquals(
            "build my app",
            ConversationTitles.fromFirstMessage("  build   my   app "),
        )
    }

    @Test
    fun emptyTextGetsSafeTitle() {
        assertEquals(
            "New system session",
            ConversationTitles.fromFirstMessage("   "),
        )
    }
}
