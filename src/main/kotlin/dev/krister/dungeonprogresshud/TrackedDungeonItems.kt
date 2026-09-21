package dev.krister.dungeonprogresshud

import java.util.Locale

internal data class TrackedDropDefinition(
    val key: String,
    val displayName: String,
    val chestCoinCost: Long,
    val aliases: Set<String> = emptySet(),
) {
    val command: String get() = displayName.lowercase(Locale.ROOT).replace(' ', '_')
}

internal object TrackedDungeonItems {
    // Standard M7 opening costs; /dph items add accepts the actual cost as an override.
    // https://hypixel-skyblock.fandom.com/wiki/Dungeon_Reward_Chest/Loot
    val all = listOf(
        TrackedDropDefinition("NECRON_HANDLE", "Handle", 100_000_000, setOf("NECRON'S_HANDLE", "NECRONS_HANDLE")),
        TrackedDropDefinition("IMPLOSION_SCROLL", "Implosion", 50_000_000),
        TrackedDropDefinition("WITHER_SHIELD_SCROLL", "Wither Shield", 50_000_000),
        TrackedDropDefinition("SHADOW_WARP_SCROLL", "Shadow Warp", 50_000_000),
        TrackedDropDefinition("RECOMBOBULATOR_3000", "Recomb", 6_000_000, setOf("RECOMBOBULATOR")),
        TrackedDropDefinition("AUTO_RECOMBOBULATOR", "Auto Recomb", 10_000_000, setOf("AUTO_RECOMBOBULATOR_3000")),
        TrackedDropDefinition("DARK_CLAYMORE", "Claymore", 150_000_000),
        TrackedDropDefinition("FIFTH_MASTER_STAR", "5th Star", 9_000_000, setOf("5TH_MASTER_STAR", "MASTER_STAR_TIER_5")),
        TrackedDropDefinition("WITHER_CHESTPLATE", "Chestplate", 10_000_000),
        TrackedDropDefinition("MASTER_SKULL_TIER_5", "Skull T5", 32_000_000, setOf("MASTER_SKULL_5")),
        TrackedDropDefinition("NECRON_DYE", "Necron Dye", 10_000_000, setOf("DYE_NECRON", "NECRONS_DYE", "NECRON'S_DYE")),
    )
    private val byAlias = all.flatMap { item ->
        (item.aliases + item.key + item.displayName).map { normalize(it) to item }
    }.toMap()

    fun find(input: String): TrackedDropDefinition? = byAlias[normalize(input)]

    private fun normalize(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_").trim('_')
}
