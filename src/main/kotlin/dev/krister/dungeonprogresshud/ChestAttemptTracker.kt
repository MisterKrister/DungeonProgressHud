package dev.krister.dungeonprogresshud

/** Attempts have no accounting effect. Only a matching server confirmation may consume one. */
internal class ChestAttemptTracker<T>(private val timeoutMillis: Long = 15_000) {
    data class Attempt<T>(val containerId: Int, val claimId: String, val startedAt: Long, val value: T)
    private var pending: Attempt<T>? = null

    fun begin(containerId: Int, claimId: String, now: Long, value: T) {
        if (pending?.claimId == claimId && now - pending!!.startedAt in 0..timeoutMillis) return
        pending = Attempt(containerId, claimId, now, value)
    }

    fun confirm(containerId: Int, now: Long, matches: (T) -> Boolean): Attempt<T>? {
        val attempt = pending ?: return null
        if (now - attempt.startedAt !in 0..timeoutMillis) { clear(); return null }
        if (containerId != attempt.containerId || !matches(attempt.value)) return null
        clear()
        return attempt
    }

    fun clear() { pending = null }

    fun confirmChat(now: Long, matches: (T) -> Boolean): Attempt<T>? =
        pending?.let { confirm(it.containerId, now, matches) }
}

internal object ChestConfirmation {
    private val rewards = "^(BEDROCK|OBSIDIAN|EMERALD|DIAMOND|GOLD|WOOD) CHEST REWARDS$".toRegex()
    fun claimedTier(message: String): String? = rewards.matchEntire(message.trim())?.groupValues?.get(1)
    fun rerolled(lore: List<String>): Boolean = lore.any { it == "You already rerolled a chest!" }
}
