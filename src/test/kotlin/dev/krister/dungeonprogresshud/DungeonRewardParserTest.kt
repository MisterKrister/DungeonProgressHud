package dev.krister.dungeonprogresshud

import com.google.gson.JsonParser
import kotlin.test.*

class DungeonRewardParserTest {
    @Test fun `books armor essence shards and regular items retain identities and quantities`() {
        val expected = mapOf(
            "Enchanted Book (Bank I)" to ParsedChestReward("ENCHANTMENT_ULTIMATE_BANK_1"),
            "Enchanted Book (Soul Eater I)" to ParsedChestReward("ENCHANTMENT_ULTIMATE_SOUL_EATER_1"),
            "Enchanted Book (Ultimate Wise V)" to ParsedChestReward("ENCHANTMENT_ULTIMATE_WISE_5"),
            "Enchanted Book (Feather Falling X) x2" to ParsedChestReward("ENCHANTMENT_FEATHER_FALLING_10", 2),
            "- Wither Essence x1,200" to ParsedChestReward("ESSENCE_WITHER", 1200, true),
            "Undead Essence × 50" to ParsedChestReward("ESSENCE_UNDEAD", 50, true),
            "Wither Chestplate" to ParsedChestReward("WITHER_CHESTPLATE"),
            "Wither Shard x3" to ParsedChestReward("SHARD_WITHER", 3),
            "Necron's Handle" to ParsedChestReward("NECRON_HANDLE"),
            "Recombobulator 3000" to ParsedChestReward("RECOMBOBULATOR_3000"),
        )
        expected.forEach { (name, reward) -> assertEquals(reward, DungeonRewardParser.parse(name), name) }
        assertEquals(ParsedChestReward("ENCHANTMENT_ULTIMATE_WISDOM_1"),
            DungeonRewardParser.parse("Enchanted Book", skyBlockId = "ENCHANTMENT_ULTIMATE_WISDOM_1"))
        assertEquals(ParsedChestReward("ENCHANTMENT_ULTIMATE_WISDOM_1"),
            DungeonRewardParser.parse("Enchanted Book", skyBlockId = "ENCHANTED_BOOK", lore = listOf("", "§dWisdom I", "Description")))
        assertEquals(ParsedChestReward("WITHER_CHESTPLATE"),
            DungeonRewardParser.parse("Ancient Wither Chestplate", skyBlockId = "WITHER_CHESTPLATE"))
        assertNull(DungeonRewardParser.parse("Enchanted Book"))
        assertNull(DungeonRewardParser.parse("Wither Shard x9999999999999"))
    }

    @Test fun `mixed chest prices every reward with quantities and subtracts purchase costs`() {
        val rewards = listOf("Enchanted Book (Bank I)", "Wither Chestplate", "Wither Shard x3", "Wither Essence x1,200")
            .map { DungeonRewardParser.parse(it)!! }
        val quotes = mapOf("ENCHANTMENT_ULTIMATE_BANK_1" to 10_000.0, "WITHER_CHESTPLATE" to 10_000_000.0,
            "SHARD_WITHER" to 100_000.0, "ESSENCE_WITHER" to 2_000.0)
        val prices = object : PriceService {
            override fun quote(itemId: String) = PriceQuote(itemId, quotes[itemId], PriceSource.AUCTION_MEDIAN)
            override fun health() = PricingHealth(1, 1, false, null)
        }
        val chest = ChestProfitCalculator(prices).calculate(ChestCalculationInput("Bedrock", rewards, 5_000_000, 0, 1_000_000))
        assertTrue(chest.pricingComplete)
        assertEquals(12_710_000, chest.grossValue)
        assertEquals(6_710_000, chest.netProfit)
        assertEquals(4, chest.pricedItems.size)
    }

    @Test fun `market feeds preserve weighted bazaar sides and auction statistics`() {
        fun json(text: String) = JsonParser.parseString(text).asJsonObject
        // Hypixel's documented example: buyPrice is 4.99, sellPrice is 4.2.
        val bazaar = PriceFeedParser.bazaar(json("""{"success":true,"products":{"INK_SACK:3":{"quick_status":{"buyPrice":4.99,"sellPrice":4.2}}}}"""))
        assertEquals(BazaarPrice(4.99, 4.2), bazaar["INK_SACK:3"])
        val auctions = PriceFeedParser.auctions(json("""{"WITHER_CHESTPLATE":{"lowest":5,"median":10,"mean":100}}"""))
        assertEquals(AuctionPrice(5.0, 10.0, 100.0), auctions["WITHER_CHESTPLATE"])
        assertFails { PriceFeedParser.bazaar(json("""{"success":false}""")) }
        assertFails { PriceFeedParser.auctions(json("{}")) }
        assertFails { PriceFeedParser.auctions(json("""{"ITEM":{"lowest":-1}}""")) }
    }
}
