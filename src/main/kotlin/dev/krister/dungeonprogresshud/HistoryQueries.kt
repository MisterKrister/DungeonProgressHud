package dev.krister.dungeonprogresshud

internal object HistoryQueries {
    fun runTimeSeconds(runs: List<DungeonRunRecord>): Long = runs.sumOf { it.runTimeSeconds.coerceAtLeast(0).toLong() }

    fun xpPerRunHour(runs: List<DungeonRunRecord>): Long? {
        val timed = runs.filter { it.runTimeSeconds > 0 && it.rawCataXp > 0 }
        return SessionRates.xpPerHour(timed.map { it.rawCataXp }, runTimeSeconds(timed) * 1_000L)
    }

    fun observedXp(runs: List<DungeonRunRecord>, floor: String, resetAt: Long): List<Pair<Long, Long>> =
        runs.filter { it.floorLabel.equals(floor, true) && it.rawCataXp > 0 && it.timestamp >= resetAt }
            .map { it.timestamp to it.rawCataXp }.sortedBy { it.first }

    /** Seed a new session's estimates from the last saved run until it has its own measurements. */
    fun progressionRuns(runs: List<DungeonRunRecord>, floor: String, resetAt: Long, now: Long,
                        useLastRun: Boolean, inScope: (Long) -> Boolean): List<DungeonRunRecord> {
        val eligible = runs.filter {
            it.floorLabel.equals(floor, true) && it.rawCataXp > 0 && it.timestamp in resetAt..now
        }
        return eligible.filter { inScope(it.timestamp) }.ifEmpty {
            if (useLastRun) listOfNotNull(eligible.maxByOrNull { it.timestamp }) else emptyList()
        }
    }

    fun savedProfile(state: RunState, account: String, profile: String, playerName: String, profileName: String): ProfileData? {
        if (state.lastBaselineAt <= 0 || !owns(state.lastPlayerUuid, state.lastProfileId, account, profile)) return null
        return ProfileData(playerName, account, profileName, state.lastCatacombsXp, profile,
            state.lastClassExperience, DungeonClasses.key(runs(state, account, profile).maxByOrNull { it.timestamp }?.dungeonClass))
    }

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
    fun classXp(state: RunState, account: String, profile: String) =
        state.classXpSamples.filter { owns(it.accountId, it.profileId, account, profile) }
    fun chests(state: RunState, account: String, profile: String) =
        state.chestProfits.filter { owns(it.accountId, it.profileId, account, profile) || legacy(it.accountId, it.profileId) }
    fun drops(state: RunState, account: String, profile: String) =
        state.trackedItemDrops.filter { owns(it.accountId, it.profileId, account, profile) || legacy(it.accountId, it.profileId) }
    fun kismets(state: RunState, account: String, profile: String) =
        state.kismetUses.filter { owns(it.accountId, it.profileId, account, profile) || legacy(it.accountId, it.profileId) }

    // Local history is included without inventing an account or profile for old records.
    private fun legacy(account: String, profile: String) = account.isBlank() && profile.isBlank()
}
