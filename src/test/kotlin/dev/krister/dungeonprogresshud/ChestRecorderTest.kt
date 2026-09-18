package dev.krister.dungeonprogresshud

import kotlin.test.*

class ChestRecorderTest {
    private val candidate = ChestProfitCandidate("Bedrock", 0, 0, 1, 54, listOf(PricedChestItem("ITEM", 1, 10_000_000.0, 10_000_000, PriceSource.BAZAAR_BUY, true)), emptyList())
    private val context = ClaimContext("run1:bedrock", "account", "profile", "Apple", "M7")

    @Test fun `repeated claim consumes a kismet exactly once even when price changes`() {
        val state = RunState(kismetUses = mutableListOf(KismetUseSample(cost = 1_000_000, claimId = context.id, priceAvailable = true)))
        assertIs<ClaimResult.Recorded>(ChestRecorder.record(state, candidate, context, "server-confirmed", 1, MissingPriceBehavior.MARK_INCOMPLETE, true))
        assertEquals(9_000_000, state.totalChestProfit)
        assertIs<ClaimResult.Duplicate>(ChestRecorder.record(state, candidate.copy(pricedItems = listOf(candidate.pricedItems.single().copy(unitPrice = 11_000_000.0, totalValue = 11_000_000))), context, "server-confirmed", 999_999, MissingPriceBehavior.MARK_INCOMPLETE, true))
        assertEquals(1, state.totalChestsOpened)
        assertEquals(9_000_000, state.totalChestProfit)
        assertTrue(state.kismetUses.single().consumed)
    }

    @Test fun `missing kismet saves a pending claim until priced or explicit zero policy`() {
        val state = RunState(kismetUses = mutableListOf(KismetUseSample(claimId = context.id)), croesusUnclaimedCount = 2)
        assertIs<ClaimResult.Incomplete>(ChestRecorder.record(state, candidate, context, "server-confirmed", 1, MissingPriceBehavior.MARK_INCOMPLETE, true))
        assertEquals(0, state.totalChestsOpened)
        assertEquals(1, state.pendingChestClaims.size)
        assertEquals(1, state.croesusUnclaimedCount)
        assertFalse(state.kismetUses.single().consumed)
        ChestRecorder.record(state, candidate, context, "fake-open", 2, MissingPriceBehavior.COUNT_AS_ZERO, true)
        assertFalse(state.chestProfits.single().pricingComplete)
        assertEquals("fake-open", state.chestProfits.single().source)
        assertTrue(state.pendingChestClaims.isEmpty())
        assertEquals(1, state.croesusUnclaimedCount)
    }

    @Test fun `unpriced confirmed claims survive reload and resolve once with original ownership and date`() {
        val state = RunState(kismetUses = mutableListOf(KismetUseSample(claimId = context.id)))
        val unpriced = candidate.copy(pricedItems = listOf(PricedChestItem("ITEM", 2)), missingItemIds = listOf("ITEM", "DUNGEON_CHEST_KEY"))
        repeat(2) { ChestRecorder.record(state, unpriced, context, "server-chat", 100, MissingPriceBehavior.MARK_INCOMPLETE, true) }
        assertEquals(1, state.pendingChestClaims.size)
        val gson = com.google.gson.Gson()
        val reloaded = gson.fromJson(gson.toJson(state), RunState::class.java)
        HistoryValidation.validate(reloaded)
        var loaded = false
        val prices = object : PriceService {
            override fun quote(itemId: String) = PriceQuote(itemId, if (!loaded) null else when (itemId) {
                "ITEM" -> 10_000_000.0
                "DUNGEON_CHEST_KEY" -> 100_000.0
                else -> 1_000_000.0
            }, PriceSource.BAZAAR_BUY)
            override fun health() = PricingHealth(1, 1, false, null)
        }
        assertEquals(0, ChestRecorder.retryPending(reloaded, prices))
        assertEquals(0, reloaded.totalChestProfit)
        loaded = true
        assertEquals(1, ChestRecorder.retryPending(reloaded, prices))
        assertEquals(18_900_000, reloaded.totalChestProfit)
        val sample = reloaded.chestProfits.single()
        assertEquals(100, sample.timestamp)
        assertEquals("account", sample.accountId)
        assertEquals("profile", sample.profileId)
        assertTrue(sample.pricingComplete)
        assertTrue(reloaded.kismetUses.single().consumed)
        assertEquals(0, ChestRecorder.retryPending(reloaded, prices))
        assertEquals(1, reloaded.totalChestsOpened)
    }

    @Test fun `300 equal-profit chests retain all records and separate run identities`() {
        val state = RunState()
        repeat(300) { index ->
            ChestRecorder.record(state, candidate, context.copy(id = "run$index:bedrock"), "server-confirmed", index.toLong(), MissingPriceBehavior.MARK_INCOMPLETE, true)
        }
        assertEquals(300, state.chestProfits.size)
        assertEquals(3_000_000_000L, state.chestProfits.sumOf { it.profit })
    }
}
