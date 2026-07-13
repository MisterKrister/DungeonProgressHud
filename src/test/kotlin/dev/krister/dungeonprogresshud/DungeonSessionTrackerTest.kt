package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonSessionTrackerTest {
    private val start = 1_000_000L
    private val minute = 60_000L

    @Test
    fun `dungeon entry starts session automatically`() {
        val tracker = DungeonSessionTracker()

        tracker.observeDungeon(false, start - minute)
        tracker.observeDungeon(true, start)

        assertEquals(start, tracker.startedAt)
        assertTrue(tracker.isRunning)
        assertEquals(2 * minute, tracker.elapsedMillis(start + 2 * minute))
    }

    @Test
    fun `transition between runs inside grace keeps one session`() {
        val tracker = DungeonSessionTracker()
        tracker.observeDungeon(true, start)
        tracker.observeDungeon(false, start + 6 * minute)
        tracker.observeDungeon(true, start + 9 * minute)

        assertEquals(start, tracker.startedAt)
        assertTrue(tracker.isRunning)
        assertEquals(10 * minute, tracker.elapsedMillis(start + 10 * minute))
    }

    @Test
    fun `five minute inactivity grace is active then pauses`() {
        val tracker = DungeonSessionTracker()
        tracker.observeDungeon(true, start)
        tracker.observeDungeon(false, start + minute)

        assertEquals(5 * minute, tracker.elapsedMillis(start + 5 * minute))
        assertTrue(tracker.isRunning)

        assertEquals(6 * minute, tracker.elapsedMillis(start + 6 * minute))
        assertFalse(tracker.isRunning)
        assertEquals(6 * minute, tracker.elapsedMillis(start + 20 * minute))
    }

    @Test
    fun `later dungeon entry resumes accumulated session without inactive gap`() {
        val tracker = DungeonSessionTracker()
        tracker.observeDungeon(true, start)
        tracker.observeDungeon(false, start + minute)
        tracker.elapsedMillis(start + 10 * minute)

        tracker.observeDungeon(true, start + 20 * minute)

        assertEquals(start, tracker.startedAt)
        assertTrue(tracker.isRunning)
        assertEquals(8 * minute, tracker.elapsedMillis(start + 22 * minute))
    }

    @Test
    fun `xp per hour is hidden until xp exists and decreases with active time`() {
        assertNull(SessionRates.xpPerHour(emptyList(), 6 * minute))
        assertEquals(8_000_000L, SessionRates.xpPerHour(listOf(800_000L), 6 * minute))
        assertEquals(5_333_333L, SessionRates.xpPerHour(listOf(800_000L), 9 * minute))
    }

    @Test
    fun `paused time does not lower xp per hour`() {
        val tracker = DungeonSessionTracker()
        tracker.observeDungeon(true, start)
        tracker.observeDungeon(false, start + minute)
        tracker.elapsedMillis(start + 6 * minute)

        val atPause = SessionRates.xpPerHour(listOf(800_000L), tracker.elapsedMillis(start + 6 * minute))
        val muchLater = SessionRates.xpPerHour(listOf(800_000L), tracker.elapsedMillis(start + 60 * minute))

        assertEquals(atPause, muchLater)
    }
}

class DungeonActivityDetectorTest {
    @Test
    fun `catacombs scoreboard or tab markers detect active dungeon`() {
        assertTrue(DungeonActivityDetector.isActiveDungeon(null, null, "The Catacombs (M7)", emptyList()))
        assertTrue(
            DungeonActivityDetector.isActiveDungeon(
                null,
                null,
                "SKYBLOCK",
                listOf("Dungeon Stats", "Secrets Found: 12", "Crypts: 3"),
            )
        )
    }

    @Test
    fun `dungeon hub is not an active dungeon session`() {
        assertFalse(
            DungeonActivityDetector.isActiveDungeon(
                "Dungeon Hub",
                "Dungeon Hub",
                "SKYBLOCK DUNGEON HUB",
                listOf("Dungeon Stats", "Secrets Found: 12"),
            )
        )
    }

    @Test
    fun `detects normal and master floors from live indicators`() {
        assertEquals(
            "M7",
            DungeonActivityDetector.detectFloor(null, null, "The Catacombs (M7)", emptyList()),
        )
        assertEquals(
            "F6",
            DungeonActivityDetector.detectFloor("The Catacombs", "Catacombs - Floor VI", "", emptyList()),
        )
        assertEquals(
            "M3",
            DungeonActivityDetector.detectFloor(null, null, "", listOf("Area: Master Catacombs - Floor III")),
        )
    }

    @Test
    fun `does not invent a floor in dungeon hub`() {
        assertNull(DungeonActivityDetector.detectFloor("Dungeon Hub", "Dungeon Hub", "SKYBLOCK", emptyList()))
    }
}
