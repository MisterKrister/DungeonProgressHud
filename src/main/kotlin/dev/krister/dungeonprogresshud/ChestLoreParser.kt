package dev.krister.dungeonprogresshud

internal data class ChestLoreResult<T>(val rewards: List<T>, val unknownRewards: List<String>,
    val coinCost: Long?, val requiresKey: Boolean, val error: String?)

internal object ChestLoreParser {
    fun coinCost(lore: List<String>): Long? {
        val index = lore.indexOf("Cost")
        if (index < 0) return null
        val line = lore.drop(index + 1).firstOrNull { it.isNotBlank() } ?: return null
        if (line.equals("FREE", true) || line.equals("FREE!", true)) return 0
        return Regex("^(\\d[\\d,]*) Coins$").matchEntire(line)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    }

    fun <T> parse(lore: List<String>, identify: (String) -> T?): ChestLoreResult<T> {
        val cost = coinCost(lore)
        val start = lore.indexOf("Contents")
        val end = lore.indexOf("Cost")
        val error = when {
            "Already opened!" in lore -> "Chest already opened"
            start < 0 || end <= start -> "Reward section missing"
            cost == null -> "Chest cost unreadable"
            else -> null
        }
        val rewards = mutableListOf<T>()
        val unknown = mutableListOf<String>()
        if (start >= 0 && end > start) {
            lore.subList(start + 1, end).filter { it.isNotBlank() }.forEach { line ->
                val reward = identify(line)
                if (reward == null) unknown.add(line) else rewards.add(reward)
            }
        }
        return ChestLoreResult(rewards, unknown, cost, end >= 0 && "Dungeon Chest Key" in lore.drop(end + 1), error)
    }
}
