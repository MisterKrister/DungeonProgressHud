package dev.krister.dungeonprogresshud

import com.google.gson.JsonParser
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonProgressTest {
    @Test fun `missing class rewards use the requested Cata XP fallback but measured gains take precedence`() {
        val run = DungeonRunRecord(rawCataXp = 707_952, dungeonClass = "mage", partyClasses = DungeonClasses.names.keys)
        val fallback = DungeonClasses.xpPerRun(emptyList(), listOf(run))
        assertEquals(589_960.0, fallback["mage"])
        assertEquals(147_490.0, fallback["healer"])
        val reported = run.copy(classExperience = mapOf("healer" to 140_000L, "mage" to 560_000L))
        val measured = DungeonClasses.xpPerRun(emptyList(), listOf(run, reported))
        assertEquals(140_000.0, measured["healer"])
        assertEquals(560_000.0, measured["mage"])
        assertTrue(DungeonClasses.xpPerRun(emptyList(), listOf(run.copy(dungeonClass = ""))).isEmpty())
        assertTrue(DungeonClasses.xpPerRun(emptyList(), listOf(run.copy(rawCataXp = 0))).isEmpty())
    }

    @Test fun `run estimates use measured active and passive class gains and round up`() {
        val state = RunState(lastProfileId = "p", lastPlayerUuid = "a", lastBaselineAt = 1000,
            lastCatacombsXp = 10000, lastClassExperience = mapOf("mage" to 1000L, "healer" to 100L, "tank" to 200L),
            runs = mutableListOf(
                DungeonRunRecord(timestamp = 1500, rawCataXp = 500, floorLabel = "M7", dungeonClass = "mage", accountId = "a", profileId = "p"),
                DungeonRunRecord(timestamp = 2000, rawCataXp = 500, floorLabel = "M7", dungeonClass = "mage", accountId = "a", profileId = "p")))
        val profile = ProfileData("Player", "a", "Apple", 11000, "p", mapOf("mage" to 3000L, "healer" to 600L, "tank" to 200L))
        DungeonClasses.recordXpSample(state, profile, 2500)
        val sample = state.classXpSamples.single()
        assertEquals(2, sample.runs)
        assertEquals("mage", sample.dungeonClass)
        assertEquals(mapOf("mage" to 2000L, "healer" to 500L, "tank" to 0L), sample.experience)
        val rates = DungeonClasses.xpPerRun(state.classXpSamples)
        assertEquals(1000.0, rates["mage"])
        assertEquals(250.0, rates["healer"])
        assertEquals(0.0, rates["tank"])
        assertNull(rates["archer"])
        val weighted = DungeonClasses.xpPerRun(listOf(sample, sample.copy(runs = 1, experience = mapOf("mage" to 500L))))
        assertEquals(2500.0 / 3, weighted["mage"])
        assertEquals(250.0, weighted["healer"])

        val progress = DungeonClasses.progress(mapOf("mage" to 610_209_640L, "healer" to 200L,
            "tank" to 0L, "archer" to 569_809_640L), "mage", true, 50, false, rates)
        assertEquals(0L, progress.single { it.name == "Mage" }.runs)
        assertEquals(2_279_238L, progress.single { it.name == "Healer" }.runs)
        assertEquals(0L, progress.single { it.name == "Archer" }.runs)
        assertNull(progress.single { it.name == "Tank" }.runs)
        assertNull(progress.single { it.name == "Berserk" }.runs)
        val next = DungeonClasses.progress(mapOf("healer" to 200L), "mage", true, 50, true, rates)
        assertEquals(1L, next.single { it.name == "Healer" }.runs)
        assertNull(DungeonClasses.progress(mapOf("mage" to 0L), "mage", true, 50, true,
            mapOf("mage" to Double.NaN)).single { it.name == "Mage" }.runs)

        val saved = Gson().fromJson(Gson().toJson(state), RunState::class.java)
        HistoryValidation.validate(saved)
        assertEquals(sample, saved.classXpSamples.single())
        assertEquals(listOf(sample), HistoryQueries.classXp(saved, "a", "p"))
        assertTrue(HistoryQueries.classXp(saved, "a", "other").isEmpty())
    }

    @Test fun `class samples reject mixed floors classes ownership and incomplete profile refreshes`() {
        val run = DungeonRunRecord(timestamp = 1500, rawCataXp = 500, floorLabel = "M7", dungeonClass = "mage", accountId = "a", profileId = "p")
        val profile = ProfileData("Player", "a", "Apple", 11000, "p", mapOf("mage" to 3000L))
        for (second in listOf(run.copy(floorLabel = "F7"), run.copy(dungeonClass = "tank"), run.copy(dungeonClass = ""),
            run.copy(profileId = "other"), run.copy(rawCataXp = 200))) {
            val state = RunState(lastProfileId = "p", lastPlayerUuid = "a", lastBaselineAt = 1000,
                lastCatacombsXp = 10000, lastClassExperience = mapOf("mage" to 1000L), runs = mutableListOf(run, second))
            DungeonClasses.recordXpSample(state, profile, 2500)
            assertTrue(state.classXpSamples.isEmpty())
            assertEquals(profile.classExperience, state.lastClassExperience)
        }
        val old = Gson().fromJson("""{"runs":[{"timestamp":1500,"rawCataXp":500}]}""", RunState::class.java)
        HistoryValidation.validate(old)
        assertEquals("", old.runs.single().dungeonClass)
        assertTrue(old.runs.single().classExperience.isEmpty())
        assertTrue(old.runs.single().partyClasses.isEmpty())
        assertTrue(old.classXpSamples.isEmpty())
        assertTrue(old.lastClassExperience.isEmpty())
        assertEquals("mage", CompletionParser.dungeonClass("§3+55,780 Cata EXP  §3+38,416 Mage EXP"))
        assertEquals("berserk", CompletionParser.dungeonClass("+1,098.7 Berserker EXP"))
        assertNull(CompletionParser.dungeonClass("+55,780 Cata EXP"))
    }

    @Test fun `current class goals start at the current level while all class level 50 uses total XP`() {
        val xp = mapOf("mage" to 200L, "healer" to 0L, "tank" to DungeonLevels.targetXp(50))
        val current = DungeonClasses.progress(xp, "MAGE", false, 3, true).single()
        assertEquals("Mage", current.name)
        assertEquals(2, current.level)
        assertEquals(3, current.target)
        assertEquals(75.0 / 110 * 100, current.percent!!, 0.0001)

        val next = DungeonClasses.progress(xp, "mage", true, 99, true)
        assertEquals(listOf("Healer", "Mage", "Berserk", "Archer", "Tank"), next.map { it.name })
        assertEquals(75.0 / 110 * 100, next[1].percent!!, 0.0001)
        assertEquals(0.0, next[0].percent)
        assertNull(next[2].level)
        assertNull(next[2].percent)
        assertEquals(51, next[4].target)
        assertEquals(0.0, next[4].percent)

        val fifty = DungeonClasses.progress(xp, null, true, 99, false)
        assertTrue(fifty.all { it.target == 50 })
        assertEquals(200.0 / 569809640 * 100, fifty[1].percent!!, 0.0001)
        assertEquals(100.0, fifty[4].percent)
        assertEquals(100.0, DungeonClasses.progress(xp, "mage", false, 1, false).single().percent)
        assertEquals(1, DungeonClasses.progress(xp, "mage", false, -10, false).single().target)
        assertEquals(1000, DungeonClasses.progress(xp, "mage", false, 2000, false).single().target)
        assertNull(DungeonClasses.progress(xp, null, false, 50, false).single().percent)
        assertTrue(DungeonClasses.progress(emptyMap(), null, true, 50, false).all { it.percent == null })
    }

    @Test fun `mage 50 to 51 shows 20 point 2 percent without counting XP from earlier levels`() {
        val xp = mapOf("mage" to 610_209_640L)
        val current = DungeonClasses.progress(xp, "mage", false, 51, false).single()
        assertEquals(50, current.level)
        assertEquals(51, current.target)
        assertEquals(20.2, current.percent!!, 0.0001)
        val next = DungeonClasses.progress(xp, "mage", true, 51, true).single { it.name == "Mage" }
        assertEquals(current.percent, next.percent)
        assertEquals(10.1, DungeonClasses.progress(xp, "mage", false, 52, false).single().percent!!, 0.0001)
        assertEquals(100.0, DungeonClasses.progress(xp, "mage", false, 50, false).single().percent)
        assertEquals(0.0, DungeonClasses.progress(mapOf("mage" to 569_809_640L), "mage", false, 51, false).single().percent)
    }

    @Test fun `shared level curve handles thresholds and virtual levels without regressions`() {
        assertEquals(0, DungeonLevels.level(0))
        assertEquals(0, DungeonLevels.level(49))
        assertEquals(1, DungeonLevels.level(50))
        assertEquals(50.0, DungeonLevels.nextLevelProgress(25))
        assertEquals(49, DungeonLevels.level(569809639))
        assertEquals(50, DungeonLevels.level(569809640))
        assertEquals(769809640, DungeonLevels.targetXp(51))
        assertEquals(52, DungeonLevels.level(993809640))
        assertEquals(12.0, DungeonLevels.nextLevelProgress(993809640))
        assertEquals(100.0, DungeonLevels.progress(Long.MAX_VALUE, 50))
        assertEquals(0.0, DungeonLevels.nextLevelProgress(-1))
        assertTrue(DungeonLevels.nextLevelProgress(Long.MAX_VALUE).isFinite())
    }

    @Test fun `provider class data accepts zero and fractional XP but preserves unavailable values`() {
        assertEquals("berserk", DungeonClasses.key("BERSERKER"))
        assertNull(DungeonClasses.key("unknown"))
        assertEquals(mapOf("mage" to 125L, "healer" to 0L), DungeonClasses.experience(mapOf(
            "Mage" to 125.9, "healer" to 0L, "berserk" to -1, "archer" to Double.NaN,
            "tank" to Double.POSITIVE_INFINITY, "unknown" to 100)))
        val provider = ProfileProvider { }
        val dungeons = JsonParser.parseString("""{"player_classes": {
            "mage": {"experience": 125.9}, "healer": {"experience": 0},
            "berserk": {"experience": -1}, "archer": {}, "tank": null
        }}""").asJsonObject
        assertEquals(mapOf("mage" to 125L, "healer" to 0L), provider.skyBlockerClassExperience(dungeons))
        assertTrue(provider.skyBlockerClassExperience(null).isEmpty())
        assertTrue(provider.skyBlockerClassExperience(JsonParser.parseString("""{"player_classes": null}""").asJsonObject).isEmpty())
    }
}
