package dev.krister.dungeonprogresshud

import java.util.Locale

/** Shared by reward stacks and Croesus lore; identity never depends on a loaded price cache. */
internal object DungeonRewardParser {
    private val quantity = Regex("^(.+?)\\s+[x×]\\s*([\\d,]+)$", RegexOption.IGNORE_CASE)
    private val essence = Regex("^(\\w+) Essence$", RegexOption.IGNORE_CASE)
    private val book = Regex("^Enchanted Book \\((.+) ([IVXL]+|\\d+)\\)$", RegexOption.IGNORE_CASE)
    private val enchant = Regex("^([A-Za-z][A-Za-z '-]+) ([IVXL]+|\\d+)$")
    private val ultimateNames = setOf("BANK", "COMBO", "LAST_STAND", "NO_PAIN_NO_GAIN", "REND",
        "SOUL_EATER", "SWARM", "WISDOM", "ONE_FOR_ALL", "DUPLEX", "FATAL_TEMPO", "INFERNO", "CHIMERA", "HABANERO", "REFRIGERATE")

    fun parse(name: String, count: Int = 1, skyBlockId: String? = null, lore: List<String> = emptyList()): ParsedChestReward? {
        val clean = name.replace(Regex("§."), "").trim().removePrefix("- ").trim()
        val amountMatch = quantity.matchEntire(clean)
        val amount = if (amountMatch == null) count else amountMatch.groupValues[2].replace(",", "").toIntOrNull() ?: return null
        if (amount <= 0) return null
        val base = amountMatch?.groupValues?.get(1) ?: clean
        essence.matchEntire(base)?.let {
            return ParsedChestReward("ESSENCE_${it.groupValues[1].uppercase(Locale.ROOT)}", amount, true)
        }
        // NBT knows the actual enchantment (including ultimate_*) and armor/item variant.
        if (!skyBlockId.isNullOrBlank() && skyBlockId != "ENCHANTED_BOOK") {
            return ParsedChestReward(normalizeSkyBlockId(skyBlockId), amount)
        }
        val match = book.matchEntire(base) ?: if (base.equals("Enchanted Book", true)) {
            lore.firstNotNullOfOrNull { enchant.matchEntire(it.replace(Regex("§."), "").trim()) }
        } else null
        if (match != null) {
            var id = match.groupValues[1].uppercase(Locale.ROOT).replace(' ', '_')
            if (id in ultimateNames) id = "ULTIMATE_$id"
            val tier = romanNumber(match.groupValues[2]) ?: return null
            return ParsedChestReward("ENCHANTMENT_${id}_$tier", amount)
        }
        if (base.isBlank() || base.equals("Enchanted Book", true)) return null
        return ParsedChestReward(normalizeSkyBlockId(base.replace("'", "").replace(' ', '_')), amount)
    }

    private fun romanNumber(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }
        ?: listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
            .indexOf(value.uppercase(Locale.ROOT)).takeIf { it >= 0 }?.plus(1)
}
