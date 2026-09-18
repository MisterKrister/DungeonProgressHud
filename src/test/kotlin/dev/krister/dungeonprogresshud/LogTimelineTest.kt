package dev.krister.dungeonprogresshud

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogTimelineTest {
    @Test fun `archive date is stable and midnight advances a day`() {
        val clock = LogTimeline.forArchive("2026-09-04-1.log.gz", ZoneOffset.UTC)!!
        assertEquals(Instant.parse("2026-09-04T23:59:59Z").toEpochMilli(), clock.timestamp("[23:59:59] [CHAT] Score: 300 (S+)"))
        assertEquals(Instant.parse("2026-09-05T00:00:01Z").toEpochMilli(), clock.timestamp("[00:00:01] [CHAT] +500,000 Cata EXP"))
        assertNull(LogTimeline.forArchive("latest.log"))
    }
}
