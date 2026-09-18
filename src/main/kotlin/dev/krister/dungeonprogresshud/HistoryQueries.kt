package dev.krister.dungeonprogresshud

internal object HistoryQueries {
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
