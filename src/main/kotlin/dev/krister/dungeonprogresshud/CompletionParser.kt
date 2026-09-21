package dev.krister.dungeonprogresshud

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import kotlin.math.roundToLong

internal data class CompletionState(
    val timestamp: Long = 0,
    val floor: String = "",
    val seconds: Int = 0,
    val score: Int = 0,
    val grade: String = "",
)

internal data class ParsedCompletion(val timestamp: Long, val metadata: CompletionState, val xp: Long)
internal data class CompletionResult(val state: CompletionState, val completion: ParsedCompletion?)

internal object CompletionParser {
    private val xp = "\\+([\\d,]+(?:\\.\\d+)?)\\s+(?:Cata|Catacombs) (?:EXP|Experience)\\b".toRegex(RegexOption.IGNORE_CASE)
    private const val CLASS_NAME = "(Healer|Mage|Berserk(?:er)?|Archer|Tank)"
    private const val AMOUNT = "([\\d,]+(?:\\.\\d+)?)"
    private val classXp = "\\+$AMOUNT\\s+$CLASS_NAME\\s+(?:EXP|Experience)\\b".toRegex(RegexOption.IGNORE_CASE)
    private val repeatedMessage = "\\s*\\(x\\d+\\)$".toRegex()
    private val duration = "\\bin\\s+(\\d{1,2})m\\s*(\\d{1,2})s\\b".toRegex(RegexOption.IGNORE_CASE)
    private val score = "Score:\\s*(\\d+)\\s*\\(([A-Z+]+)\\)".toRegex(RegexOption.IGNORE_CASE)

    fun dungeonClass(line: String): String? = DungeonClasses.key(classXp.find(line.replace(Regex("§."), ""))?.groupValues?.get(2))

    fun classExperience(message: Component): Map<String, Long> = buildMap {
        // Flat components retain inherited styles, including hovers on parent components.
        val hovers = message.toFlatList().mapNotNull { (it.style.hoverEvent as? HoverEvent.ShowText)?.value()?.string }.distinct()
        for (text in hovers + message.string) {
            val clean = text.replace(Regex("§."), "")
            fun record(name: String, amount: String) {
                val key = DungeonClasses.key(name) ?: return
                val value = amount.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return
                put(key, value.roundToLong())
            }
            classXp.findAll(clean).forEach { record(it.groupValues[2], it.groupValues[1]) }
        }
    }

    fun recordClassExperience(message: Component, run: DungeonRunRecord?, timestamp: Long): Boolean {
        if (run == null || run.source != "completion-chat" || timestamp - run.timestamp !in 0..30_000) return false
        val playedClass = run.dungeonClass.ifBlank { dungeonClass(message.string).orEmpty() }
        val gains = classExperience(message)
        if (gains.isEmpty()) return false
        // An explicit reward also confirms participation when the tab roster was unavailable.
        val partyClasses = run.partyClasses + gains.keys
        val merged = (run.classExperience + gains).toMutableMap()
        // Preserve exact hover values; fill missing passive gains from the played class.
        merged[playedClass]?.let { activeXp ->
            partyClasses.forEach { merged.putIfAbsent(it, (activeXp / 4.0).roundToLong()) }
        }
        if (merged == run.classExperience && partyClasses == run.partyClasses) return false
        run.classExperience = merged
        run.dungeonClass = playedClass
        run.partyClasses = partyClasses
        return true
    }

    fun accept(previous: CompletionState, line: String, timestamp: Long): CompletionResult {
        var state = if (timestamp - previous.timestamp !in 0..30_000) CompletionState() else previous
        val text = line.replace(Regex("§."), "").trim()
        // Chat-display repeats in archived logs are not additional server completions.
        if (repeatedMessage.containsMatchIn(text)) return CompletionResult(state, null)
        DungeonActivityDetector.detectFloor(null, null, text, emptyList())?.let {
            state = state.copy(timestamp = timestamp, floor = it)
        }
        if (text.contains("Defeated", true)) duration.find(text)?.let {
            state = state.copy(timestamp = timestamp, seconds = it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt())
        }
        score.find(text)?.let {
            state = state.copy(timestamp = timestamp, score = it.groupValues[1].toInt(), grade = it.groupValues[2])
        }
        val gained = xp.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }?.roundToLong()
        return CompletionResult(state, gained?.takeIf { it > 0 }?.let { ParsedCompletion(timestamp, state, it) })
    }
}

data class XpInterval(val start: Long, val end: Long, val delta: Long, val accountId: String, val profileId: String)

internal object XpReconciliation {
    fun unattributed(interval: XpInterval, completions: List<Pair<Long, Long>>): Long =
        (interval.delta - completions.filter { it.first > interval.start && it.first <= interval.end }.sumOf { it.second })
            .coerceAtLeast(0)
}
