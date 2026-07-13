package dev.krister.dungeonprogresshud

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val state = Gson().fromJson(oldJson, DungeonProgressHudFeature.RunState::class.java)

        RunStateMigration.migrate(state)

        assertEquals(987654, state.chestProfits.single().profit)
        assertEquals(987654, state.totalChestProfit)
        assertEquals(1, state.totalChestsOpened)
        assertTrue(state.chestProfits.single().pricingComplete)
        assertTrue(state.chestProfits.single().pricedItems.isEmpty())
    }

    @Test
    fun `truncated retained samples do not overwrite larger lifetime totals`() {
        val state = DungeonProgressHudFeature.RunState(
            chestProfits = mutableListOf(DungeonProgressHudFeature.ChestProfitSample(profit = 10)),
            totalChestProfit = 1_000,
            totalChestsOpened = 100,
        )

        RunStateMigration.migrate(state)

        assertEquals(1_000, state.totalChestProfit)
        assertEquals(100, state.totalChestsOpened)
    }
}
