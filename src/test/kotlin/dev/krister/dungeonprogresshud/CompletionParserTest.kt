package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionParserTest {
    @Test fun `master floor and completion metadata survive a realistic sequence`() {
        var state = CompletionState()
        for (line in listOf("Master Mode - 7", "Defeated Necron in 5m 12s", "Score: 305 (S+)")) {
            val result = CompletionParser.accept(state, line, 100_000)
            assertNull(result.completion)
            state = result.state
        }
        val run = CompletionParser.accept(state, "+500,000 Cata EXP", 101_000).completion!!
        assertEquals("M7", run.metadata.floor)
        assertEquals(312, run.metadata.seconds)
        assertEquals(305, run.metadata.score)
        assertEquals(500_000, run.xp)
        assertEquals("", CompletionParser.accept(state, "+500,000 Cata EXP", 200_000).completion!!.metadata.floor)
    }

    @Test fun `two completions consume one refresh interval without inventing a third run`() {
        val interval = XpInterval(100, 1000, 1_000_000, "account", "profile")
        assertEquals(0, XpReconciliation.unattributed(interval, listOf(200L to 500_000L, 800L to 500_000L)))
        assertEquals(500_000, XpReconciliation.unattributed(interval, listOf(200L to 500_000L)))
    }
}
