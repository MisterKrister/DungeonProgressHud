package dev.krister.dungeonprogresshud

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class RunStateMigrationTest {
    @Test
    fun `old runs json keeps historical profit and gains safe defaults`() {
        val oldJson = """
            {
              "chestProfits": [
                {"timestamp": 123, "chestName": "Bedrock", "profit": 987654, "profileName": "Apple", "floorLabel": "M7"}
              ],
              "totalChestProfit": 0,
              "totalChestsOpened": 0
            }
        """.trimIndent()
        val state = Gson().fromJson(oldJson, RunState::class.java)

        RunStateMigration.migrate(state)

        assertEquals(987654, state.chestProfits.single().profit)
        assertEquals(987654, state.totalChestProfit)
        assertEquals(1, state.totalChestsOpened)
        assertFalse(state.chestProfits.single().pricingComplete)
        assertTrue(state.chestProfits.single().pricedItems.isEmpty())
    }

    @Test
    fun `truncated retained samples do not overwrite larger lifetime totals`() {
        val state = RunState(
            chestProfits = mutableListOf(ChestProfitSample(profit = 10)),
            totalChestProfit = 1_000,
            totalChestsOpened = 100,
        )

        RunStateMigration.migrate(state)

        assertEquals(1_000, state.totalChestProfit)
        assertEquals(100, state.totalChestsOpened)
    }
    @Test fun `null historical arrays fail validation before any automatic write`() {
        val state = Gson().fromJson("{\"runs\":null}", RunState::class.java)
        assertFailsWith<IllegalArgumentException> { HistoryValidation.validate(state) }
    }

}
