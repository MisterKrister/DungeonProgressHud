package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkyBlockPriceServiceTest {
    @Test
    fun `bazaar is preferred and supports both instant modes`() {
        val provider = FakeProvider(
            bazaar = mapOf("ITEM" to BazaarPrice(120.0, 95.0)),
            auction = mapOf("ITEM" to AuctionPrice(500.0, 600.0, 700.0)),
        )
        var options = PricingOptions()
        val service = SkyBlockPriceService(provider, { options }, devonianPrice = { 0.0 })

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
        val service = SkyBlockPriceService(provider, { options }, devonianPrice = { 0.0 })

        assertEquals(500.0, service.quote("ITEM").unitPrice)
        options = options.copy(auctionValuation = AuctionValuation.MEDIAN)
        assertEquals(600.0, service.quote("ITEM").unitPrice)
        options = options.copy(auctionValuation = AuctionValuation.MEAN)
        assertEquals(700.0, service.quote("ITEM").unitPrice)
    }

    @Test
    fun `Devonian is loading-only and hardcoded handles unsupported ids`() {
        val loading = FakeProvider(bazaarCount = 0, auctionCount = 0)
        val service = SkyBlockPriceService(loading, hardcodedPrices = mapOf("SPECIAL" to 42.0), devonianPrice = { 99.0 })
        assertEquals(PriceSource.DEVONIAN_FALLBACK, service.quote("SPECIAL").source)

        val loaded = FakeProvider(bazaarCount = 1, auctionCount = 1)
        val loadedService = SkyBlockPriceService(loaded, hardcodedPrices = mapOf("SPECIAL" to 42.0), devonianPrice = { 99.0 })
        assertEquals(PriceSource.HARDCODED, loadedService.quote("SPECIAL").source)
        assertFalse(loadedService.quote("MISSING").available)
    }

    @Test
    fun `normalization covers aliases and ultimate enchanted books`() {
        assertEquals("WITHER_SHIELD_SCROLL", normalizeSkyBlockId("wither_shield"))
        val service = SkyBlockPriceService(
            provider = FakeProvider(auction = mapOf("ENCHANTMENT_ULTIMATE_WISE_5" to AuctionPrice(10.0, 10.0, 10.0))),
            devonianPrice = { 0.0 },
        )
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", resolveEnchantedBookId("Ultimate Wise", 5, service))
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
