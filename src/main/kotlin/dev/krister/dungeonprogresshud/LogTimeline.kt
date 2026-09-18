package dev.krister.dungeonprogresshud

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Archived Minecraft logs encode their session start date in their filename. */
internal class LogTimeline(private var date: LocalDate, private val zone: ZoneId) {
    private var previous: LocalTime? = null
    fun timestamp(line: String): Long? {
        val match = Regex("^\\[(\\d{2}:\\d{2}:\\d{2})]").find(line) ?: return null
        val time = runCatching { LocalTime.parse(match.groupValues[1]) }.getOrNull() ?: return null
        if (previous != null && time < previous && previous!!.hour >= 18 && time.hour <= 6) date = date.plusDays(1)
        previous = time
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        fun forArchive(name: String, zone: ZoneId = ZoneId.systemDefault()): LogTimeline? {
            val date = Regex("^(\\d{4}-\\d{2}-\\d{2})-").find(name)?.groupValues?.get(1) ?: return null
            return runCatching { LogTimeline(LocalDate.parse(date), zone) }.getOrNull()
        }
    }
}
