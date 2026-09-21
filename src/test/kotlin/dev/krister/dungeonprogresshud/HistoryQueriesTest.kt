package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryQueriesTest {
    @Test fun `relaunch restores profile and last run estimates before a new dungeon without reviving reset or expired samples`() {
        val lastRun = DungeonRunRecord(timestamp = 1_000, floorLabel = "M7", accountId = "a", profileId = "p",
            rawCataXp = 705_600, dungeonClass = "mage", partyClasses = setOf("mage", "healer"),
            classExperience = mapOf("mage" to 588_000L, "healer" to 147_000L))
        val original = RunState(lastBaselineAt = 1_200, lastPlayerUuid = "a", lastProfileId = "p",
            lastCatacombsXp = 1_002_663_150,
            lastClassExperience = mapOf("mage" to 614_717_630L, "healer" to 184_489_324L, "tank" to 189_652_808L),
            runs = mutableListOf(lastRun, lastRun.copy(timestamp = 500, rawCataXp = 100),
                lastRun.copy(timestamp = 1_500, accountId = "other", dungeonClass = "tank")))
        val path = java.nio.file.Files.createTempDirectory("dph-relaunch").resolve("runs.json")
        val gson = com.google.gson.Gson()
        fun repository() = HistoryRepository(path, { json: String ->
            gson.fromJson(json, RunState::class.java).also(HistoryValidation::validate)
        }, { state: RunState -> gson.toJson(state) })
        try {
            val writer = repository()
            writer.load { RunState() }
            assertTrue(writer.save(original))
            val saved = repository().load { RunState() }
            val profile = HistoryQueries.savedProfile(saved, "a", "p", "Player", "Peach")!!
            assertEquals(original.lastCatacombsXp, profile.catacombsExperience)
            assertEquals(original.lastClassExperience, profile.classExperience)
            assertEquals("mage", profile.selectedDungeonClass)
            assertNull(HistoryQueries.savedProfile(saved, "other", "p", "Player", "Peach"))
            assertNull(HistoryQueries.savedProfile(saved, "a", "different", "Player", "Apple"))
            assertNull(HistoryQueries.savedProfile(saved, "", "", "Player", ""))

            val owned = HistoryQueries.runs(saved, "a", "p")
            val restored = HistoryQueries.progressionRuns(owned, "M7", 0, 2_000, true) { false }
            assertEquals(listOf(lastRun), restored)
            assertEquals(listOf(1_000L to 705_600L), HistoryQueries.observedXp(restored, "M7", 0))
            val rates = DungeonClasses.xpPerRun(emptyList(), restored, restored.single().partyClasses)
            assertEquals(147_000.0, rates["healer"])
            val progress = DungeonClasses.progress(profile.classExperience, profile.selectedDungeonClass, true, 50, false, rates)
            assertEquals(2_622L, progress.single { it.name == "Healer" }.runs)
            assertEquals(0L, progress.single { it.name == "Mage" }.runs)
            assertNull(progress.single { it.name == "Tank" }.runs)

            val newRun = lastRun.copy(timestamp = 1_900, rawCataXp = 720_000)
            assertEquals(listOf(newRun), HistoryQueries.progressionRuns(owned + newRun, "M7", 0, 2_000, true) { it >= 1_800 })
            assertTrue(HistoryQueries.progressionRuns(owned, "M7", 1_100, 2_000, true) { false }.isEmpty())
            assertTrue(HistoryQueries.progressionRuns(owned, "M7", 0, 2_000, false) { it >= 1_800 }.isEmpty())
            assertTrue(HistoryQueries.progressionRuns(owned, "F7", 0, 2_000, true) { false }.isEmpty())
            assertTrue(HistoryQueries.progressionRuns(listOf(newRun), "M7", 0, 1_800, true) { true }.isEmpty())
        } finally {
            java.nio.file.Files.deleteIfExists(path)
            java.nio.file.Files.deleteIfExists(path.parent)
        }
    }

    @Test fun `historical run time and rate use the supplied scope and ignore missing timing`() {
        val runs = listOf(DungeonRunRecord(rawCataXp = 1000, runTimeSeconds = 100),
            DungeonRunRecord(rawCataXp = 2000, runTimeSeconds = 200),
            DungeonRunRecord(rawCataXp = 90000, runTimeSeconds = 0))
        assertEquals(300L, HistoryQueries.runTimeSeconds(runs))
        assertEquals(36000L, HistoryQueries.xpPerRunHour(runs))
        assertEquals(100L, HistoryQueries.runTimeSeconds(runs.take(1)))
        assertNull(HistoryQueries.xpPerRunHour(runs.takeLast(1)))
        assertNull(HistoryQueries.xpPerRunHour(emptyList()))
    }

    @Test fun `total restores the saved lifetime remainder without adding it to sessions`() {
        val state = RunState(totalChestProfit = 5_616_579_005L, totalChestsOpened = 922,
            chestProfits = MutableList(250) { ChestProfitSample(timestamp = 1, profit = 2_000_000) }
                .apply { add(ChestProfitSample(timestamp = 2, profit = 18_595_581)) })
        val total = HistoryQueries.profitStats(state, "a", "p", "total", true) { true }
        assertEquals(5_616_579_005L, total.profit)
        assertEquals(922, total.chests)
        assertEquals(5_616_579_005L / 922, total.average)
        assertEquals(18_595_581, HistoryQueries.profitStats(state, "a", "p", "session", false) { it >= 2 }.profit)
        state.chestProfits.add(ChestProfitSample(accountId = "other", profileId = "p", profit = 10))
        state.totalChestsOpened++
        state.totalChestProfit += 10
        assertEquals(total, HistoryQueries.profitStats(state, "a", "p", "total", true) { true })
    }

    @Test fun `truncated losses and complete history are not inflated`() {
        val state = RunState(totalChestProfit = -100, totalChestsOpened = 2,
            chestProfits = mutableListOf(ChestProfitSample(profit = 10)))
        assertEquals(-100, HistoryQueries.profitStats(state, "a", "p", "total", true) { true }.profit)
        state.totalChestsOpened = 1
        assertEquals(10, HistoryQueries.profitStats(state, "a", "p", "total", true) { true }.profit)
    }

    @Test fun `legacy chest profit is visible without assigning ownership or mixing known profiles`() {
        val legacy = ChestProfitSample(timestamp = 10, profit = 61069)
        val state = RunState(chestProfits = mutableListOf(legacy,
            ChestProfitSample(accountId = "a", profileId = "apple", profit = 100),
            ChestProfitSample(accountId = "a", profileId = "pear", profit = 200),
            ChestProfitSample(accountId = "b", profileId = "apple", profit = 300)))
        assertEquals(61169, HistoryQueries.chests(state, "a", "apple").sumOf { it.profit })
        assertEquals(61269, HistoryQueries.chests(state, "a", "pear").sumOf { it.profit })
        assertEquals("", legacy.accountId)
        assertEquals("", legacy.profileId)
        assertEquals(1, HistoryQueries.chests(state, "a", "apple").count { it.timestamp >= 10 })
    }

    @Test fun `same account profiles and unknown legacy ownership remain separate`() {
        val state = RunState(runs = mutableListOf(
            DungeonRunRecord(accountId = "a", profileId = "apple", rawCataXp = 100),
            DungeonRunRecord(accountId = "a", profileId = "pear", rawCataXp = 200),
            DungeonRunRecord(accountId = "b", profileId = "apple", rawCataXp = 300),
            DungeonRunRecord(rawCataXp = 400),
        ))
        assertEquals(100, HistoryQueries.runs(state, "a", "apple").single().rawCataXp)
        assertEquals(200, HistoryQueries.runs(state, "a", "pear").single().rawCataXp)
        assertTrue(HistoryQueries.runs(state, "", "").isEmpty())
        assertEquals(4, state.runs.size)
    }
}
