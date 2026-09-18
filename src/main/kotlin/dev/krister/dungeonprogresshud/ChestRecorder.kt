package dev.krister.dungeonprogresshud

import kotlin.math.roundToLong

data class ClaimContext(val id: String, val accountId: String, val profileId: String, val profileName: String, val floor: String)
data class PendingChestClaim(val candidate: ChestProfitCandidate, val context: ClaimContext,
    val source: String, val timestamp: Long, val includeKismet: Boolean)
internal sealed interface ClaimResult {
    data class Recorded(val sample: ChestProfitSample) : ClaimResult
    data class Incomplete(val missing: List<String>) : ClaimResult
    data object Duplicate : ClaimResult
}

/** Validate and prepare everything before changing the ledger. Call only for confirmed or explicit fake claims. */
internal object ChestRecorder {
    fun record(state: RunState, candidate: ChestProfitCandidate, context: ClaimContext, source: String,
               now: Long, missingPolicy: MissingPriceBehavior, includeKismet: Boolean): ClaimResult {
        if (state.chestProfits.any { it.claimId == context.id }) return ClaimResult.Duplicate
        val wasPending = state.pendingChestClaims.any { it.context.id == context.id }
        val uses = state.kismetUses.filter { it.claimId == context.id && !it.consumed }
        val missing = (candidate.missingItemIds +
            if (includeKismet && uses.any { !it.priceAvailable }) listOf("KISMET_FEATHER") else emptyList()).distinct()
        if ((!candidate.pricingComplete || missing.isNotEmpty()) && missingPolicy == MissingPriceBehavior.MARK_INCOMPLETE) {
            if (!wasPending) {
                state.pendingChestClaims.add(PendingChestClaim(candidate, context, source, now, includeKismet))
                if (state.croesusUnclaimedCount > 0) state.croesusUnclaimedCount--
            }
            return ClaimResult.Incomplete(missing)
        }
        val cost = if (includeKismet) uses.sumOf { it.cost } else 0L
        val sample = ChestProfitSample(
            claimId = context.id, source = source, timestamp = now, chestName = candidate.chestName,
            profit = candidate.profit - cost, grossValue = candidate.grossValue,
            chestCoinCost = candidate.chestCoinCost, keyCost = candidate.keyCost, kismetCost = cost,
            pricingComplete = candidate.pricingComplete && missing.isEmpty(), missingItemIds = missing.toMutableList(),
            pricedItems = candidate.pricedItems.map { it.copy() }.toMutableList(), detailsVersion = 1,
            profileName = context.profileName, floorLabel = context.floor,
            accountId = context.accountId, profileId = context.profileId,
        )
        val drops = if (context.floor == "M7") candidate.trackedDrops.map {
            TrackedItemDropSample(now, it.key, it.displayName, candidate.chestName, context.floor,
                context.profileName, context.accountId, context.profileId)
        } else emptyList()
        state.chestProfits.add(sample)
        if (wasPending) state.chestProfits.sortBy { it.timestamp }
        state.pendingChestClaims.removeAll { it.context.id == context.id }
        state.trackedItemDrops.addAll(drops)
        uses.forEach { it.consumed = true }
        state.totalChestsOpened++
        state.totalChestProfit += sample.profit
        state.lastChestName = state.chestProfits.last().chestName
        state.lastChestProfit = state.chestProfits.last().profit
        state.lastMissingItemIds = missing.toMutableList()
        if (!wasPending && state.croesusUnclaimedCount > 0) state.croesusUnclaimedCount--
        return ClaimResult.Recorded(sample)
    }

    fun retryPending(state: RunState, prices: PriceService): Int {
        var recorded = 0
        for (pending in state.pendingChestClaims.toList()) {
            val items = pending.candidate.pricedItems.map { item ->
                val quote = prices.quote(item.itemId)
                if (!quote.available) item else item.copy(unitPrice = quote.unitPrice!!,
                    totalValue = (quote.unitPrice * item.quantity).roundToLong(), source = quote.source, available = true)
            }
            val missingKey = "DUNGEON_CHEST_KEY" in pending.candidate.missingItemIds
            val keyQuote = if (missingKey) prices.quote("DUNGEON_CHEST_KEY") else null
            val uses = state.kismetUses.filter { it.claimId == pending.context.id && !it.priceAvailable }
            uses.forEach { use ->
                val quote = prices.quote("KISMET_FEATHER")
                if (quote.available) {
                    use.cost = quote.unitPrice!!.roundToLong()
                    use.priceAvailable = true
                    use.priceSource = quote.source
                }
            }
            val candidate = pending.candidate.copy(pricedItems = items,
                keyCost = keyQuote?.unitPrice?.roundToLong() ?: pending.candidate.keyCost,
                missingItemIds = (pending.candidate.missingItemIds.filter { id ->
                    items.none { it.itemId == id && it.available } && !(id == "DUNGEON_CHEST_KEY" && keyQuote?.available == true)
                } + items.filterNot { it.available }.map { it.itemId }).distinct())
            if (record(state, candidate, pending.context, pending.source, pending.timestamp,
                    MissingPriceBehavior.MARK_INCOMPLETE, pending.includeKismet) is ClaimResult.Recorded) recorded++
        }
        return recorded
    }
}
