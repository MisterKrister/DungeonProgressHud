package dev.krister.dungeonprogresshud

internal data class ClaimContext(val id: String, val accountId: String, val profileId: String, val profileName: String, val floor: String)
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
        val uses = state.kismetUses.filter { it.claimId == context.id && !it.consumed }
        val missing = (candidate.missingItemIds +
            if (includeKismet && uses.any { !it.priceAvailable }) listOf("KISMET_FEATHER") else emptyList()).distinct()
        if ((!candidate.pricingComplete || missing.isNotEmpty()) && missingPolicy == MissingPriceBehavior.MARK_INCOMPLETE) {
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
        state.trackedItemDrops.addAll(drops)
        uses.forEach { it.consumed = true }
        state.totalChestsOpened++
        state.totalChestProfit += sample.profit
        state.lastChestName = sample.chestName
        state.lastChestProfit = sample.profit
        state.lastMissingItemIds = missing.toMutableList()
        if (state.croesusUnclaimedCount > 0) state.croesusUnclaimedCount--
        return ClaimResult.Recorded(sample)
    }
}
