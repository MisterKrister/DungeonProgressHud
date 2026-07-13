package dev.krister.dungeonprogresshud

import kotlin.math.roundToLong

data class ParsedChestReward(
    val itemId: String,
    val quantity: Int = 1,
    val essence: Boolean = false,
)

data class ChestCalculationInput(
    val chestName: String,
    val rewards: List<ParsedChestReward>,
    val chestCoinCost: Long = 0,
    val keyCost: Long = 0,
    val kismetCost: Long = 0,
)

data class PricedChestItem(
    var itemId: String = "",
    var quantity: Int = 0,
    var unitPrice: Double = 0.0,
    var totalValue: Long = 0,
    var source: PriceSource = PriceSource.UNAVAILABLE,
    var available: Boolean = false,
)

data class ChestProfitCalculation(
    val grossValue: Long,
    val chestCoinCost: Long,
    val keyCost: Long,
    val kismetCost: Long,
    val netProfit: Long,
    val pricingComplete: Boolean,
    val missingItemIds: List<String>,
    val pricedItems: List<PricedChestItem>,
)

class ChestProfitCalculator(private val prices: PriceService) {
    fun calculate(
        input: ChestCalculationInput,
        includeEssence: Boolean = true,
        includeKeyCost: Boolean = false,
        includeKismetCost: Boolean = true,
    ): ChestProfitCalculation {
        val included = input.rewards.filter { includeEssence || !it.essence }
        val priced = included.map { reward ->
            val quote = prices.quote(reward.itemId)
            val total = ((quote.unitPrice ?: 0.0) * reward.quantity.coerceAtLeast(0)).roundToLong()
            PricedChestItem(quote.itemId, reward.quantity, quote.unitPrice ?: 0.0, total, quote.source, quote.available)
        }
        val missing = priced.filterNot { it.available }.map { it.itemId }.distinct()
        val gross = priced.sumOf { it.totalValue }
        val appliedKeyCost = if (includeKeyCost) input.keyCost else 0L
        val appliedKismetCost = if (includeKismetCost) input.kismetCost else 0L
        return ChestProfitCalculation(
            grossValue = gross,
            chestCoinCost = input.chestCoinCost,
            keyCost = appliedKeyCost,
            kismetCost = appliedKismetCost,
            netProfit = gross - input.chestCoinCost - appliedKeyCost - appliedKismetCost,
            pricingComplete = missing.isEmpty(),
            missingItemIds = missing,
            pricedItems = priced,
        )
    }
}

internal class EventDeduplicator(private val windowMillis: Long = 2_000L) {
    private val lastSeen = mutableMapOf<String, Long>()

    fun shouldAccept(key: String, timestamp: Long): Boolean {
        val previous = lastSeen[key]
        if (previous != null && timestamp - previous in 0 until windowMillis) return false
        lastSeen[key] = timestamp
        lastSeen.entries.removeIf { timestamp - it.value >= windowMillis }
        return true
    }
}
