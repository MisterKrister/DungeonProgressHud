package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryQueriesTest {
    @Test fun `total restores the saved lifetime remainder without adding it to sessions`() {
        val state = RunState(totalChestProfit = 5_616_579_005L, totalChestsOpened = 922,
            chestProfits = MutableList(250) { ChestProfitSample(timestamp = 1, profit = 2_000_000) }
                .apply { add(ChestProfitSample(timestamp = 2, profit = 18_595_581)) })
        val total = HistoryQueries.profitStats(state, "a", "p", "total", true) { true }
        assertEquals(5_616_579_005L, total.profit)
        assertEquals(922, total.chests)
        assertEquals(5_616_579_005L / 922, total.average)
        assertEquals(18_595_581, HistoryQueries.profitStats(state, "a", "p", "session", false) { it >= 2 }.profit)
        state.chestProfits.add(ChestProfitSample(accountId = "other", profileId = "p", profit = 10))
        state.totalChestsOpened++
        state.totalChestProfit += 10
        assertEquals(total, HistoryQueries.profitStats(state, "a", "p", "total", true) { true })
    }

    @Test fun `truncated losses and complete history are not inflated`() {
        val state = RunState(totalChestProfit = -100, totalChestsOpened = 2,
            chestProfits = mutableListOf(ChestProfitSample(profit = 10)))
        assertEquals(-100, HistoryQueries.profitStats(state, "a", "p", "total", true) { true }.profit)
        state.totalChestsOpened = 1
        assertEquals(10, HistoryQueries.profitStats(state, "a", "p", "total", true) { true }.profit)
    }

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
