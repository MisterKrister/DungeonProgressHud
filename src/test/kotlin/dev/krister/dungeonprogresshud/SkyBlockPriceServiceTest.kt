package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkyBlockPriceServiceTest {
    @Test
    fun `empty Bazaar markets resolve saved book claims while unknown items stay pending`() {
        val ids = listOf("ENCHANTMENT_ULTIMATE_BANK_1", "ENCHANTMENT_FEATHER_FALLING_6")
        val products = ids.joinToString(",") { "\"$it\":{\"quick_status\":{\"buyPrice\":0.0,\"sellPrice\":0.0}}" }
        val feed = PriceFeedParser.bazaar(com.google.gson.JsonParser.parseString(
            "{\"success\":true,\"products\":{$products}}").asJsonObject)
        val prices = SkyBlockPriceService(FakeProvider(bazaar = feed), noammPrice = { _, _ -> error("Feed is already loaded") })
        for (mode in BazaarValuation.entries) {
            val service = SkyBlockPriceService(FakeProvider(bazaar = feed), { PricingOptions(bazaarValuation = mode) },
                noammPrice = { _, _ -> error("Feed is already loaded") })
            for (id in ids) {
                assertTrue(service.quote(id).available)
                assertEquals(0.0, service.quote(id).unitPrice)
            }
            assertFalse(service.quote("UNKNOWN_REWARD").available)
        }

        val state = RunState()
        for ((index, id) in (ids + "UNKNOWN_REWARD").withIndex()) {
            val candidate = ChestProfitCandidate("Diamond", 250_000, 0, 2, 54,
                listOf(PricedChestItem(id, 1), PricedChestItem("KNOWN_REWARD", 1, 400_000.0, 400_000, PriceSource.BAZAAR_BUY, true)),
                listOf(id))
            ChestRecorder.record(state, candidate, ClaimContext("claim$index", "a", "p", "Peach", "M7"),
                "server-chat", 100L + index, MissingPriceBehavior.MARK_INCOMPLETE, false)
        }
        val gson = com.google.gson.Gson()
        val saved = gson.fromJson(gson.toJson(state), RunState::class.java)
        assertEquals(2, ChestRecorder.retryPending(saved, prices))
        assertEquals(300_000, saved.totalChestProfit)
        assertEquals(listOf(100L, 101L), saved.chestProfits.map { it.timestamp })
        assertTrue(saved.chestProfits.all { it.pricingComplete && it.accountId == "a" && it.profileId == "p" })
        assertEquals(listOf("UNKNOWN_REWARD"), saved.pendingChestClaims.single().candidate.missingItemIds)
        assertEquals(0, ChestRecorder.retryPending(saved, prices))
        assertEquals(2, saved.totalChestsOpened)
        assertFalse(PriceQuote("INVALID", Double.NaN, PriceSource.BAZAAR_BUY).available)
        assertFalse(PriceQuote("INVALID", -1.0, PriceSource.BAZAAR_BUY).available)
    }

    @Test
    fun `bazaar is preferred and supports both instant modes`() {
        val provider = FakeProvider(
            bazaar = mapOf("ITEM" to BazaarPrice(120.0, 95.0)),
            auction = mapOf("ITEM" to AuctionPrice(500.0, 600.0, 700.0)),
        )
        var options = PricingOptions()
        val service = SkyBlockPriceService(provider, { options }, noammPrice = { _, _ -> 0.0 })

        assertEquals(120.0, service.quote("item").unitPrice)
        assertEquals(PriceSource.BAZAAR_BUY, service.quote("ITEM").source)
        options = options.copy(bazaarValuation = BazaarValuation.INSTANT_SELL)
        assertEquals(95.0, service.quote("ITEM").unitPrice)
        assertEquals(PriceSource.BAZAAR_SELL, service.quote("ITEM").source)
    }

    @Test
    fun `auction fallback supports lowest median and mean`() {
        val provider = FakeProvider(auction = mapOf("ITEM" to AuctionPrice(500.0, 600.0, 700.0)))
        var options = PricingOptions()
        val service = SkyBlockPriceService(provider, { options }, noammPrice = { _, _ -> 0.0 })

        assertEquals(600.0, service.quote("ITEM").unitPrice)
        options = options.copy(auctionValuation = AuctionValuation.LOWEST_BIN)
        assertEquals(500.0, service.quote("ITEM").unitPrice)
        options = options.copy(auctionValuation = AuctionValuation.MEAN)
        assertEquals(700.0, service.quote("ITEM").unitPrice)
    }

    @Test
    fun `NoammAddons is loading-only and hardcoded handles unsupported ids`() {
        val loading = FakeProvider(bazaarCount = 0, auctionCount = 0)
        val service = SkyBlockPriceService(loading, hardcodedPrices = mapOf("SPECIAL" to 42.0), noammPrice = { _, _ -> 99.0 })
        assertEquals(PriceSource.NOAMM_FALLBACK, service.quote("SPECIAL").source)

        val loaded = FakeProvider(bazaarCount = 1, auctionCount = 1)
        val loadedService = SkyBlockPriceService(loaded, hardcodedPrices = mapOf("SPECIAL" to 42.0), noammPrice = { _, _ -> 99.0 })
        assertEquals(PriceSource.HARDCODED, loadedService.quote("SPECIAL").source)
        assertFalse(loadedService.quote("MISSING").available)
    }

    @Test
    fun `normalization covers aliases and ultimate enchanted books`() {
        assertEquals("WITHER_SHIELD_SCROLL", normalizeSkyBlockId("wither_shield"))
        val dyeService = SkyBlockPriceService(
            provider = FakeProvider(auction = mapOf("DYE_NECRON" to AuctionPrice(20_000_000.0, 21_000_000.0, 22_000_000.0))),
            noammPrice = { _, _ -> 0.0 },
        )
        assertEquals("DYE_NECRON", dyeService.quote("NECRON_DYE").itemId)
        assertEquals(21_000_000.0, dyeService.quote("NECRON_DYE").unitPrice)
        val service = SkyBlockPriceService(
            provider = FakeProvider(auction = mapOf("ENCHANTMENT_ULTIMATE_WISE_5" to AuctionPrice(10.0, 10.0, 10.0))),
            noammPrice = { _, _ -> 0.0 },
        )
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", resolveEnchantedBookId("Ultimate Wise", 5, service))
    }

    @Test
    fun `book identity does not depend on prices being available`() {
        val service = SkyBlockPriceService(FakeProvider(), noammPrice = { _, _ -> 0.0 })
        assertEquals("ENCHANTMENT_SHARPNESS_5", resolveEnchantedBookId("Sharpness", 5, service))
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", resolveEnchantedBookId("Ultimate Wise", 5, service))
    }

    @Test
    fun `disabled loading fallback never supplies a quote`() {
        val service = SkyBlockPriceService(FakeProvider(bazaarCount = 0, auctionCount = 0),
            { PricingOptions(allowNoammWhileLoading = false) }, noammPrice = { _, _ -> error("Fallback must not be called") })
        assertFalse(service.quote("ITEM").available)
    }

    @Test fun `NoammAddons loading fallback receives the selected Bazaar side`() {
        var options = PricingOptions()
        val service = SkyBlockPriceService(FakeProvider(bazaarCount = 0, auctionCount = 0), { options },
            noammPrice = { _, side -> if (side == BazaarValuation.INSTANT_BUY) 120.0 else 95.0 })
        assertEquals(120.0, service.quote("ITEM").unitPrice)
        assertTrue(service.health().fallbackActive)
        options = options.copy(bazaarValuation = BazaarValuation.INSTANT_SELL)
        assertEquals(95.0, service.quote("ITEM").unitPrice)
        service.beginCalculation()
        assertFalse(service.health().fallbackActive)
    }

    private class FakeProvider(
        private val bazaar: Map<String, BazaarPrice> = emptyMap(),
        private val auction: Map<String, AuctionPrice> = emptyMap(),
        private val bazaarCount: Int = bazaar.size.coerceAtLeast(1),
        private val auctionCount: Int = auction.size.coerceAtLeast(1),
    ) : PriceDataProvider {
        override fun bazaar(itemId: String): BazaarPrice? = bazaar[itemId]
        override fun auction(itemId: String): AuctionPrice? = auction[itemId]
        override fun bazaarItemCount(): Int = bazaarCount
        override fun auctionItemCount(): Int = auctionCount
    }
}

