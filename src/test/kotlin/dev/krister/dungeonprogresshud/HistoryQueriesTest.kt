package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryQueriesTest {
    @Test fun `legacy chest profit is visible without assigning ownership or mixing known profiles`() {
        val legacy = ChestProfitSample(timestamp = 10, profit = 61069)
        val state = RunState(chestProfits = mutableListOf(legacy,
            ChestProfitSample(accountId = "a", profileId = "apple", profit = 100),
            ChestProfitSample(accountId = "a", profileId = "pear", profit = 200),
            ChestProfitSample(accountId = "b", profileId = "apple", profit = 300)))
        assertEquals(61169, HistoryQueries.chests(state, "a", "apple").sumOf { it.profit })
        assertEquals(61269, HistoryQueries.chests(state, "a", "pear").sumOf { it.profit })
        assertEquals("", legacy.accountId)
        assertEquals("", legacy.profileId)
        assertEquals(1, HistoryQueries.chests(state, "a", "apple").count { it.timestamp >= 10 })
    }

    @Test fun `same account profiles and unknown legacy ownership remain separate`() {
        val state = RunState(runs = mutableListOf(
            DungeonRunRecord(accountId = "a", profileId = "apple", rawCataXp = 100),
            DungeonRunRecord(accountId = "a", profileId = "pear", rawCataXp = 200),
            DungeonRunRecord(accountId = "b", profileId = "apple", rawCataXp = 300),
            DungeonRunRecord(rawCataXp = 400),
        ))
        assertEquals(100, HistoryQueries.runs(state, "a", "apple").single().rawCataXp)
        assertEquals(200, HistoryQueries.runs(state, "a", "pear").single().rawCataXp)
        assertTrue(HistoryQueries.runs(state, "", "").isEmpty())
        assertEquals(4, state.runs.size)
    }
}
