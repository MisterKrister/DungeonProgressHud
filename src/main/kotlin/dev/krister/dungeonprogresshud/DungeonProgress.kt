package dev.krister.dungeonprogresshud

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** Catacombs and classes share an XP curve, including virtual levels above 50. */
internal object DungeonLevels {
    private val cumulativeXp = longArrayOf(
        0L, 50L, 125L, 235L, 395L, 625L, 955L, 1425L, 2095L, 3045L, 4385L,
        6275L, 8940L, 12700L, 17960L, 25340L, 35640L, 50040L, 70040L, 97640L, 135640L,
        188140L, 259640L, 356640L, 488640L, 668640L, 911640L, 1239640L, 1683640L, 2284640L, 3084640L,
        4149640L, 5559640L, 7459640L, 9959640L, 13259640L, 17559640L, 23159640L, 30359640L, 39559640L, 51559640L,
        66559640L, 85559640L, 109559640L, 139559640L, 177559640L, 225559640L, 285559640L, 360559640L, 453559640L, 569809640L,
    )
    private const val POST_50_XP = 200_000_000L

    fun targetXp(level: Int): Long = if (level <= 50) cumulativeXp[level.coerceAtLeast(0)]
        else cumulativeXp.last() + (level - 50).toLong() * POST_50_XP

    fun level(xp: Long): Int = if (xp < cumulativeXp.last()) cumulativeXp.indexOfLast { xp >= it }.coerceAtLeast(0)
        else 50 + ((xp - cumulativeXp.last()) / POST_50_XP).coerceAtMost((Int.MAX_VALUE - 51).toLong()).toInt()

    fun progress(xp: Long, target: Int, fromLevel: Int = 0): Double {
        val start = targetXp(fromLevel)
        val end = targetXp(target)
        if (end <= start) return if (xp >= end) 100.0 else 0.0
        return ((xp.coerceAtLeast(0).toDouble() - start) / (end - start) * 100.0).coerceIn(0.0, 100.0)
    }

    fun nextLevelProgress(xp: Long): Double = level(xp).let { progress(xp, it + 1, it) }
}

internal data class ClassProgress(val name: String, val level: Int?, val target: Int, val percent: Double?, val runs: Long? = null)

internal object DungeonClasses {
    val names = linkedMapOf("healer" to "Healer", "mage" to "Mage", "berserk" to "Berserk", "archer" to "Archer", "tank" to "Tank")

    fun key(name: String?): String? = name?.lowercase(Locale.ROOT)?.let {
        (if (it == "berserker") "berserk" else it).takeIf(names::containsKey)
    }

    fun experience(values: Map<*, *>?): Map<String, Long> = values.orEmpty().mapNotNull { (name, value) ->
        val key = key(name as? String) ?: return@mapNotNull null
        val xp = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        key to xp.toLong()
    }.toMap()

    private fun participated(run: DungeonRunRecord, name: String): Boolean =
        name == key(run.dungeonClass) || name in run.partyClasses

    /** Pair profile gains only with a complete set of runs on one floor using one class. */
    fun recordXpSample(state: RunState, profile: ProfileData, now: Long) {
        val previous = state.lastClassExperience
        state.lastClassExperience = profile.classExperience
        val profileId = profile.profileId.replace("-", "")
        if (profileId.isBlank() || state.lastProfileId != profileId || state.lastPlayerUuid != profile.playerUuid) return
        val runs = HistoryQueries.runs(state, profile.playerUuid, profileId)
            .filter { it.timestamp > state.lastBaselineAt && it.timestamp <= now }
        val first = runs.firstOrNull() ?: return
        if (first.floorLabel == "N/A" || first.floorLabel.isBlank() || key(first.dungeonClass) == null ||
            runs.any { it.floorLabel != first.floorLabel || it.dungeonClass != first.dungeonClass }) return
        val cataDelta = profile.catacombsExperience - state.lastCatacombsXp
        // A cached/partial API response or a missed completion must not distort XP per run.
        if (cataDelta <= 0 || abs(cataDelta - runs.sumOf { it.rawCataXp }) > runs.size) return
        val gains = profile.classExperience.mapNotNull { (name, xp) ->
            // Server tooltips already supply these measurements; do not count them twice.
            if (runs.any { participated(it, name) && name in it.classExperience }) return@mapNotNull null
            previous[name]?.let { old -> (xp - old).takeIf { it >= 0 }?.let { name to it } }
        }.toMap()
        if (gains.isNotEmpty()) state.classXpSamples.add(ClassXpSample(now, profile.playerUuid, profileId,
            first.floorLabel, first.dungeonClass, runs.size, gains))
    }

    fun xpPerRun(samples: List<ClassXpSample>, runs: List<DungeonRunRecord> = emptyList(),
                 partyClasses: Set<String> = emptySet()): Map<String, Double> = names.keys.mapNotNull { name ->
        if (partyClasses.isNotEmpty() && name !in partyClasses) return@mapNotNull null
        val measured = samples.filter { it.runs > 0 && (it.experience[name] ?: -1) >= 0 }
        // Older records guessed XP for every class without recording who actually played.
        val participatingRuns = runs.filter { participated(it, name) }
        val recorded = participatingRuns.mapNotNull { it.classExperience[name]?.takeIf { xp -> xp >= 0 } }
        val count = measured.sumOf { it.runs.toLong() } + recorded.size
        val rate = if (count > 0L) (measured.sumOf { it.experience.getValue(name).toDouble() } + recorded.sumOf { it.toDouble() }) / count
        else participatingRuns.filter { it.rawCataXp > 0 && key(it.dungeonClass) != null }.takeIf { it.isNotEmpty() }?.map {
            // Fallback estimate for runs without captured class XP. Reported gains take precedence.
            it.rawCataXp / if (key(it.dungeonClass) == name) 1.2 else 4.8
        }?.average()
        rate?.let { name to it }
    }.toMap()

    fun progress(experience: Map<String, Long>, selected: String?, all: Boolean, target: Int, next: Boolean,
                 xpPerRun: Map<String, Double> = emptyMap()): List<ClassProgress> =
        (if (all) names.keys.toList() else listOf(key(selected))).map { key ->
            val xp = experience[key]
            val level = xp?.let(DungeonLevels::level)
            val nextLevel = all && next
            val goal = if (nextLevel) (level ?: 0) + 1 else if (all) 50 else target.coerceIn(1, 1_000)
            ClassProgress(names[key] ?: "Class", level, goal, xp?.let {
                DungeonLevels.progress(it, goal, if (nextLevel || !all) level ?: 0 else 0)
            }, xp?.let {
                val remaining = (DungeonLevels.targetXp(goal) - it).coerceAtLeast(0)
                if (remaining == 0L) 0L else xpPerRun[key]?.takeIf { rate -> rate.isFinite() && rate > 0 }
                    ?.let { rate -> ceil(remaining / rate).toLong() }
            })
        }
}
