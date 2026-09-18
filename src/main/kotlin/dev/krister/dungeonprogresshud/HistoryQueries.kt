package dev.krister.dungeonprogresshud

internal object HistoryQueries {
    fun observedXp(runs: List<DungeonRunRecord>, floor: String, resetAt: Long): List<Pair<Long, Long>> =
        runs.filter { it.floorLabel.equals(floor, true) && it.rawCataXp > 0 && it.timestamp >= resetAt }
            .map { it.timestamp to it.rawCataXp }.sortedBy { it.first }

    fun profitStats(state: RunState, account: String, profile: String, label: String,
                    lifetime: Boolean, inScope: (Long) -> Boolean): ChestProfitStats {
        val retained = chests(state, account, profile).filter { inScope(it.timestamp) }
        // Older releases capped the ledger at 250 chests but kept the lifetime counters.
        // Their undated remainder belongs only in Total, just like unowned legacy records.
        val missingCount = if (lifetime) (state.totalChestsOpened - state.chestProfits.size).coerceAtLeast(0) else 0
        val missingProfit = if (missingCount > 0) state.totalChestProfit - state.chestProfits.sumOf { it.profit } else 0L
        val profit = retained.sumOf { it.profit } + missingProfit
        val count = retained.size + missingCount
        return ChestProfitStats(label, profit, count, if (count == 0) 0L else profit / count)
    }

    private fun owns(account: String, profile: String, expectedAccount: String, expectedProfile: String) =
        account.isNotBlank() && profile.isNotBlank() && account == expectedAccount && profile == expectedProfile

    fun runs(state: RunState, account: String, profile: String) =
        state.runs.filter { owns(it.accountId, it.profileId, account, profile) }
    fun chests(state: RunState, account: String, profile: String) =
        state.chestProfits.filter { owns(it.accountId, it.profileId, account, profile) || legacy(it.accountId, it.profileId) }
    fun drops(state: RunState, account: String, profile: String) =
        state.trackedItemDrops.filter { owns(it.accountId, it.profileId, account, profile) || legacy(it.accountId, it.profileId) }
    fun kismets(state: RunState, account: String, profile: String) =
        state.kismetUses.filter { owns(it.accountId, it.profileId, account, profile) || legacy(it.accountId, it.profileId) }

    // Local history is included without inventing an account or profile for old records.
    private fun legacy(account: String, profile: String) = account.isBlank() && profile.isBlank()
}
