package dev.krister.dungeonprogresshud

import java.util.UUID

data class TrackedDrop(
    val key: String,
    val displayName: String,
)

data class RunState(
    var samples: MutableList<RunSample> = mutableListOf(),
    var xpIntervals: MutableList<XpInterval> = mutableListOf(),
    var lastBaselineAt: Long = 0,
    var averagingResetAt: Long = 0,
    var lastProfileId: String = "",
    var lastCatacombsXp: Long = 0,
    var lastClassExperience: Map<String, Long> = emptyMap(),
    var classXpSamples: MutableList<ClassXpSample> = mutableListOf(),
    var lastPlayerUuid: String = "",
    var runs: MutableList<DungeonRunRecord> = mutableListOf(),
    var chestProfits: MutableList<ChestProfitSample> = mutableListOf(),
    var pendingChestClaims: MutableList<PendingChestClaim> = mutableListOf(),
    var medianPricingDefaultApplied: Boolean = false,
    var lastChestName: String = "",
    var lastChestProfit: Long = 0,
    var totalChestProfit: Long = 0,
    var totalChestsOpened: Int = 0,
    var chestProfitWindowMillis: Long = 0,
    var lastLogImportAt: Long = 0,
    var importedLogFiles: MutableMap<String, String> = mutableMapOf(),
    var hudViewMode: String = "profit",
    var trackedItemDrops: MutableList<TrackedItemDropSample> = mutableListOf(),
    var croesusUnclaimedCount: Int = -1,
    var totalKismetsUsed: Int = 0,
    var kismetUses: MutableList<KismetUseSample> = mutableListOf(),
    var lastMissingItemIds: MutableList<String> = mutableListOf(),
    var hudLineOrder: MutableList<String>? = mutableListOf(),
)

data class RunSample(
    var timestamp: Long = 0,
    var floorLabel: String = "M7",
    var rawXpDelta: Long = 0,
    var normalizedXpDelta: Long = 0,
    var accountId: String = "",
    var profileId: String = "",
)

data class DungeonRunRecord(
    var source: String = "legacy-unknown",
    var timestamp: Long = 0,
    var floorLabel: String = "M7",
    var runTimeSeconds: Int = 0,
    var score: Int = 0,
    var grade: String = "",
    var rawCataXp: Long = 0,
    var normalizedCataXp: Long = 0,
    var accountId: String = "",
    var profileId: String = "",
    var dungeonClass: String = "",
    var classExperience: Map<String, Long> = emptyMap(),
    var partyClasses: Set<String> = emptySet(),
)

data class ClassXpSample(
    val timestamp: Long = 0,
    val accountId: String = "",
    val profileId: String = "",
    val floorLabel: String = "",
    val dungeonClass: String = "",
    val runs: Int = 0,
    val experience: Map<String, Long> = emptyMap(),
)

data class ChestProfitSample(
    var claimId: String = "",
    var source: String = "legacy-unknown",
    var timestamp: Long = 0,
    var chestName: String = "",
    var profit: Long = 0,
    var grossValue: Long = 0,
    var chestCoinCost: Long = 0,
    var keyCost: Long = 0,
    var kismetCost: Long = 0,
    var pricingComplete: Boolean = true,
    var missingItemIds: MutableList<String> = mutableListOf(),
    var pricedItems: MutableList<PricedChestItem> = mutableListOf(),
    var detailsVersion: Int = 0,
    var profileName: String = "",
    var floorLabel: String = "M7",
    var accountId: String = "",
    var profileId: String = "",
)

data class TrackedItemDropSample(
    var timestamp: Long = 0,
    var itemKey: String = "",
    var displayName: String = "",
    var chestName: String = "",
    var floorLabel: String = "M7",
    var profileName: String = "",
    var accountId: String = "",
    var profileId: String = "",
)

data class KismetUseSample(
    var timestamp: Long = 0,
    var chestName: String = "",
    var cost: Long = 0,
    var profileName: String = "",
    var floorLabel: String = "M7",
    var accountId: String = "",
    var profileId: String = "",
    var claimId: String = "",
    var priceAvailable: Boolean = false,
    var priceSource: PriceSource = PriceSource.UNAVAILABLE,
    var consumed: Boolean = false,
)

data class ChestProfitCandidate(
    val chestName: String,
    val chestCoinCost: Long,
    val keyCost: Long,
    val itemCount: Int,
    val scannedSlots: Int,
    val pricedItems: List<PricedChestItem>,
    val missingItemIds: List<String>,
    val trackedDrops: List<TrackedDrop> = emptyList(),
) {
    val grossValue: Long get() = pricedItems.sumOf { it.totalValue }
    val profit: Long get() = grossValue - chestCoinCost - keyCost
    val cost: Int get() = (chestCoinCost + keyCost).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val pricingComplete: Boolean get() = missingItemIds.isEmpty() && pricedItems.all { it.available }
    fun summary(): String = "chest=$chestName profit=$profit gross=$grossValue cost=$cost complete=$pricingComplete missing=${missingItemIds.joinToString()} items=$itemCount scannedSlots=$scannedSlots"
}

data class ChestProfitItem(
    val itemId: String,
    val amount: Int,
    val essence: Boolean,
    val trackedDrop: TrackedDrop? = null,
)

data class ProfileData(
    val playerName: String,
    val playerUuid: String,
    val profileName: String,
    val catacombsExperience: Long,
    val profileId: String,
    val classExperience: Map<String, Long> = emptyMap(),
    val selectedDungeonClass: String? = null,
)

data class ProfileRequest(
    val activeProfile: Pair<UUID?, String>? = null,
    val playerName: String,
    val playerUuid: UUID,
)

data class ChestProfitStats(
    val label: String,
    val profit: Long,
    val chests: Int,
    val average: Long,
)

data class RunSummary(
    val label: String,
    val runs: Int,
    val xp: Long,
    val profit: Long,
    val chests: Int,
    val averageRunTimeSeconds: Int,
    val profitPerRun: Long,
    val profitPerChest: Long,
    val profitPerHour: Long,
    val xpPerHour: Long,
)

data class PendingCompletionState(
    val timestamp: Long,
    val floor: String,
    val runTimeSeconds: Int,
    val score: Int,
    val grade: String,
)

data class ImportedDungeonRun(
    val timestamp: Long,
    val floorLabel: String,
    val runTimeSeconds: Int,
    val score: Int,
    val grade: String,
    val rawCataXp: Long,
)

data class ImportedParseResult(
    val pending: PendingCompletionState,
    val lastDedupeKey: String,
    val lastDedupeAt: Long,
    val run: ImportedDungeonRun?,
)
