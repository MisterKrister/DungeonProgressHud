package dev.krister.dungeonprogresshud

internal object RunStateMigration {
    fun migrate(state: RunState) {
        state.chestProfits.filter { it.detailsVersion <= 0 }.forEach { old ->
            // Preserve historical amounts without inventing itemized pricing provenance.
            old.pricingComplete = false
            old.missingItemIds = mutableListOf()
            old.pricedItems = mutableListOf()
        }

        val retainedProfit = state.chestProfits.sumOf { it.profit }
        if (state.totalChestsOpened < state.chestProfits.size ||
            (state.totalChestsOpened == state.chestProfits.size && state.totalChestProfit != retainedProfit)
        ) {
            state.totalChestsOpened = state.chestProfits.size
            state.totalChestProfit = retainedProfit
        }
        if (state.totalKismetsUsed < state.kismetUses.size) state.totalKismetsUsed = state.kismetUses.size
    }
}