class ChestProfitCalculatorTest {
    @Test
    fun `quantity essence and all costs are calculated`() {
        val prices = mapOf("ITEM" to 100.0, "ESSENCE_WITHER" to 5.0)
        val calculator = ChestProfitCalculator(MapPriceService(prices))
        val result = calculator.calculate(
            ChestCalculationInput(
                "Bedrock",
                listOf(ParsedChestReward("ITEM", 3), ParsedChestReward("ESSENCE_WITHER", 10, essence = true)),
                chestCoinCost = 40,
                keyCost = 20,
                kismetCost = 15,
            ),
            includeKeyCost = true,
        )

        assertEquals(350, result.grossValue)
        assertEquals(275, result.netProfit)
        assertTrue(result.pricingComplete)
    }

    @Test
    fun `essence key and kismet switches are independent`() {
        val calculator = ChestProfitCalculator(MapPriceService(mapOf("ITEM" to 100.0, "ESSENCE_WITHER" to 5.0)))
        val result = calculator.calculate(
            ChestCalculationInput("Chest", listOf(ParsedChestReward("ITEM"), ParsedChestReward("ESSENCE_WITHER", 10, true)), 40, 20, 15),
            includeEssence = false,
            includeKeyCost = false,
            includeKismetCost = false,
        )
        assertEquals(100, result.grossValue)
        assertEquals(60, result.netProfit)
    }

