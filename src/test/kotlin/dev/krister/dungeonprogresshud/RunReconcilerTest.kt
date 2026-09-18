package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunReconcilerTest {
    @Test fun `identical consecutive runs remain distinct but same completion log reconciles`() {
        val run = DungeonRunRecord(timestamp = 100_500, floorLabel = "M7", rawCataXp = 500_000, runTimeSeconds = 300, score = 305)
        assertTrue(RunReconciler.matches(run, "M7", 500_000, 300, 305, 100_000))
        assertFalse(RunReconciler.matches(run, "M7", 500_000, 300, 305, 400_500))
    }
}
