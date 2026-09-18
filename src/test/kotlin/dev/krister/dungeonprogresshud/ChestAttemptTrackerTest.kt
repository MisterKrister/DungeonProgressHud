package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChestAttemptTrackerTest {
    @Test fun `menu activation accepts NoFrills converted clicks and rejects unrelated input`() {
        assertTrue(ChestConfirmation.acceptsClick(0, "PICKUP"))
        assertTrue(ChestConfirmation.acceptsClick(1, "PICKUP"))
        assertTrue(ChestConfirmation.acceptsClick(0, "QUICK_MOVE"))
        assertTrue(ChestConfirmation.acceptsClick(2, "CLONE"))
        assertFalse(ChestConfirmation.acceptsClick(0, "CLONE"))
        assertFalse(ChestConfirmation.acceptsClick(2, "PICKUP"))
        for (input in listOf("SWAP", "THROW", "QUICK_CRAFT", "PICKUP_ALL")) {
            assertFalse(ChestConfirmation.acceptsClick(0, input))
        }
    }

    @Test fun `converted Croesus click and screenshot reward heading produce session profit once`() {
        val tracker = ChestAttemptTracker<ChestProfitCandidate>()
        val candidate = ChestProfitCandidate("Obsidian", 1_000_000, 0, 1, 54,
            listOf(PricedChestItem("ENCHANTMENT_ULTIMATE_SOUL_EATER_1", 1, 1_500_000.0, 1_500_000,
                PriceSource.BAZAAR_BUY, true)), emptyList())
        val state = RunState()
        // NoFrills rewrites the user's left-click to button 2 / CLONE before DPH sees it.
        if (ChestConfirmation.acceptsClick(2, "CLONE")) tracker.begin(12, "run:obsidian", 100, candidate)
        assertEquals(0, state.totalChestsOpened)
        val tier = ChestConfirmation.claimedTier("  OBSIDIAN CHEST REWARDS")!!
        val attempt = tracker.confirmChat(200) { it.chestName.equals(tier, true) }!!
        ChestRecorder.record(state, attempt.value, ClaimContext(attempt.claimId, "a", "p", "Peach", "F7"),
            "server-chat", 200, MissingPriceBehavior.MARK_INCOMPLETE, true)
        val stats = HistoryQueries.profitStats(state, "a", "p", "session", false) { it >= 200 }
        assertEquals(500_000, stats.profit)
        assertEquals(1, stats.chests)
        assertNull(tracker.confirm(12, 201) { true })
        assertNull(tracker.confirmChat(202) { true })
    }

    @Test fun `Kismet chat and inventory confirmation consume the same attempt once in either order`() {
        assertTrue(ChestConfirmation.kismetUsed("You used a Kismet Feather!"))
        assertFalse(ChestConfirmation.kismetUsed("[MVP+] Player: You used a Kismet Feather!"))
        assertFalse(ChestConfirmation.kismetUsed("You do not have a Kismet Feather!"))
        for (chatFirst in listOf(true, false)) {
            val tracker = ChestAttemptTracker<String>()
            tracker.begin(12, "run:obsidian", 100, "Obsidian")
            val confirmation = if (chatFirst) tracker.confirmChat(200) { true } else tracker.confirm(12, 200) { true }
            assertEquals("run:obsidian", confirmation?.claimId)
            assertNull(tracker.confirmChat(201) { true })
            assertNull(tracker.confirm(12, 201) { true })
        }
    }

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
