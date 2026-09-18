package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionParserTest {
    @Test fun `fractional screenshot XP records a run in live chat and archived chat`() {
        val lines = listOf("§cThe Catacombs §r- §eF7",
            "§aDefeated §cMaxor, Storm, Goldor, and Necron §ain §e04m 54s",
            "§aScore: §6298 §a(§bS§a)    §b+52 Bits",
            "§3+64,832.9 Cata EXP  §3+53,735.4 Mage EXP")
        for (messages in listOf(lines, listOf(lines.joinToString("\\n") + " §8(§7x§r2§8)"))) {
            var state = CompletionState()
            val completions = messages.mapNotNull { line ->
                val result = CompletionParser.accept(state, line, 100_000)
                state = result.state
                result.completion
            }
            val run = completions.single()
            assertEquals("F7", run.metadata.floor)
            assertEquals(294, run.metadata.seconds)
            assertEquals(298, run.metadata.score)
            assertEquals("S", run.metadata.grade)
            assertEquals(64_833, run.xp)
            val history = RunState(runs = mutableListOf(DungeonRunRecord(
                timestamp = run.timestamp, floorLabel = run.metadata.floor,
                rawCataXp = run.xp, accountId = "account", profileId = "profile")))
            val scopedRuns = HistoryQueries.runs(history, "account", "profile").filter { it.timestamp >= 90_000 }
            val xp = HistoryQueries.observedXp(scopedRuns, "F7", 0)
            assertEquals(1, scopedRuns.size)
            assertEquals(64_833, xp.last().second)
            assertEquals(64_833.0, xp.map { it.second }.average())
            assertEquals(2714, kotlin.math.ceil(175_900_000 / xp.map { it.second }.average()).toInt())
        }
    }

    @Test fun `screenshot F7 completion supplies the floor XP average and remaining runs`() {
        var state = CompletionState()
        var completion: ParsedCompletion? = null
        for (line in listOf("§cThe Catacombs §r- §eF7", "Defeated Maxor, Storm, Goldor, and Necron in 05m 38s",
            "Score: 302 (S+)    +52 Bits", "+66,295 Cata EXP  +55,245.9 Mage EXP")) {
            val result = CompletionParser.accept(state, line, 100_000)
            state = result.state
            completion = result.completion ?: completion
        }
        val run = completion!!
        assertEquals("F7", run.metadata.floor)
        assertEquals(338, run.metadata.seconds)
        val runs = listOf(DungeonRunRecord(floorLabel = "F5", rawCataXp = 201_000),
            DungeonRunRecord(timestamp = 100_000, floorLabel = run.metadata.floor, rawCataXp = run.xp))
        val samples = HistoryQueries.observedXp(runs, run.metadata.floor, 0)
        assertEquals(66_295, samples.single().second)
        assertEquals(2655, kotlin.math.ceil(175_970_000 / samples.map { it.second }.average()).toInt())
        assertEquals(emptyList(), HistoryQueries.observedXp(runs, "M7", 0))
        assertEquals(emptyList(), HistoryQueries.observedXp(runs, "F7", 100_001))
    }

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