    @Test
    fun `one or multiple kismets and missing prices are represented`() {
        val calculator = ChestProfitCalculator(MapPriceService(mapOf("ITEM" to 100.0)))
        val one = calculator.calculate(ChestCalculationInput("Chest", listOf(ParsedChestReward("ITEM")), kismetCost = 15))
        val multiple = calculator.calculate(ChestCalculationInput("Chest", listOf(ParsedChestReward("ITEM"), ParsedChestReward("MISSING", 2)), kismetCost = 30))
        assertEquals(85, one.netProfit)
        assertEquals(70, multiple.netProfit)
        assertFalse(multiple.pricingComplete)
        assertEquals(listOf("MISSING"), multiple.missingItemIds)
    }

    private class MapPriceService(private val prices: Map<String, Double>) : PriceService {
        override fun quote(itemId: String): PriceQuote = prices[normalizeSkyBlockId(itemId)]
            ?.let { PriceQuote(normalizeSkyBlockId(itemId), it, PriceSource.HARDCODED) }
            ?: PriceQuote(normalizeSkyBlockId(itemId), null, PriceSource.UNAVAILABLE)
        override fun health() = PricingHealth(prices.size, prices.size, false, null)
    }
}

class EventDeduplicatorTest {
    @Test
    fun `duplicate claims and rerolls are suppressed only inside the window`() {
        val deduplicator = EventDeduplicator(2_000)
        assertTrue(deduplicator.shouldAccept("container:chest:claim", 10_000))
        assertFalse(deduplicator.shouldAccept("container:chest:claim", 11_000))
        assertTrue(deduplicator.shouldAccept("container:chest:kismet", 11_000))
        assertTrue(deduplicator.shouldAccept("container:chest:claim", 12_000))
    }
}
