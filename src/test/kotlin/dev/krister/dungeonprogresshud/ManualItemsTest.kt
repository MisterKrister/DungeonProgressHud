package dev.krister.dungeonprogresshud

import com.google.gson.Gson
import kotlin.test.*

class ManualItemsTest {
    private val context = ClaimContext("", "account", "profile", "Apple", "M7")

    @Test fun `all tracked items subtract their M7 cost and retain counts and ownership after reload`() {
        val profitsAt160Million = mapOf(
            "handle" to 60_000_000L, "implosion" to 110_000_000L, "wither_shield" to 110_000_000L,
            "shadow_warp" to 110_000_000L, "recomb" to 154_000_000L, "auto_recomb" to 150_000_000L,
            "claymore" to 10_000_000L, "5th_star" to 151_000_000L, "chestplate" to 150_000_000L,
            "skull_t5" to 128_000_000L, "necron_dye" to 150_000_000L,
        )
        val state = RunState(croesusUnclaimedCount = 4, kismetUses = mutableListOf(KismetUseSample(claimId = "live-chest")))
        assertEquals(profitsAt160Million.keys, TrackedDungeonItems.all.map { it.command }.toSet())
        for ((name, profit) in profitsAt160Million) {
            val item = assertNotNull(TrackedDungeonItems.find(name))
            assertEquals(item, TrackedDungeonItems.find(item.key))
            assertEquals(item, TrackedDungeonItems.find(item.displayName.uppercase()))
            ChestRecorder.recordManualItems(state, item, 2, item.chestCoinCost, prices(160_000_000.0), context, 100)
            assertEquals(listOf(profit, profit), state.chestProfits.takeLast(2).map { it.profit })
            assertEquals(listOf(item.key, item.key), state.trackedItemDrops.takeLast(2).map { it.itemKey })
        }
        val gson = Gson()
        val reloaded = gson.fromJson(gson.toJson(state), RunState::class.java)
        HistoryValidation.validate(reloaded)
        assertEquals(22, reloaded.totalChestsOpened)
        assertEquals(profitsAt160Million.values.sum() * 2, reloaded.totalChestProfit)
        assertEquals(22, reloaded.chestProfits.map { it.claimId }.toSet().size)
        assertTrue(reloaded.chestProfits.all { it.source == "manual" && it.timestamp == 100L && it.pricingComplete })
        assertEquals(22, HistoryQueries.drops(reloaded, "account", "profile").size)
        assertEquals(22, HistoryQueries.chests(reloaded, "account", "profile").size)
        assertTrue(HistoryQueries.chests(reloaded, "account", "other-profile").isEmpty())
        assertTrue(HistoryQueries.drops(reloaded, "other-account", "profile").isEmpty())
        assertEquals(4, reloaded.croesusUnclaimedCount)
        assertFalse(reloaded.kismetUses.single().consumed)
        assertTrue(reloaded.runs.isEmpty())
        assertTrue(reloaded.pendingChestClaims.isEmpty())
        assertEquals(TrackedDungeonItems.find("necron_dye"), TrackedDungeonItems.find("DYE_NECRON"))
        assertEquals(TrackedDungeonItems.find("handle"), TrackedDungeonItems.find("Necron's Handle"))
    }

    @Test fun `custom costs apply per item including losses and free chests without deduplicating commands`() {
        val state = RunState()
        val item = TrackedDungeonItems.find("recomb")!!
        ChestRecorder.recordManualItems(state, item, 2, 5_000_000, prices(7_000_000.0), context, 100)
        ChestRecorder.recordManualItems(state, item, 1, 10_000_000, prices(9_000_000.0), context, 100)
        ChestRecorder.recordManualItems(state, item, 1, 0, prices(9_000_000.0), context, 100)
        assertEquals(listOf(2_000_000L, 2_000_000L, -1_000_000L, 9_000_000L), state.chestProfits.map { it.profit })
        assertEquals(listOf(5_000_000L, 5_000_000L, 10_000_000L, 0L), state.chestProfits.map { it.chestCoinCost })
        assertEquals(4, state.trackedItemDrops.size)
        val stats = HistoryQueries.profitStats(state, "account", "profile", "Session", false) { it >= 100 }
        assertEquals(12_000_000, stats.profit)
        assertEquals(3_000_000, stats.average)
        assertEquals(0, HistoryQueries.profitStats(state, "account", "profile", "Session", false) { it > 100 }.chests)
    }

    @Test fun `unknown items missing prices invalid inputs and overflowing batches leave history untouched`() {
        val state = RunState(croesusUnclaimedCount = 4)
        val before = Gson().toJson(state)
        val item = TrackedDungeonItems.find("handle")!!
        assertNull(TrackedDungeonItems.find("unknown item"))
        for (price in listOf(null, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                ChestRecorder.recordManualItems(state, item, 2, item.chestCoinCost, prices(price), context, 100)
            }
        }
        for (count in listOf(-1, 0, ChestRecorder.MAX_MANUAL_ITEM_COUNT + 1)) {
            assertFailsWith<IllegalArgumentException> {
                ChestRecorder.recordManualItems(state, item, count, item.chestCoinCost, prices(160_000_000.0), context, 100)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ChestRecorder.recordManualItems(state, item, 1, -1, prices(160_000_000.0), context, 100)
        }
        assertFailsWith<IllegalArgumentException> {
            ChestRecorder.recordManualItems(state, item, 1, item.chestCoinCost, prices(160_000_000.0), context.copy(profileId = ""), 100)
        }
        assertFailsWith<ArithmeticException> {
            ChestRecorder.recordManualItems(state, item, 2, Long.MAX_VALUE, prices(160_000_000.0), context, 100)
        }
        assertEquals(before, Gson().toJson(state))
    }

    private fun prices(value: Double?) = object : PriceService {
        override fun quote(itemId: String) = PriceQuote(itemId, value, PriceSource.BAZAAR_SELL)
        override fun health() = PricingHealth(1, 1, false, null)
    }
}
