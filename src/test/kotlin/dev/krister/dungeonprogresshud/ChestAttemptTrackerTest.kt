package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChestAttemptTrackerTest {
    @Test fun `rejection and unrelated inventory do not confirm a purchase`() {
        val tracker = ChestAttemptTracker<String>()
        tracker.begin(12, "run:bedrock", 100, "Bedrock")
        assertNull(tracker.confirm(12, 200) { false })
        assertNull(tracker.confirm(13, 200) { true })
        assertEquals("run:bedrock", tracker.confirm(12, 300) { it == "Bedrock" }?.claimId)
        assertNull(tracker.confirm(12, 301) { true })
    }

    @Test fun `expired or abandoned attempt cannot consume costs`() {
        val tracker = ChestAttemptTracker<Long>()
        tracker.begin(12, "first", 100, 1_000_000)
        assertNull(tracker.confirm(12, 20_000) { true })
        tracker.begin(12, "second", 21_000, 2_000_000)
        tracker.clear()
        assertNull(tracker.confirm(12, 22_000) { true })
    }
    @Test fun `only server reward heading confirms the pending tier even after screen closes`() {
        val tracker = ChestAttemptTracker<String>()
        tracker.begin(12, "run:bedrock", 100, "Bedrock")
        assertNull(ChestConfirmation.claimedTier("You cannot afford this chest!"))
        assertNull(ChestConfirmation.claimedTier("[MVP+] Player: BEDROCK CHEST REWARDS"))
        val tier = ChestConfirmation.claimedTier("   BEDROCK CHEST REWARDS")!!
        assertEquals("run:bedrock", tracker.confirmChat(200) { it.equals(tier, true) }?.claimId)
        assertNull(tracker.confirmChat(201) { true })
    }
}
