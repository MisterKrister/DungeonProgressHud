package dev.krister.dungeonprogresshud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudGeometryTest {
    @Test
    fun `class layouts fit their editor bounds and every section shows its selected range`() {
        val top = listOf("sessionTime", "currentLevel", "target", "levelProgress", "classProgress", "currentXp")
        val bottom = listOf("profit", "avgChest")
        for (scope in listOf("session", "total", "last 1 day", "last 7 days", "last 4 days", "last 30 days")) {
            val profitScope = if (scope == "session") "" else scope.uppercase()
            for (all in listOf(false, true)) {
                val layout = HudGeometry.measure(80, 60, 130, 18, top, bottom, false, scope, all)
                assertEquals(scope.uppercase(), layout.sections().first().title())
                assertEquals(profitScope, layout.sections().last().scope())
                val block = layout.rows().single { it.id() == "classProgress" }
                assertEquals(if (all) 76 else 60, block.height())
                assertEquals(block.bottom(), layout.rows().single { it.id() == "currentXp" }.y())
                assertEquals(block, layout.rows().single { it.contains(block.x() + 1.0, block.bottom() - 1.0) })
                assertTrue(layout.rows().all { it.bottom() < layout.height() })
            }
            val items = HudGeometry.measure(80, 60, 130, 18, bottom, listOf("itemHandle"), true, scope, false)
            assertEquals(profitScope, items.sections().first().scope())
            assertEquals(scope.uppercase(), items.sections().last().scope())
        }
        val previous = HudGeometry.DEFAULT_ORDER.filterNot { it == "classProgress" }
        assertEquals(HudGeometry.DEFAULT_ORDER, HudGeometry.normalizeOrder(previous))
        val moved = HudGeometry.move(top, "classProgress", "currentLevel")
        assertEquals(listOf("classProgress", "currentLevel", "target"), moved.drop(1).take(3))
    }

    @Test
    fun `section layout and editor hit areas stay aligned after hiding and dragging rows`() {
        val top = listOf("sessionTime", "observedCount", "lastRun", "currentLevel", "target",
            "levelProgress", "currentXp", "remaining", "runsLeft", "xpPerRun", "xpPerHour", "floor")
        val bottom = listOf("profit", "avgChest", "kismets", "croesus")
        fun layout(ids: List<String>, items: Boolean = false) =
            HudGeometry.measure(80, 60, 130, 18, ids, bottom, items)
        val reference = layout(top)
        assertEquals(listOf("SESSION", "CATACOMBS", "PROFIT"), reference.sections().map { it.title() })
        assertEquals(top.filterNot { it == "floor" } + bottom, reference.rows().map { it.id() })
        assertEquals(2, reference.dividers().size)
        val levels = reference.rows().filter { HudGeometry.isLevel(it.id()) }
        assertEquals(levels[0].y(), levels[1].y())
        assertTrue(levels[0].right() < levels[1].x())
        assertEquals(HudGeometry.SIDE_PADDING, levels[0].x())
        assertEquals(reference.right(), levels[1].right())
        for (row in reference.rows()) {
            // The same local rectangles drive drawing and translated/scaled inventory hit testing.
            val screenX = 73 + (row.x() + 1) * 1.75
            val screenY = 41 + (row.y() + 1) * 1.75
            assertEquals(row, reference.rows().single { it.contains((screenX - 73) / 1.75, (screenY - 41) / 1.75) })
            assertFalse(row.contains(row.right().toDouble(), row.bottom().toDouble()))
            assertTrue(row.bottom() < reference.height())
        }
        for (hidden in listOf(setOf("target"), setOf("currentLevel"), setOf("levelProgress"),
            setOf("currentLevel", "target", "levelProgress"))) {
            val reduced = layout(top.filterNot { it in hidden })
            assertTrue(reduced.rows().none { it.id() in hidden })
            assertTrue(reduced.height() <= reference.height())
        }
        val reordered = HudGeometry.move(top, "currentLevel", "remaining")
        assertEquals(listOf("currentLevel", "target"), reordered.drop(reordered.indexOf("remaining") + 1).take(2))
        assertTrue(layout(reordered).rows().first { it.id() == "remaining" }.bottom() <=
            layout(reordered).rows().first { it.id() == "currentLevel" }.y())
        assertEquals(listOf("target", "currentLevel"), layout(HudGeometry.move(top, "currentLevel", "target"))
            .rows().filter { HudGeometry.isLevel(it.id()) }.map { it.id() })
        assertEquals(top, HudGeometry.move(top, "sessionTime", "currentXp"))
        val legacy = listOf("sessionTime", "currentLevel", "target", "levelProgress", "runsLeft", "currentXp",
            "remaining", "floor", "xpPerRun", "profile", "lastRun", "observedCount", "profit", "avgChest",
            "chestsOpened", "kismets", "croesus", "lastChest")
        assertEquals(HudGeometry.DEFAULT_ORDER, HudGeometry.normalizeOrder(legacy))
        assertEquals(HudGeometry.DEFAULT_ORDER, HudGeometry.normalizeOrder(emptyList()))
        assertEquals(listOf("lastRun", "sessionTime"), HudGeometry.normalizeOrder(listOf("lastRun", "sessionTime", "lastRun", "obsolete")).take(2))
        assertEquals(HudGeometry.DEFAULT_ORDER.size, HudGeometry.normalizeOrder(legacy.reversed()).size)
        assertEquals(reference.width(), layout(listOf("profit", "avgChest"), items = true).width())
        assertTrue(HudGeometry.measure(180, 140, 130, 18, top, bottom, false).right() >= 330)
        assertEquals(0, HudGeometry.progressWidth(200, -5.0))
        assertEquals(24, HudGeometry.progressWidth(200, 12.0))
        assertEquals(200, HudGeometry.progressWidth(200, 120.0))
        assertEquals(0, HudGeometry.progressWidth(200, Double.NaN))
        assertEquals(0xFF6ECAFD.toInt(), HudGeometry.ACCENT)
        assertEquals(0xFF6FF4C6.toInt(), HudGeometry.PROFIT)
    }
}
