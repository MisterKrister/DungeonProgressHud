package dev.krister.dungeonprogresshud

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
    private val xp = "\\+([\\d,]+(?:\\.\\d+)?)\\s+Cata EXP".toRegex()
    private val duration = "\\bin\\s+(\\d{1,2})m\\s*(\\d{1,2})s\\b".toRegex(RegexOption.IGNORE_CASE)
    private val score = "Score:\\s*(\\d+)\\s*\\(([A-Z+]+)\\)".toRegex(RegexOption.IGNORE_CASE)

    fun accept(previous: CompletionState, line: String, timestamp: Long): CompletionResult {
        var state = if (timestamp - previous.timestamp !in 0..30_000) CompletionState() else previous
        val text = line.replace(Regex("§."), "").trim().replace(Regex("\\s*\\(x\\d+\\)$"), "")
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
