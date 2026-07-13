package dev.krister.dungeonprogresshud

import com.github.synnerz.devonian.api.SkyblockPrices
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.BazaarAPI
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.pricing.LowestBinAPI

interface PriceService {
    fun quote(itemId: String): PriceQuote
    fun health(): PricingHealth
}

data class PriceQuote(
    val itemId: String,
    val unitPrice: Double?,
    val source: PriceSource,
    val available: Boolean = unitPrice != null && unitPrice > 0.0,
    val quotedAt: Long = System.currentTimeMillis(),
)

enum class PriceSource {
    BAZAAR_BUY,
    BAZAAR_SELL,
    AUCTION_LOWEST,
    AUCTION_MEDIAN,
    AUCTION_MEAN,
    DEVONIAN_FALLBACK,
    HARDCODED,
    UNAVAILABLE,
}

enum class BazaarValuation { INSTANT_BUY, INSTANT_SELL }
enum class AuctionValuation { LOWEST_BIN, MEDIAN, MEAN }
enum class MissingPriceBehavior { MARK_INCOMPLETE, COUNT_AS_ZERO }

data class PricingOptions(
    val bazaarValuation: BazaarValuation = BazaarValuation.INSTANT_BUY,
    val auctionValuation: AuctionValuation = AuctionValuation.LOWEST_BIN,
    val allowDevonianWhileLoading: Boolean = true,
)

data class BazaarPrice(val instantBuy: Double, val instantSell: Double)
data class AuctionPrice(val lowest: Double, val median: Double, val mean: Double)

interface PriceDataProvider {
    fun bazaar(itemId: String): BazaarPrice?
    fun auction(itemId: String): AuctionPrice?
    fun bazaarItemCount(): Int
    fun auctionItemCount(): Int
}

data class PricingHealth(
    val bazaarItemCount: Int,
    val auctionItemCount: Int,
    val fallbackActive: Boolean,
    val lastQuote: PriceQuote?,
) {
    val status: String
        get() = when {
            bazaarItemCount == 0 && auctionItemCount == 0 -> "Loading"
            bazaarItemCount == 0 || auctionItemCount == 0 -> "Partial"
            else -> "Ready"
        }
}

object SkyBlockApiPriceDataProvider : PriceDataProvider {
    override fun bazaar(itemId: String): BazaarPrice? = BazaarAPI.getProduct(itemId)?.let {
        // Hypixel's sellPrice is the current instant-buy price; buyPrice is instant-sell.
        BazaarPrice(instantBuy = it.sellPrice, instantSell = it.buyPrice)
    }

    override fun auction(itemId: String): AuctionPrice? = LowestBinAPI.getPrice(itemId)?.let {
        AuctionPrice(it.lowest.toDouble(), it.median.toDouble(), it.mean)
    }

    override fun bazaarItemCount(): Int = BazaarAPI.products.size
    override fun auctionItemCount(): Int = LowestBinAPI.items.size
}

class SkyBlockPriceService(
    private val provider: PriceDataProvider = SkyBlockApiPriceDataProvider,
    private val options: () -> PricingOptions = { PricingOptions() },
    private val hardcodedPrices: Map<String, Double> = emptyMap(),
    private val devonianPrice: (String) -> Double = { SkyblockPrices.buyPrice(it).toDouble() },
) : PriceService {
    private var lastQuote: PriceQuote? = null
    private var fallbackActive = false

    override fun quote(itemId: String): PriceQuote {
        val id = normalizeSkyBlockId(itemId)
        val settings = options()
        val bazaar = provider.bazaar(id)
        val bazaarValue = bazaar?.let {
            if (settings.bazaarValuation == BazaarValuation.INSTANT_BUY) it.instantBuy else it.instantSell
        }?.positiveOrNull()
        val quote = bazaarValue?.let {
            PriceQuote(id, it, if (settings.bazaarValuation == BazaarValuation.INSTANT_BUY) PriceSource.BAZAAR_BUY else PriceSource.BAZAAR_SELL)
        } ?: provider.auction(id)?.let { auction ->
            val value = when (settings.auctionValuation) {
                AuctionValuation.LOWEST_BIN -> auction.lowest
                AuctionValuation.MEDIAN -> auction.median
                AuctionValuation.MEAN -> auction.mean
            }.positiveOrNull() ?: return@let null
            val source = when (settings.auctionValuation) {
                AuctionValuation.LOWEST_BIN -> PriceSource.AUCTION_LOWEST
                AuctionValuation.MEDIAN -> PriceSource.AUCTION_MEDIAN
                AuctionValuation.MEAN -> PriceSource.AUCTION_MEAN
            }
            PriceQuote(id, value, source)
        } ?: loadingFallback(id, settings)
            ?: hardcodedPrices[id]?.positiveOrNull()?.let { PriceQuote(id, it, PriceSource.HARDCODED) }
            ?: PriceQuote(id, null, PriceSource.UNAVAILABLE, available = false)

        fallbackActive = quote.source == PriceSource.DEVONIAN_FALLBACK
        if (quote.available) lastQuote = quote
        return quote
    }

    override fun health(): PricingHealth = PricingHealth(
        bazaarItemCount = provider.bazaarItemCount(),
        auctionItemCount = provider.auctionItemCount(),
        fallbackActive = fallbackActive,
        lastQuote = lastQuote,
    )

    private fun loadingFallback(id: String, settings: PricingOptions): PriceQuote? {
        if (!settings.allowDevonianWhileLoading) return null
        if (provider.bazaarItemCount() > 0 && provider.auctionItemCount() > 0) return null
        return devonianPrice(id).positiveOrNull()?.let { PriceQuote(id, it, PriceSource.DEVONIAN_FALLBACK) }
    }
}

private val SKYBLOCK_ID_ALIASES = mapOf(
    "WITHER_SHARD" to "SHARD_WITHER",
    "THORN_SHARD" to "SHARD_THORN",
    "APEX_DRAGON_SHARD" to "SHARD_APEX_DRAGON",
    "POWER_DRAGON_SHARD" to "SHARD_POWER_DRAGON",
    "SCARF_SHARD" to "SHARD_SCARF",
    "NECROMANCERS_BROOCH" to "NECROMANCER_BROOCH",
    "WITHER_SHIELD" to "WITHER_SHIELD_SCROLL",
    "IMPLOSION" to "IMPLOSION_SCROLL",
    "SHADOW_WARP" to "SHADOW_WARP_SCROLL",
    "WARPED_STONE" to "AOTE_STONE",
    "SPIRIT_STONE" to "SPIRIT_DECOY",
)

internal fun normalizeSkyBlockId(itemId: String): String {
    val normalized = itemId.trim().uppercase()
    return SKYBLOCK_ID_ALIASES[normalized] ?: normalized
}

internal fun resolveEnchantedBookId(enchantName: String, tier: Int, prices: PriceService): String {
    val normalizedName = enchantName.trim().replace(" ", "_").uppercase()
    val standard = "ENCHANTMENT_${normalizedName}_$tier"
    return if (prices.quote(standard).available) standard else "ENCHANTMENT_ULTIMATE_${normalizedName}_$tier"
}

private fun Double.positiveOrNull(): Double? = takeIf { it.isFinite() && it > 0.0 }
