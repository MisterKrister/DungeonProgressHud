package dev.krister.dungeonprogresshud

import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.dungeon.DungeonProgressHudFeature
import com.google.gson.Gson
import io.github.classgraph.ClassGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoammIntegrationTest {
    @Test fun `NoammAddons scanner discovers the singleton feature`() {
        // Match ClassGraphInitializer without initializing Minecraft or fetching remote data.
        ClassGraph().enableAllInfo().acceptPackages("com.github.noamm9")
            .overrideClassLoaders(Thread.currentThread().contextClassLoader).scan().use { scan ->
            val feature = scan.getSubclasses(Feature::class.java.name)
                .single { it.name == DungeonProgressHudFeature::class.java.name }
            assertTrue(feature.getFieldInfo("INSTANCE").isStatic)
            assertEquals("dungeon", feature.packageName.substringAfter(".impl."))
        }
    }

    @Test fun `legacy price sources survive a history round trip`() {
        val gson = Gson()
        val quote = gson.fromJson("""{"itemId":"ITEM","unitPrice":99.0,"source":"DEVONIAN_FALLBACK","available":true}""", PriceQuote::class.java)
        assertEquals(PriceSource.DEVONIAN_FALLBACK, quote.source)
        assertEquals(quote, gson.fromJson(gson.toJson(quote), PriceQuote::class.java))
    }
}
