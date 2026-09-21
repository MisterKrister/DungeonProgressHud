package dev.krister.dungeonprogresshud

import kotlin.math.roundToLong

internal class DungeonSessionTracker(
    private val inactivityGraceMillis: Long = 5 * 60 * 1000L,
) {
    var startedAt: Long = 0L
        private set
    var isRunning: Boolean = false
        private set
    var isInDungeon: Boolean = false
        private set

    private var elapsedMillis = 0L
    private var lastAccountedAt = 0L
    private var leftDungeonAt: Long? = null

    fun observeDungeon(active: Boolean, now: Long) {
        accountUntil(now)

        if (active) {
            if (startedAt <= 0L) startedAt = now
            if (!isRunning) {
                isRunning = true
                lastAccountedAt = now
            }
            isInDungeon = true
            leftDungeonAt = null
        } else if (isInDungeon) {
            isInDungeon = false
            leftDungeonAt = now
        }
    }

    /** Starts from an inferred run start only when dungeon detection did not start the session first. */
    fun startOrResume(inferredStartedAt: Long, now: Long) {
        if (startedAt <= 0L) {
            startedAt = inferredStartedAt.coerceIn(1L, now)
            elapsedMillis = (now - startedAt).coerceAtLeast(0L)
            lastAccountedAt = now
            isRunning = true
            isInDungeon = true
            leftDungeonAt = null
            return
        }
        observeDungeon(true, now)
    }

    fun pause(now: Long) {
        accountUntil(now)
        isRunning = false
        isInDungeon = false
        leftDungeonAt = null
    }

    fun elapsedMillis(now: Long): Long {
        accountUntil(now)
        return elapsedMillis
    }

    private fun accountUntil(now: Long) {
        if (!isRunning || now <= lastAccountedAt) return
        val deadline = leftDungeonAt?.plus(inactivityGraceMillis)
        val activeUntil = deadline?.coerceAtMost(now) ?: now
        if (activeUntil > lastAccountedAt) {
            elapsedMillis += activeUntil - lastAccountedAt
            lastAccountedAt = activeUntil
        }
        if (deadline != null && now >= deadline) {
            isRunning = false
        }
    }
}

internal object DungeonActivityDetector {
    private val shortFloorRegex = "^([FM])\\s*([1-7])$".toRegex(RegexOption.IGNORE_CASE)
    private val dungeonFloorRegex = ("(?:master\\s+(?:mode\\s+)?(?:the\\s+)?catacombs|master\\s+mode|(?:the\\s+)?catacombs)" +
        "\\s*[-,:(]*\\s*(?:\\[MM]\\s*)?(?:floor\\s*)?([FM]?[1-7]|VII|VI|IV|V|III|II|I)\\b").toRegex(RegexOption.IGNORE_CASE)

    fun isActiveDungeon(area: String?, subarea: String?, scoreboard: String, tabList: List<String>): Boolean {
        val location = listOfNotNull(area, subarea).joinToString("\n").lowercase()
        val board = scoreboard.lowercase()
        val tab = tabList.joinToString("\n").lowercase()

        if (board.contains("the catacombs") || board.contains("master catacombs")) return true
        if (!location.contains("dungeon hub") &&
            (location.contains("the catacombs") || location.contains("master catacombs"))
        ) return true

        val dungeonHudMarkers = listOf("cleared:", "starting in:", "dungeon starts in", "secrets found:")
        if (dungeonHudMarkers.any(board::contains)) return true
        if (location.contains("dungeon hub") || board.contains("dungeon hub")) return false

        val dungeonTabMarkers = listOf("dungeon stats", "secrets found", "crypts:", "deaths:", "puzzles:")
        return dungeonTabMarkers.count(tab::contains) >= 2
    }

    fun detectFloor(area: String?, subarea: String?, scoreboard: String, tabList: List<String>): String? {
        // Network HUD data updates before location metadata during world transitions, so prefer it.
        detectFloorInText(scoreboard)?.let { return it }
        if (scoreboard.contains("dungeon hub", ignoreCase = true)) return null
        val tabAreas = tabList.filter { it.replace(Regex("§."), "").trim().startsWith("Area:", ignoreCase = true) }
        tabAreas.firstNotNullOfOrNull(::detectFloorInText)?.let { return it }
        if (tabAreas.isNotEmpty() && tabAreas.none { isActiveDungeon(it, null, "", emptyList()) }) return null
        // Outside dungeons, the RNG meter also shows "Catacombs Floor VII"; only trust the tab's area.
        val floorTab = if (isActiveDungeon(area, subarea, scoreboard, tabList)) tabList
            else emptyList()
        return listOf(floorTab.joinToString("\n"), subarea.orEmpty(), area.orEmpty())
            .firstNotNullOfOrNull(::detectFloorInText)
    }

    private fun detectFloorInText(text: String): String? {
        val clean = text.replace(Regex("§."), "").replace('\u00a0', ' ')
        dungeonFloorRegex.find(clean)?.let {
            val value = it.groupValues[1].uppercase()
            if (value.startsWith("M") || value.startsWith("F")) return value
            return romanFloor(if (it.value.startsWith("master", true) || it.value.contains("[MM]", true)) "M" else "F", value)
        }
        shortFloorRegex.matchEntire(clean.trim())?.let { return "${it.groupValues[1].uppercase()}${it.groupValues[2]}" }
        return null
    }

    private fun romanFloor(prefix: String, value: String): String? {
        val number = value.toIntOrNull() ?: when (value.uppercase()) {
            "I" -> 1
            "II" -> 2
            "III" -> 3
            "IV" -> 4
            "V" -> 5
            "VI" -> 6
            "VII" -> 7
            else -> return null
        }
        return "$prefix$number"
    }
}

internal object SessionRates {
    fun xpPerHour(completedRunXp: Iterable<Long>, activeMillis: Long): Long? {
        val recordedXp = completedRunXp.filter { it > 0L }
        if (recordedXp.none() || activeMillis <= 0L) return null
        val totalXp = recordedXp.sum()
        return (totalXp.toDouble() * 3_600_000.0 / activeMillis.toDouble()).roundToLong()
    }
}
