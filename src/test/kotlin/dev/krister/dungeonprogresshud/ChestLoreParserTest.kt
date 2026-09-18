package dev.krister.dungeonprogresshud

import kotlin.test.*

class ChestLoreParserTest {
    @Test fun `cost and instructions are excluded while unknown rewards remain explicit`() {
        val result = ChestLoreParser.parse(listOf("Contents", "Recombobulator 3000", "Mystery Reward", "", "Cost",
            "1,000,000 Coins", "Dungeon Chest Key", "Click to open!")) { if (it == "Recombobulator 3000") "RECOMBOBULATOR_3000" else null }
        assertEquals(listOf("RECOMBOBULATOR_3000"), result.rewards)
        assertEquals(listOf("Mystery Reward"), result.unknownRewards)
        assertEquals(1_000_000, result.coinCost)
        assertTrue(result.requiresKey)
        assertNull(result.error)
    }

    @Test fun `unknown cost is never free`() {
        assertNull(ChestLoreParser.coinCost(listOf("Cost", "Please wait...")))
        assertEquals(0, ChestLoreParser.coinCost(listOf("Cost", "FREE")))
    }
}
