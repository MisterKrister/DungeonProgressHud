package dev.krister.dungeonprogresshud

import com.google.gson.Gson
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionParserTest {
    @Test fun `fractional screenshot XP records a run in live chat and archived chat`() {
        val lines = listOf("§cThe Catacombs §r- §eF7",
            "§aDefeated §cMaxor, Storm, Goldor, and Necron §ain §e04m 54s",
            "§aScore: §6298 §a(§bS§a)    §b+52 Bits",
            "§3+64,832.9 Cata EXP  §3+53,735.4 Mage EXP")
        for (messages in listOf(lines, listOf(lines.joinToString("\\n")))) {
            var state = CompletionState()
            val completions = messages.mapNotNull { line ->
                val result = CompletionParser.accept(state, line, 100_000)
                state = result.state
                result.completion
            }
            val run = completions.single()
            assertEquals("F7", run.metadata.floor)
            assertEquals(294, run.metadata.seconds)
            assertEquals(298, run.metadata.score)
            assertEquals("S", run.metadata.grade)
            assertEquals(64_833, run.xp)
            val history = RunState(runs = mutableListOf(DungeonRunRecord(
                timestamp = run.timestamp, floorLabel = run.metadata.floor,
                rawCataXp = run.xp, accountId = "account", profileId = "profile")))
            val scopedRuns = HistoryQueries.runs(history, "account", "profile").filter { it.timestamp >= 90_000 }
            val xp = HistoryQueries.observedXp(scopedRuns, "F7", 0)
            assertEquals(1, scopedRuns.size)
            assertEquals(64_833, xp.last().second)
            assertEquals(64_833.0, xp.map { it.second }.average())
            assertEquals(2714, kotlin.math.ceil(175_900_000 / xp.map { it.second }.average()).toInt())
        }
    }

    @Test fun `completion hovers provide active and passive XP immediately without double counting a refresh`() {
        // Exact XP amounts and tooltip lines supplied with the M7 bug report.
        val tooltip = Component.literal("§3+726,768 Cata EXP\n§3+605,640 Mage EXP\n§3+151,410 Healer EXP\n")
            .append(Component.literal("§3+151,410 Berserk EXP\n§3+151,410 Tank EXP\n§3+151,410 Archer EXP"))
        val xpLine = Component.literal("§3+726,768 Cata EXP  ").append(
            Component.empty().withStyle { it.withHoverEvent(HoverEvent.ShowText(tooltip)) }
                .append(Component.literal("§3+605,640 ")).append(Component.literal("Mage EXP")))
        val gains = CompletionParser.classExperience(xpLine)
        assertEquals(mapOf("mage" to 605_640L, "healer" to 151_410L, "berserk" to 151_410L,
            "archer" to 151_410L, "tank" to 151_410L), gains)
        assertEquals(mapOf("mage" to 605_640L), CompletionParser.classExperience(Component.literal(xpLine.string)))
        val fractional = CompletionParser.classExperience(Component.literal("+55,611.7 Mage EXP"))
        assertEquals(55_612L, fractional["mage"])

        val run = DungeonRunRecord(timestamp = 1500, floorLabel = "M7", rawCataXp = 726_768,
            dungeonClass = "mage", classExperience = gains, partyClasses = gains.keys, accountId = "a", profileId = "p")
        val state = RunState(lastProfileId = "p", lastPlayerUuid = "a", lastBaselineAt = 1000,
            lastCatacombsXp = 10000, lastClassExperience = gains.mapValues { 100L }, runs = mutableListOf(run))
        val rates = DungeonClasses.xpPerRun(state.classXpSamples, state.runs)
        assertEquals(605_640.0, rates["mage"])
        assertEquals(151_410.0, rates["healer"])
        val progress = DungeonClasses.progress(mapOf("healer" to 183_513_326L, "mage" to 610_813_640L),
            "mage", true, 50, false, rates)
        assertEquals(2552L, progress.single { it.name == "Healer" }.runs)
        assertEquals(0L, progress.single { it.name == "Mage" }.runs)
        DungeonClasses.recordXpSample(state, ProfileData("Player", "a", "Apple", 736_768, "p",
            gains.mapValues { it.value + 100 }), 2000)
        assertEquals(emptyList(), state.classXpSamples)
        assertEquals(rates, DungeonClasses.xpPerRun(state.classXpSamples, state.runs))
        val saved = Gson().fromJson(Gson().toJson(state), RunState::class.java)
        HistoryValidation.validate(saved)
        assertEquals(gains, saved.runs.single().classExperience)
        assertEquals(gains.keys, saved.runs.single().partyClasses)
    }

    @Test fun `class XP arriving after the Cata reward updates that run and supplies passive estimates`() {
        val cataMessage = Component.literal("+707,952 Catacombs Experience")
        val cata = CompletionParser.accept(CompletionState(100_000, "M7", 366, 301, "S+"), cataMessage.string, 100_000).completion!!
        val run = DungeonRunRecord(source = "completion-chat", timestamp = cata.timestamp,
            floorLabel = cata.metadata.floor, rawCataXp = cata.xp, dungeonClass = "mage", accountId = "a", profileId = "p",
            partyClasses = DungeonClasses.names.keys)
        val state = RunState(runs = mutableListOf(run))
        assertFalse(CompletionParser.recordClassExperience(cataMessage, run, 100_000))
        val classMessage = Component.literal("§3+589,960 Mage EXP")
        assertNull(CompletionParser.accept(cata.metadata, classMessage.string, 100_050).completion)
        assertTrue(CompletionParser.recordClassExperience(classMessage, run, 100_050))
        assertEquals(1, state.runs.size)
        assertEquals(589_960L, run.classExperience["mage"])
        for (name in listOf("healer", "berserk", "tank", "archer")) assertEquals(147_490L, run.classExperience[name])
        assertEquals(147_490.0, DungeonClasses.xpPerRun(emptyList(), state.runs)["healer"])
        assertFalse(CompletionParser.recordClassExperience(classMessage, run, 100_100))

        // If exact hover values are present, keep them instead of replacing them with the estimate.
        val withHover = classMessage.copy().withStyle {
            it.withHoverEvent(HoverEvent.ShowText(Component.literal("+147,491 Healer EXP")))
        }
        assertTrue(CompletionParser.recordClassExperience(withHover, run, 100_200))
        assertEquals(147_491L, run.classExperience["healer"])
        assertFalse(CompletionParser.recordClassExperience(classMessage, run, 100_250))
        assertEquals(147_491L, run.classExperience["healer"])
        assertFalse(CompletionParser.recordClassExperience(classMessage, run, 130_001))
        assertFalse(CompletionParser.recordClassExperience(classMessage, run.copy(source = "log-import"), 100_300))
        assertFalse(CompletionParser.recordClassExperience(classMessage,
            HistoryQueries.runs(state, "a", "other-profile").lastOrNull(), 100_300))
        assertFalse(CompletionParser.recordClassExperience(Component.literal("Party > Mage: hello"), run, 100_300))
    }

    @Test fun `M5 without a Tank estimates only participating classes and ignores old unverified passive XP`() {
        val party = setOf("mage", "mage", "berserk", "healer", "archer")
        val run = DungeonRunRecord(source = "completion-chat", timestamp = 100_000, floorLabel = "M5",
            rawCataXp = 162_626, dungeonClass = "mage", partyClasses = party)
        val reward = Component.literal("+162,626.1 Cata EXP  +134,789.2 Mage EXP")
        assertTrue(CompletionParser.recordClassExperience(reward, run, 100_000))
        assertEquals(party, run.classExperience.keys)
        for (name in party - "mage") assertEquals(33_697L, run.classExperience[name])
        assertNull(run.classExperience["tank"])

        // Even a previous run with Tank XP cannot produce a Tank estimate for this party.
        val earlier = run.copy(partyClasses = DungeonClasses.names.keys,
            classExperience = run.classExperience + ("tank" to 33_697L))
        val rates = DungeonClasses.xpPerRun(emptyList(), listOf(earlier, run), party)
        assertEquals(33_697.0, rates["healer"])
        assertNull(rates["tank"])
        assertNull(DungeonClasses.progress(mapOf("tank" to 188_857_998L), "mage", true, 50, false,
            rates).single { it.name == "Tank" }.runs)
        assertEquals(33_697.0, DungeonClasses.xpPerRun(emptyList(), listOf(earlier), earlier.partyClasses)["tank"])

        val fallback = DungeonClasses.xpPerRun(emptyList(), listOf(run.copy(classExperience = emptyMap())))
        assertEquals(162_626 / 4.8, fallback["healer"])
        assertNull(fallback["tank"])
        val legacy = earlier.copy(partyClasses = emptySet())
        assertEquals(mapOf("mage" to 134_789.0), DungeonClasses.xpPerRun(emptyList(), listOf(legacy)))

        val unknown = run.copy(classExperience = emptyMap(), partyClasses = emptySet())
        assertTrue(CompletionParser.recordClassExperience(reward, unknown, 100_000))
        assertEquals(setOf("mage"), unknown.classExperience.keys)
        val hover = reward.copy().withStyle {
            it.withHoverEvent(HoverEvent.ShowText(Component.literal("+33,697.3 Healer EXP")))
        }
        assertTrue(CompletionParser.recordClassExperience(hover, unknown, 100_100))
        assertEquals(setOf("mage", "healer"), unknown.partyClasses)
        assertEquals(33_697.0, DungeonClasses.xpPerRun(emptyList(), listOf(unknown))["healer"])
        assertNull(DungeonClasses.xpPerRun(emptyList(), listOf(unknown))["tank"])
    }

    @Test fun `M7 summary and delayed chat repeats only produce one completion`() {
        val message = "Master Mode - M7\nDefeated Maxor, Storm, Goldor, and Necron in 06m 24s\n" +
            "Score: 309 (S+)    +207 Bits\n+726,768 Cata EXP  +605,640 Mage EXP"
        val first = CompletionParser.accept(CompletionState(), message, 100_000)
        assertEquals("M7", first.completion!!.metadata.floor)
        assertEquals(384, first.completion.metadata.seconds)
        assertEquals(726_768L, first.completion.xp)
        assertNull(CompletionParser.accept(first.state, "$message §8(§7x§r2§8)", 100_000).completion)
        assertNull(CompletionParser.accept(first.state, "$message §8(§7x§r3§8)", 116_000).completion)
        assertEquals(726_768L, CompletionParser.accept(first.state, message, 500_000).completion!!.xp)
        assertEquals(726_768L, CompletionParser.accept(CompletionState(), "+726,768 Catacombs Experience", 100_000).completion!!.xp)
    }

    @Test fun `screenshot F7 completion supplies the floor XP average and remaining runs`() {
        var state = CompletionState()
        var completion: ParsedCompletion? = null
        for (line in listOf("§cThe Catacombs §r- §eF7", "Defeated Maxor, Storm, Goldor, and Necron in 05m 38s",
            "Score: 302 (S+)    +52 Bits", "+66,295 Cata EXP  +55,245.9 Mage EXP")) {
            val result = CompletionParser.accept(state, line, 100_000)
            state = result.state
            completion = result.completion ?: completion
        }
        val run = completion!!
        assertEquals("F7", run.metadata.floor)
        assertEquals(338, run.metadata.seconds)
        val runs = listOf(DungeonRunRecord(floorLabel = "F5", rawCataXp = 201_000),
            DungeonRunRecord(timestamp = 100_000, floorLabel = run.metadata.floor, rawCataXp = run.xp))
        val samples = HistoryQueries.observedXp(runs, run.metadata.floor, 0)
        assertEquals(66_295, samples.single().second)
        assertEquals(2655, kotlin.math.ceil(175_970_000 / samples.map { it.second }.average()).toInt())
        assertEquals(emptyList(), HistoryQueries.observedXp(runs, "M7", 0))
        assertEquals(emptyList(), HistoryQueries.observedXp(runs, "F7", 100_001))
    }

    @Test fun `master floor and completion metadata survive a realistic sequence`() {
        var state = CompletionState()
        for (line in listOf("Master Mode - 7", "Defeated Necron in 5m 12s", "Score: 305 (S+)")) {
            val result = CompletionParser.accept(state, line, 100_000)
            assertNull(result.completion)
            state = result.state
        }
        val run = CompletionParser.accept(state, "+500,000 Cata EXP", 101_000).completion!!
        assertEquals("M7", run.metadata.floor)
        assertEquals(312, run.metadata.seconds)
        assertEquals(305, run.metadata.score)
        assertEquals(500_000, run.xp)
        assertEquals("", CompletionParser.accept(state, "+500,000 Cata EXP", 200_000).completion!!.metadata.floor)
    }

    @Test fun `two completions consume one refresh interval without inventing a third run`() {
        val interval = XpInterval(100, 1000, 1_000_000, "account", "profile")
        assertEquals(0, XpReconciliation.unattributed(interval, listOf(200L to 500_000L, 800L to 500_000L)))
        assertEquals(500_000, XpReconciliation.unattributed(interval, listOf(200L to 500_000L)))
    }
}
