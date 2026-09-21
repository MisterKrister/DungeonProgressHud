package dev.krister.dungeonprogresshud

import dev.krister.dungeonprogresshud.DungeonLevels.level as currentCataLevel
import dev.krister.dungeonprogresshud.DungeonLevels.targetXp
import com.github.synnerz.devonian.Devonian
import com.github.synnerz.devonian.api.ItemUtils
import com.github.synnerz.devonian.api.Location
import com.github.synnerz.devonian.api.events.GuiKeyDownEvent
import com.github.synnerz.devonian.api.events.ChatEvent
import com.github.synnerz.devonian.config.Categories
import com.github.synnerz.devonian.config.Config
import com.github.synnerz.devonian.config.ConfigData
import com.github.synnerz.devonian.hud.HudFeature
import com.github.synnerz.devonian.hud.HudManager
import com.github.synnerz.devonian.utils.BoundingBox
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.multiplayer.chat.GuiMessageTag
import net.minecraft.network.chat.MessageSignature
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private fun GuiGraphicsExtractor.drawString(font: Font, component: Component, x: Int, y: Int, color: Int, shadow: Boolean) {
    text(font, component, x, y, color, shadow)
}

object DungeonProgressHudAddon : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("DungeonProgressHud")
    private val debugLogFile = FabricLoader.getInstance().configDir.resolve("DungeonProgressHud").resolve("debug.log").toFile()
    private var commandsRegistered = false
    private var registered = false
    private var feature: DungeonProgressHudFeature? = null
    private var renderHookSeen = false
    private var lastMissingFeatureLog = 0L
    private var pendingServerJoinAt = 0L
    private var lastTickDetectedServerKey = ""

    override fun onInitializeClient() {
        debug("Client entrypoint initializing")
        registerCommands()
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.execute {
                val current = feature
                if (current != null) {
                    current.onServerJoin()
                } else {
                    pendingServerJoinAt = System.currentTimeMillis()
                    debug("Server join queued until feature registration")
                }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute {
                pendingServerJoinAt = 0L
                lastTickDetectedServerKey = ""
                feature?.onServerDisconnect()
            }
        }
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register {
            feature?.flushHistory()
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val serverKey = detectedServerKey(client)
            if (serverKey != lastTickDetectedServerKey) {
                lastTickDetectedServerKey = serverKey
                if (serverKey.isNotBlank()) feature?.onServerJoin()
            }
            feature?.onFabricClientTick()
        }
    }

    private fun detectedServerKey(client: Minecraft): String {
        if (client.player == null || client.connection == null) return ""
        return client.currentServer?.ip ?: client.level?.dimension()?.toString().orEmpty()
    }

    fun registerWithDevonian() {
        if (registered) return
        debug("Registering DungeonProgressHud feature with Devonian")
        registerCommands()
        val category = Categories.GLOBAL
        val subcategory = "Mod"
        val created = DungeonProgressHudFeature(category, subcategory)
        feature = created
        Devonian.addFeatureInstance(created)
        if (pendingServerJoinAt > 0L) {
            created.onServerJoin(pendingServerJoinAt)
            pendingServerJoinAt = 0L
        }
        registered = true
        debug("Devonian feature registration complete")
    }

    fun renderOverlay(graphics: GuiGraphicsExtractor) {
        if (!renderHookSeen) {
            renderHookSeen = true
            debug("Gui render hook fired")
        }
        val current = feature
        if (current == null) {
            val now = System.currentTimeMillis()
            if (now - lastMissingFeatureLog >= 5_000) {
                lastMissingFeatureLog = now
                debug("Render skipped: feature is not registered")
            }
            return
        }
        current.renderHud(graphics)
    }

    fun renderScreenOverlay(graphics: GuiGraphicsExtractor, screen: Screen, mouseX: Int, mouseY: Int) {
        if (screen is AbstractContainerScreen<*>) {
            feature?.renderHudOrderOverlay(graphics, mouseX.toDouble(), mouseY.toDouble())
        }
    }

    fun onServerInventoryUpdate(containerId: Int) {
        feature?.onServerInventoryUpdate(containerId)
    }

    fun onInventoryClick(containerId: Int, slotId: Int, button: Int, clickType: ContainerInput) {
        feature?.onInventoryClick(containerId, slotId, button, clickType)
    }

    fun onHudOrderMouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, shiftDown: Boolean): Boolean =
        feature?.onHudOrderMouseClicked(screen, event, shiftDown) ?: false

    fun onHudModeButtonPressed(): Boolean =
        feature?.onHudModeButtonPressed() ?: false

    fun onHudOrderMouseDragged(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        feature?.onHudOrderMouseDragged(screen, event, dragX, dragY) ?: false

    fun onHudOrderMouseReleased(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean =
        feature?.onHudOrderMouseReleased(screen, event) ?: false

    fun onFakeOpenKey(screen: AbstractContainerScreen<*>): Boolean = feature?.onFakeOpenKey(screen) ?: false

    fun onResetSelectionKey(screen: AbstractContainerScreen<*>): Boolean = feature?.onResetSelectionKey(screen) ?: false

    fun matchesFakeOpenKey(event: KeyEvent): Boolean = feature?.matchesFakeOpenKey(event) ?: false

    fun onChatMessage(message: Component) {
        feature?.parseDungeonCompletionMessage(message)
    }

    private fun registerCommands() {
        if (commandsRegistered) return
        commandsRegistered = true
        debug("Registering /dph client commands")
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("dph")
                    .executes {
                        withFeature { it.sendStatus() }
                        1
                    }
                    .then(literal("legacy").executes {
                        feature?.sendLegacyStatus()
                        1
                    })
                    .then(literal("recoverbackup").executes {
                        feature?.recoverBackup()
                        1
                    })
                    .then(literal("refresh").executes {
                        withFeature { it.refresh(force = true, recordObservedSample = false, notify = true) }
                        1
                    })
                    .then(literal("reset").executes {
                        withFeature { it.resetSamples() }
                        1
                    })
                    .then(literal("prices").executes {
                        withFeature { it.sendPricingStatus() }
                        1
                    })
                    .then(literal("session").executes {
                        withFeature { it.setTrackerScope("session") }
                        1
                    })
                    .then(literal("daily").executes {
                        withFeature { it.setTrackerScope("daily") }
                        1
                    })
                    .then(literal("weekly").executes {
                        withFeature { it.setTrackerScope("weekly") }
                        1
                    })
                    .then(literal("total").executes {
                        withFeature { it.setTrackerScope("total") }
                        1
                    })
                    .then(literal("importlogs").executes {
                        withFeature { it.importRecentLogs(true) }
                        1
                    })
                    .then(literal("summary")
                        .executes {
                            withFeature { it.sendRunSummary("daily") }
                            1
                        }
                        .then(literal("session").executes {
                            withFeature { it.sendRunSummary("session") }
                            1
                        })
                        .then(literal("daily").executes {
                            withFeature { it.sendRunSummary("daily") }
                            1
                        })
                        .then(literal("weekly").executes {
                            withFeature { it.sendRunSummary("weekly") }
                            1
                        })
                    )
                    .then(literal("profit")
                        .executes {
                            withFeature { it.sendProfitStatus() }
                            1
                        }
                        .then(literal("toggle").executes {
                            withFeature { it.toggleProfitMode() }
                            1
                        })
                        .then(literal("session").executes {
                            withFeature { it.setProfitMode("Session") }
                            1
                        })
                        .then(literal("total").executes {
                            withFeature { it.setProfitMode("Total") }
                            1
                        })
                        .then(argument("window", StringArgumentType.word()).executes { context ->
                            withFeature { it.setProfitWindow(StringArgumentType.getString(context, "window")) }
                            1
                        })
                    )
                    .then(literal("items")
                        .executes {
                            withFeature { it.sendTrackerStatus() }
                            1
                        }
                        .then(literal("prices").executes {
                            withFeature { it.sendTrackedItemPrices() }
                            1
                        })
                        .then(literal("add")
                            .executes {
                                withFeature { it.sendTrackedItemPrices() }
                                1
                            }
                            .then(argument("item", StringArgumentType.string())
                                .suggests { _, builder ->
                                    TrackedDungeonItems.all.map { it.command }
                                        .filter { it.startsWith(builder.remainingLowerCase) }
                                        .forEach { builder.suggest(it) }
                                    builder.buildFuture()
                                }
                                .executes { context ->
                                    withFeature { it.addTrackedItem(StringArgumentType.getString(context, "item")) }
                                    1
                                }
                                .then(argument("count", IntegerArgumentType.integer(1, ChestRecorder.MAX_MANUAL_ITEM_COUNT))
                                    .executes { context ->
                                        withFeature { it.addTrackedItem(StringArgumentType.getString(context, "item"),
                                            IntegerArgumentType.getInteger(context, "count")) }
                                        1
                                    }
                                    .then(argument("chestCost", LongArgumentType.longArg(0))
                                        .executes { context ->
                                            withFeature { it.addTrackedItem(StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "count"),
                                                LongArgumentType.getLong(context, "chestCost")) }
                                            1
                                        }
                                    )
                                )
                            )
                        )
                        .then(literal("toggle").executes {
                            withFeature { it.toggleTrackerMode() }
                            1
                        })
                        .then(literal("session").executes {
                            withFeature { it.setTrackerMode("Session") }
                            1
                        })
                        .then(literal("total").executes {
                            withFeature { it.setTrackerMode("Total") }
                            1
                        })
                        .then(literal("reset").executes {
                            withFeature { it.resetTrackedItems() }
                            1
                        })
                        .then(argument("window", StringArgumentType.word()).executes { context ->
                            withFeature { it.setTrackerWindow(StringArgumentType.getString(context, "window")) }
                            1
                        })
                    )
                    .then(literal("fake").executes {
                        withFeature { it.fakeOpenCurrentScreen() }
                        1
                    })
                    .then(literal("order")
                        .then(literal("reset").executes {
                            withFeature { it.resetHudLineOrder() }
                            1
                        })
                    )
                    .then(argument("scope", StringArgumentType.word()).executes { context ->
                        withFeature { it.setTrackerScope(StringArgumentType.getString(context, "scope")) }
                        1
                    })
            )
        }
    }

    private inline fun withFeature(action: (DungeonProgressHudFeature) -> Unit) {
        val current = feature ?: return
        action(current)
    }

    fun debug(message: String) {
        logger.debug(message)
    }

}

class DungeonProgressHudFeature(
    configCategory: Categories,
    private val configTab: String,
) : HudFeature(
    "dungeonProgressHud",
    "Shows Catacombs XP progress and estimated runs left.",
    configCategory,
    "catacombs",
    displayName = "Dungeon Progress HUD",
    subcategory = configTab,
) {
    private companion object {
        private const val FEATURE_CONFIG = "dungeonProgressHud"
        private const val DAY_MILLIS = 86_400_000L
        private const val AUTO_REFRESH_INTERVAL_MILLIS = 300_000L
        private const val PROFILE_FETCH_TIMEOUT_SECONDS = 30L
        private const val SKYBLOCK_PV_MOD_ID = "skyblockpv"
        private const val SKYBLOCK_PV_PROFILE_API = "me.owdding.skyblockpv.api.ProfileAPI"
        private const val SKYBLOCK_PV_PLAYER_DB_API = "me.owdding.skyblockpv.api.PlayerDbAPI"
        private const val SKYBLOCK_API_PROFILE_API = "tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI"
        private const val SKYBLOCKER_MOD_ID = "skyblocker"
        private const val SKYBLOCKER_PROFILE_UTILS = "de.hysky.skyblocker.utils.ProfileUtils"
        private const val CROESUS_TAB_REFRESH_INTERVAL_MILLIS = 1_000L
        private const val MAX_TRACKED_ITEM_DROPS = 5_000
        private const val HUD_LINE_GAP = 2
        private const val HUD_TOP_PADDING = HudGeometry.TOP_PADDING
        private const val HUD_SIDE_PADDING = HudGeometry.SIDE_PADDING
        private const val HUD_ORDER_HINT = "Shift + drag to reorder"
        private const val STATUS_LINE_ID = "status"
        private val JOIN_REFRESH_RETRY_DELAYS = longArrayOf(10_000L, 30_000L, 90_000L, 180_000L)
        private val DEFAULT_HUD_LINE_ORDER = HudGeometry.DEFAULT_ORDER
        private const val HUD_MODE_PROFIT = "profit"
        private const val HUD_MODE_ITEMS = "items"
        private val CROESUS_TAB_COUNT_REGEXES = listOf(
            Regex("\\bunclaimed\\s+chests?\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bunopened\\s+chests?\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\b(?:unclaimed|unopened)\\b.*\\bcroesus\\b.*?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bcroesus\\b.*\\b(?:unclaimed|unopened)\\b.*?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bcroesus\\b.*\\bchests?\\b.*?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d+)\\b.*\\bcroesus\\b.*\\bchests?\\b", RegexOption.IGNORE_CASE),
        )
    }

    private val PREFIX = "&6[&bDPH&6]&r "
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configDir = FabricLoader.getInstance().configDir.resolve("DungeonProgressHud").toFile()
    private val stateFile = File(configDir, "runs.json")
    private val summarySignatures = (0 until 3).map { signature("dph-summary-$it") }
    private val logsDir = FabricLoader.getInstance().gameDir.resolve("logs").toFile()
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val renderHud = addSwitch("11_renderHud", true, "Draw the HUD during normal gameplay.", "Render HUD", emptySet(), false, configTab)
    private val showEverywhere = addSwitch("12_showEverywhere", true, "Render everywhere instead of only Dungeon Hub/Catacombs.", "Show Everywhere", emptySet(), false, configTab)
    private val targetLevel = addTextInput("13_targetLevel", "50", "Target Catacombs level.", "Target Level", emptySet(), configTab)
    private val showCurrentLevel = addSwitch("15_showCurrentLevel", true, "Show current Catacombs level.", "Current Level", emptySet(), false, configTab)
    private val showCurrentXp = addSwitch("16_showCurrentXp", true, "Show current Catacombs XP.", "Current XP", emptySet(), false, configTab)
    private val showLevelProgress = addSwitch("17_showLevelProgress", true, "Show percentage progress toward the next Catacombs level.", "Level Progress", emptySet(), false, configTab)
    private val showTarget = addSwitch("18_showTarget", true, "Show target Catacombs level.", "Target", emptySet(), false, configTab)
    private val showRemaining = addSwitch("19_showRemaining", true, "Show XP remaining.", "XP Remaining", emptySet(), false, configTab)
    private val showFloor = addSwitch("1a_showFloor", true, "Show floor label.", "Floor", emptySet(), false, configTab)
    private val showXpPerRun = addSwitch("1b_showXpPerRun", true, "Show the scoped average XP per run and XP per hour.", "XP/Run", emptySet(), false, configTab)
    private val showRunsLeft = addSwitch("1c_showRunsLeft", true, "Show estimated runs left.", "Runs Left", emptySet(), false, configTab)
    private val showProfile = addSwitch("1d_showProfile", false, "Show selected SkyBlock profile.", "Profile", emptySet(), false, configTab)
    private val showLastRun = addSwitch("1e_showLastRun", true, "Show the most recent recorded run XP in the selected tracker scope.", "Last Run XP", emptySet(), false, configTab)
    private val showObservedCount = addSwitch("1f_showObservedCount", true, "Show persisted runs for the selected tracker scope.", "Run Count", emptySet(), false, configTab)
    private val classProgressMode = addSelection("1g_classProgressMode", 0, listOf("Off", "Current Class", "All Classes"), "Show the current class toward your target, or progress bars for all five classes.", "Class Progress", emptySet(), configTab)
    private val classTargetLevel = addTextInput("1h_classTargetLevel", "50", "Target level for the Current Class view (1-1000, including virtual levels above 50).", "Class Target Level", emptySet(), configTab)
    private val allClassesGoal = addSelection("1i_allClassesGoal", 0, listOf("Next Level", "Level 50"), "Choose what the All Classes progress bars measure.", "All Classes Goal", emptySet(), configTab)
    private val showClassRuns = addSwitch("1j_showClassRuns", false, "Replace All Classes percentages with estimated runs remaining while continuing your current class. Uses measured class XP gains, including passive XP, on the displayed floor.", "Class Runs Remaining", emptySet(), false, configTab)

    private val xpMode = addSelection("21_xpMode", 0, listOf("Observed Average", "Hardcoded"), "XP/run source.", "XP/Run Mode", emptySet(), configTab)
    private val hardcodedXpPerRun = addTextInput("22_hardcodedXpPerRun", "450000", "Fallback XP per run.", "Hardcoded XP/Run", emptySet(), configTab)
    private val trackChestProfit = addSwitch("31_trackChestProfit", true, "Track profit when claiming dungeon reward chests.", "Track Chest Profit", emptySet(), false, configTab)
    private val showChestProfit = addSwitch("32_showChestProfit", true, "Show tracked dungeon chest profit.", "Chest Profit", emptySet(), false, configTab)
    private val chestProfitMode = addSelection("33_chestProfitMode", 0, listOf("Session", "Total"), "Choose whether profit and item tracker lines use this session or all tracked chests.", "Tracker Mode", emptySet(), configTab)
    private val showChestCount = addSwitch("34_showChestCount", false, "Show tracked dungeon reward chest count.", "Chest Count", emptySet(), false, configTab)
    private val showLastChest = addSwitch("35_showLastChest", false, "Show the last opened dungeon reward chest and its profit.", "Last Chest Opened", emptySet(), false, configTab)
    private val includeEssenceProfit = addSwitch("36_includeEssenceProfit", true, "Include essence value in chest profit.", "Include Essence", emptySet(), false, configTab)
    private val includeDungeonKeyCost = addSwitch("37_includeDungeonKeyCost", false, "Subtract Dungeon Chest Key value when a reward chest requires one.", "Count Dungeon Key Cost", emptySet(), false, configTab)
    private val bazaarValuation = addSelection("38_bazaarValuation", 0, listOf("Instant Buy", "Instant Sell"), "Choose the Bazaar side used to value rewards.", "Bazaar Valuation", emptySet(), configTab)
    private val auctionValuation = addSelection("39_auctionValuation", 1, listOf("Lowest BIN", "Median", "Mean"), "Median limits the effect of extreme listings; choose the statistic used for auction rewards.", "Auction Valuation", emptySet(), configTab)
    private val missingPriceBehavior = addSelection("3a_missingPriceBehavior", 0, listOf("Mark Chest Incomplete", "Count Missing As Zero"), "Incomplete confirmed chests are saved and retried when prices load.", "Missing Prices", emptySet(), configTab)
    private val allowDevonianPriceFallback = addSwitch("3b_allowDevonianPriceFallback", true, "Use Devonian prices only while a SkyBlockAPI cache is still loading.", "Devonian Loading Fallback", emptySet(), false, configTab)
    private val includeKismetCost = addSwitch("3c_includeKismetCost", true, "Subtract Kismet Feather value from the associated chest.", "Count Kismet Cost", emptySet(), false, configTab)

    private val refreshButton = addButton({ refresh(force = true, recordObservedSample = false, notify = true) }, "Refresh", "Force profile refresh.", "Refresh Player Data", emptySet(), configTab)
    private val resetButton = addButton({ resetSamples() }, "Reset", "Clear XP/run averaging samples and reset the profile XP baseline.", "Reset XP Samples", emptySet(), configTab)
    private val keybindCategory by lazy {
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("dungeonprogresshud", "keybinds"))
    }
    private val fakeOpenKey = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.dungeonprogresshud.fakeOpenChest", GLFW.GLFW_KEY_H, keybindCategory)
    )

    private var stateRevision = 0L
    private var state = RunState()
    private val profileProvider by lazy { ProfileProvider(::log) }
    private var data: ProfileData? = null
    private var status = "Waiting for profile data"
    private var refreshGeneration = 0L
    private var refreshing = false
    private var lastRefresh = 0L
    private var lastAutoRefreshAttempt = 0L
    private var joinRefreshStartedAt = 0L
    private var joinRefreshAttempts = 0
    private var nextJoinRefreshAttemptAt = 0L
    private var joinRefreshCompleted = false
    private var lastServerJoinEventAt = 0L
    private var skyBlockJoinRefreshAttempted = false
    private var skyBlockJoinRefreshCompleted = false
    private var wasVisible = false
    private var startupRefreshAttempted = false
    private var runtimeStarted = false
    private var lastRenderStateLog = 0L
    private var renderedOnce = false
    private var pendingChestProfit: ChestProfitCandidate? = null
    private var lastChestScanKey = ""
    private var lastChestScanCandidate: ChestProfitCandidate? = null
    private var lastCroesusCandidates: Map<String, ChestProfitCandidate> = emptyMap()
    private var selectedCroesusCandidate: ChestProfitCandidate? = null
    private var lastChestScreenLog = 0L
    private var lastVisibilityCheckAt = 0L
    private var lastVisibilityResult = false
    private var lastCroesusTabRefreshAt = 0L
    private val chestClaimDeduplicator = EventDeduplicator()
    private val kismetDeduplicator = EventDeduplicator()
    private val claimAttempts = ChestAttemptTracker<ChestProfitCandidate>()
    private val rerollAttempts = ChestAttemptTracker<Pair<String, PriceQuote>>()
    private var chestContextId = UUID.randomUUID().toString()
    private var chestContextFloor = "N/A"

    private var lastDungeonCompletionChat = ""
    private var lastDungeonCompletionChatAt = 0L
    private var lastDungeonCompletionRawXp = 0L
    private var lastDungeonCompletionNormalizedXp = 0L
    private var pendingCompletionAt = 0L
    private var pendingCompletionFloor = ""
    private var pendingCompletionTimeSeconds = 0
    private var pendingCompletionScore = 0
    private var pendingCompletionGrade = ""
    private var sessionChestProfit = 0L
    private var sessionChestsOpened = 0
    private var sessionTracker = DungeonSessionTracker()
    private var trackingOwner = ""
    private val sessionStartedAt: Long
        get() = sessionTracker.startedAt
    private var detectedFloorLabel = ""
    private var completedDungeon: Pair<net.minecraft.client.multiplayer.ClientLevel, String>? = null
    private var detectedDungeonClass: String? = null
    private var nextPriceRefreshAt = 0L
    private var priceRefreshInProgress = false
    private var priceRefreshError: String? = null
    private var nextPendingPriceRetryAt = 0L
    private var draggedHudLineId: String? = null
    private var hoveredHudLineId: String? = null
    private var settingsLoaded = false
    private val priceService: PriceService by lazy {
        SkyBlockPriceService(
            options = {
                PricingOptions(
                    bazaarValuation = if (bazaarValuation.get() == 1) BazaarValuation.INSTANT_SELL else BazaarValuation.INSTANT_BUY,
                    auctionValuation = when (auctionValuation.get()) {
                        1 -> AuctionValuation.MEDIAN
                        2 -> AuctionValuation.MEAN
                        else -> AuctionValuation.LOWEST_BIN
                    },
                    allowDevonianWhileLoading = allowDevonianPriceFallback.get(),
                )
            },
        )
    }
    private val chestProfitCalculator by lazy { ChestProfitCalculator(priceService) }

    init {
        xpMode.onChange { index ->
            if (settingsLoaded && index !in xpMode.options.indices) xpMode.set(0)
        }
        chestProfitMode.onChange { index ->
            if (!settingsLoaded) return@onChange
            if (index !in chestProfitMode.options.indices) {
                chestProfitMode.set(0)
                return@onChange
            }
            if (state.chestProfitWindowMillis > 0L) {
                state.chestProfitWindowMillis = 0L
                saveState()
                log("Rolling tracker window cleared by Tracker Mode setting")
            }
        }
        trackChestProfit.onChange { enabled ->
            if (!enabled) clearPendingChestTracking()
        }
        Config.onAfterLoad {
            if (xpMode.get() !in xpMode.options.indices) xpMode.set(0)
            if (chestProfitMode.get() !in chestProfitMode.options.indices) chestProfitMode.set(0)
            if (bazaarValuation.get() !in bazaarValuation.options.indices) bazaarValuation.set(0)
            if (auctionValuation.get() !in auctionValuation.options.indices) auctionValuation.set(0)
            if (missingPriceBehavior.get() !in missingPriceBehavior.options.indices) missingPriceBehavior.set(0)
            if (classProgressMode.get() !in classProgressMode.options.indices) classProgressMode.set(0)
            if (allClassesGoal.get() !in allClassesGoal.options.indices) allClassesGoal.set(0)
            settingsLoaded = true
        }
    }

    fun onServerJoin(joinedAt: Long = System.currentTimeMillis()) {
        startRuntime("server join")
        if (joinedAt - lastServerJoinEventAt < 5_000L && (joinRefreshStartedAt > 0L || refreshing || joinRefreshAttempts > 0 || lastRefresh >= joinRefreshStartedAt)) {
            log("Server join refresh ignored duplicate event")
            return
        }
        lastServerJoinEventAt = joinedAt
        scheduleJoinRefresh(joinedAt)
        log("Server join refresh scheduled user=${mc.user.name} uuid=${mc.user.profileId}")
    }

    private fun scheduleJoinRefresh(joinedAt: Long) {
        nextPriceRefreshAt = joinedAt
        joinRefreshStartedAt = joinedAt
        joinRefreshAttempts = 0
        nextJoinRefreshAttemptAt = 0L
        joinRefreshCompleted = false
        skyBlockJoinRefreshAttempted = false
        skyBlockJoinRefreshCompleted = false
        startupRefreshAttempted = true
    }

    fun onServerDisconnect() {
        clearPendingChestTracking()
        completedDungeon = null
        detectedFloorLabel = ""
        detectedDungeonClass = null
        pendingCompletionAt = 0L
        pendingCompletionFloor = ""
        pendingCompletionTimeSeconds = 0
        pendingCompletionScore = 0
        pendingCompletionGrade = ""
        lastDungeonCompletionChat = ""
        data = null
        refreshGeneration++
        refreshing = false
        lastRefresh = 0L
        lastServerJoinEventAt = 0L
        pauseSessionTimer("disconnect")
        joinRefreshStartedAt = 0L
        joinRefreshAttempts = 0
        nextJoinRefreshAttemptAt = 0L
        joinRefreshCompleted = false
        skyBlockJoinRefreshAttempted = false
        skyBlockJoinRefreshCompleted = false
    }

    override fun drawImpl(ctx: GuiGraphicsExtractor) = drawDirect(ctx)

    override fun sampleDraw(ctx: GuiGraphicsExtractor, mx: Int, my: Int, selected: Boolean) {
        drawDirect(ctx)
        super.sampleDraw(ctx, mx, my, selected)
    }

    override fun getBounds(): BoundingBox {
        val (_, top, bottom) = currentHudContent()
        val layout = hudPanelLayout(top, bottom)
        val renderScale = hudRenderScale()
        return BoundingBox(hudX(), hudY(), layout.width() * renderScale.toDouble(), layout.height() * renderScale.toDouble())
    }

    private fun hudX() = if (x.isFinite()) x else 10.0
    private fun hudY() = if (y.isFinite()) y else 10.0
    private fun hudRenderScale() = scale.takeIf { it.isFinite() && it > 0f } ?: 1f

    private fun clearPendingChestTracking() {
        pendingChestProfit = null
        lastChestScanKey = ""
        lastChestScanCandidate = null
        lastCroesusCandidates = emptyMap()
        selectedCroesusCandidate = null
        claimAttempts.clear()
        rerollAttempts.clear()
        chestContextId = UUID.randomUUID().toString()
        chestContextFloor = "N/A"
    }

    override fun initialize() {
        startRuntime("devonian initialize")


    }

    fun onFabricClientTick() {
        startRuntime("fabric client tick")
        val account = currentAccountId()
        val profile = currentProfileId()
        val owner = "$account:$profile"
        if (account.isNotBlank() && profile.isNotBlank() && owner != trackingOwner) {
            trackingOwner = owner
            completedDungeon = null
            detectedFloorLabel = ""
            clearPendingChestTracking()
            refreshGeneration++
            refreshing = false
            data = null
            detectedDungeonClass = null
            lastRefresh = 0L
            state.croesusUnclaimedCount = -1
            pendingCompletionAt = 0L
            pendingCompletionFloor = ""
            pendingCompletionTimeSeconds = 0
            pendingCompletionScore = 0
            pendingCompletionGrade = ""
            lastDungeonCompletionChat = ""
            sessionTracker = DungeonSessionTracker()
            // Profile identity can arrive after the first join refresh already completed.
            scheduleJoinRefresh(System.currentTimeMillis())
        }
        if (data == null) {
            data = HistoryQueries.savedProfile(state, account, profile, mc.player?.name?.string.orEmpty(),
                activeSkyBlockProfile()?.second.orEmpty())
            if (data != null) status = "Loaded saved ${data!!.profileName}"
        }
        clientTick()
    }

    private fun startRuntime(reason: String) {
        if (runtimeStarted) return
        runtimeStarted = true
        log("Feature runtime started by $reason")
        loadState()
        importRecentLogs(false)
    }

    private fun clientTick() {
        val now = System.currentTimeMillis()
        val scoreboard = getScoreboardText()
        val tabList = tabListLines()
        DungeonClasses.key(DungeonAPI.dungeonClass?.name)?.let {
            detectedDungeonClass = it
        }
        // A completion is authoritative for this dungeon world, including normal/master mode.
        if (completedDungeon?.first !== mc.level) completedDungeon = null
        val completionFloor = completedDungeon?.second
        (completionFloor ?: DungeonActivityDetector.detectFloor(Location.area, Location.subarea, scoreboard, tabList))?.let {
            detectedFloorLabel = it
        }
        if (settingsLoaded && !state.medianPricingDefaultApplied) {
            auctionValuation.set(1)
            state.medianPricingDefaultApplied = true
            saveState()
        }
        maybeRefreshPrices(now)
        sessionTracker.observeDungeon(isActiveDungeon(scoreboard, tabList), now)
        val refreshedForSkyBlockJoin = maybeRefreshAfterSkyBlockJoin()
        val refreshedForJoin = if (!refreshedForSkyBlockJoin) maybeRefreshAfterServerJoin() else false
        if (!startupRefreshAttempted && sessionReady() && !refreshedForSkyBlockJoin && !refreshedForJoin) {
            startupRefreshAttempted = true
            lastAutoRefreshAttempt = System.currentTimeMillis()
            log("Startup refresh triggered for user=${mc.user.name} uuid=${mc.user.profileId}")
            refresh(false)
        }

        scanCurrentChestScreen()
        maybeUpdateCroesusUnclaimedCountFromTab()
        val visible = shouldRenderCached()
        if (visible && !wasVisible) {
            lastAutoRefreshAttempt = System.currentTimeMillis()
            refresh(false)
        } else if (visible) {
            maybeAutoRefresh()
        }
        wasVisible = visible
    }

    private fun maybeRefreshPrices(now: Long) {
        if (!isEnabled() || !sessionReady()) return
        if (nextPriceRefreshAt == 0L) nextPriceRefreshAt = now + 10_000L
        if (!priceRefreshInProgress && now >= nextPriceRefreshAt) {
            priceRefreshInProgress = true
            SkyBlockApiPriceDataProvider.refresh().whenComplete { _, error ->
                mc.execute {
                    priceRefreshInProgress = false
                    priceRefreshError = error?.let(::profileFailureMessage)
                    nextPriceRefreshAt = System.currentTimeMillis() + if (error == null) 300_000L else 30_000L
                    nextPendingPriceRetryAt = 0L
                    lastChestScanKey = ""
                    log("Price refresh ${priceRefreshError ?: "complete"}")
                }
            }
        }
        if (trackChestProfit.get() && now >= nextPendingPriceRetryAt && state.pendingChestClaims.isNotEmpty()) {
            nextPendingPriceRetryAt = now + 5_000L
            val recorded = ChestRecorder.retryPending(state, priceService)
            if (recorded > 0) log("Resolved pricing for $recorded saved chest claims")
            saveState()
        }
    }

    private fun maybeRefreshAfterSkyBlockJoin(): Boolean {
        if (joinRefreshStartedAt == 0L || skyBlockJoinRefreshCompleted) return false
        if (!sessionReady()) return false

        val now = System.currentTimeMillis()
        if (!isSkyBlockArea()) return false

        if (skyBlockJoinRefreshAttempted && data != null && lastRefresh >= joinRefreshStartedAt) {
            skyBlockJoinRefreshCompleted = true
            log("SkyBlock detected after server join; profile already refreshed")
            return false
        }
        if (refreshing) return true
        if (skyBlockJoinRefreshAttempted) return false

        skyBlockJoinRefreshAttempted = true
        startupRefreshAttempted = true
        lastAutoRefreshAttempt = now
        log("SkyBlock detected after server join; forcing silent profile refresh server=${mc.currentServer?.ip ?: "unknown"}")
        refresh(force = true, recordObservedSample = false, notify = false)
        return true
    }

    private fun maybeRefreshAfterServerJoin(): Boolean {
        if (joinRefreshStartedAt == 0L) return false
        val now = System.currentTimeMillis()
        if (data != null && lastRefresh >= joinRefreshStartedAt) {
            joinRefreshCompleted = true
        }
        if (joinRefreshCompleted || !sessionReady()) return false
        if (refreshing) return true
        if (joinRefreshAttempts >= 1 && now < nextJoinRefreshAttemptAt) return true
        if (joinRefreshAttempts >= JOIN_REFRESH_RETRY_DELAYS.size + 1) {
            joinRefreshCompleted = true
            log("Join refresh gave up after $joinRefreshAttempts attempts status=$status")
            return false
        }

        joinRefreshAttempts++
        nextJoinRefreshAttemptAt = now + JOIN_REFRESH_RETRY_DELAYS.getOrElse(joinRefreshAttempts - 1) { JOIN_REFRESH_RETRY_DELAYS.last() }
        startupRefreshAttempted = true
        lastAutoRefreshAttempt = now
        log("Server join refresh attempt=$joinRefreshAttempts server=${mc.currentServer?.ip ?: "unknown"} user=${mc.user.name} uuid=${mc.user.profileId} lastRefresh=$lastRefresh joinStarted=$joinRefreshStartedAt")
        refresh(force = true, recordObservedSample = false, notify = false)
        return true
    }

    private fun maybeAutoRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastAutoRefreshAttempt < AUTO_REFRESH_INTERVAL_MILLIS) return
        lastAutoRefreshAttempt = now
        log("Periodic auto refresh triggered")
        refresh(false)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        val enabled = isEnabled()
        if (mc.screen is AbstractContainerScreen<*> || HudManager.isEditing) {
            return
        }
        val visible = shouldRenderCached()
        if (!renderHud.get() || !visible) {
            logRenderState("Render blocked renderHud=${renderHud.get()} enabled=$enabled shouldRender=$visible showEverywhere=${showEverywhere.get()}")
            return
        }


        drawDirect(graphics)
        if (!renderedOnce) {
            renderedOnce = true
            log("HUD rendered x=$x y=$y scale=$scale status=$status")
        }
    }

    fun refresh(force: Boolean, recordObservedSample: Boolean = true, notify: Boolean = false) {
        if (refreshing) {
            if (notify) send("Refresh already running.")
            return
        }
        if (!sessionReady()) {
            status = "Waiting for session"
            log("Refresh skipped: session not ready")
            if (notify) send("Refresh skipped: waiting for Minecraft session.")
            return
        }
        if (!force && System.currentTimeMillis() - lastRefresh < AUTO_REFRESH_INTERVAL_MILLIS && data != null) return
        if (!FabricLoader.getInstance().isModLoaded(SKYBLOCK_PV_MOD_ID) &&
            !FabricLoader.getInstance().isModLoaded(SKYBLOCKER_MOD_ID)
        ) {
            status = "No profile provider loaded"
            log("Refresh skipped: neither SkyBlock Profile Viewer nor SkyBlocker is loaded")
            if (notify) send("Refresh skipped: install SkyBlock Profile Viewer or SkyBlocker.")
            return
        }
        val player = mc.player ?: run {
            status = "Waiting for player"
            if (notify) send("Refresh skipped: waiting for player data.")
            return
        }
        val request = ProfileRequest(
            activeProfile = activeSkyBlockProfile(),
            playerName = player.name.string,
            playerUuid = player.uuid,
        )

        val generation = refreshGeneration
        val requestedProfile = activeSkyBlockProfile()?.first
        refreshing = true
        status = "Refreshing..."
        log("Refresh started force=$force recordObservedSample=$recordObservedSample user=${request.playerName} uuid=${request.playerUuid}")
        if (notify) send("Refreshing profile data...")

        thread(name = "DungeonProgressHud-Profile", isDaemon = true) {
            val result = runCatching { profileProvider.fetch(request) }
            mc.execute {
                if (generation != refreshGeneration || mc.player?.uuid != request.playerUuid) return@execute
                if (activeSkyBlockProfile()?.first != requestedProfile) { refreshing = false; return@execute }
                try {
                    result
                        .onSuccess {
                            data = it
                            status = "Loaded ${it.profileName}"
                            lastRefresh = System.currentTimeMillis()
                            log("Refresh success profile=${it.profileName} xp=${it.catacombsExperience} level=${currentCataLevel(it.catacombsExperience)}")
                            if (notify) send("Loaded ${it.profileName}: Cata ${currentCataLevel(it.catacombsExperience)}.")
                            recordSample(it, recordObservedSample)
                        }
                        .onFailure {
                            status = profileFailureMessage(it)
                            log("Refresh failed: ${it.stackTraceToString()}")
                            if (notify) send("Refresh failed: $status")
                        }
                } finally {
                    refreshing = false
                }
            }
        }
    }

    fun resetSamples() {
        state.averagingResetAt = System.currentTimeMillis()
        state.samples.clear()
        state.classXpSamples.clear()
        state.lastClassExperience = data?.classExperience.orEmpty()
        state.lastCatacombsXp = data?.catacombsExperience ?: 0L
        state.lastPlayerUuid = data?.playerUuid.orEmpty()
        saveState()
        log("Observed run samples reset")
        send("Observed runs reset.")
    }

    fun resetHudLineOrder() {
        state.hudLineOrder = DEFAULT_HUD_LINE_ORDER.toMutableList()
        draggedHudLineId = null
        hoveredHudLineId = null
        saveState()
        log("HUD line order reset")
        send("HUD line order reset.")
    }

    fun setProfitMode(mode: String) {
        setTrackerMode(mode)
    }

    fun setTrackerScope(input: String) {
        when (input.trim().lowercase(Locale.ROOT)) {
            "session" -> setTrackerMode("Session")
            "total" -> setTrackerMode("Total")
            "daily", "24h" -> setTrackerWindow("1d")
            "weekly" -> setTrackerWindow("1w")
            else -> setTrackerWindow(input)
        }
    }

    fun setTrackerMode(mode: String) {
        val normalized = if (mode.equals("Total", true)) "Total" else "Session"
        state.chestProfitWindowMillis = 0L
        saveState()
        chestProfitMode.set(if (normalized == "Total") 1 else 0)
        log("Tracker mode set to $normalized")
        send("Tracker view: ${normalized.lowercase(Locale.ROOT)}.")
    }

    fun toggleProfitMode() {
        toggleTrackerMode()
    }

    fun toggleTrackerMode() {
        if (state.chestProfitWindowMillis > 0L) {
            setTrackerMode(trackerModeValue())
            return
        }
        setTrackerMode(if (trackerModeValue() == "Total") "Session" else "Total")
    }

    fun setProfitWindow(input: String) {
        setTrackerWindow(input)
    }

    fun setTrackerWindow(input: String) {
        val parsedDays = parseProfitWindowDays(input)
        if (parsedDays == null) {
            send("Use /dph session, daily, total, 1, 2, 7, 1w, 2w, or 1m.")
            return
        }

        state.chestProfitWindowMillis = parsedDays * DAY_MILLIS
        saveState()
        log("Tracker window set to ${formatProfitWindow(state.chestProfitWindowMillis)}")
        send("Tracker view: ${formatProfitWindow(state.chestProfitWindowMillis)}.")
    }

    fun sendProfitStatus() {
        sendTrackerStatus()
    }

    fun sendTrackerStatus() {
        val stats = chestProfitStats()
        send("Tracker view: ${stats.label}, ${stats.profit.formatCoins()} across ${stats.chests} chests.")
        send("Includes current profile and older local chest history with unknown ownership. /dph legacy shows historical lifetime counters; discarded records cannot be assigned to a time window.")
        send("Add tracked drops: /dph items add <item> [count] [chestCost]. List items and costs: /dph items prices.")
    }

    fun sendTrackedItemPrices() {
        send("Use /dph items add <item> [count] [chestCost]. Count defaults to 1; optional cost is coins per chest.")
        send("Standard M7 opening costs (each item counts as one chest):")
        TrackedDungeonItems.all.forEach { item -> send("${item.command}: ${item.chestCoinCost.formatCoins()} coins.") }
    }

    fun addTrackedItem(input: String, count: Int = 1, chestCoinCost: Long? = null) {
        startRuntime("manual item command")
        val item = TrackedDungeonItems.find(input)
        if (item == null) {
            send("Unknown tracked item. Use /dph items prices or Tab to see the item names.")
            return
        }
        if (!historyRepository.writable || historyRepository.error != null) {
            send("History cannot be saved. Nothing added; /dph shows the history error.")
            return
        }
        val profile = activeSkyBlockProfile()
        val context = ClaimContext("", currentAccountId(), profile?.first?.toString()?.replace("-", "").orEmpty(),
            profile?.second.orEmpty(), "M7")
        val now = System.currentTimeMillis()
        val candidate = try {
            ChestRecorder.recordManualItems(state, item, count, chestCoinCost ?: item.chestCoinCost, priceService, context, now)
        } catch (error: IllegalArgumentException) {
            send(error.message ?: "Invalid item entry. Nothing added.")
            return
        } catch (_: ArithmeticException) {
            send("That entry would exceed the history's number limits. Nothing added.")
            return
        }
        ensureSessionStarted(now, "manual item")
        saveState()
        send("Added $count x ${item.displayName}: ${(candidate.grossValue * count).formatCoins()} value - ${(candidate.chestCoinCost * count).formatCoins()} chest cost = ${(candidate.profit * count).formatCoins()} profit.")
        log("Manual item added item=${item.key} count=$count ${candidate.summary()}")
    }

    fun resetTrackedItems() {
        state.trackedItemDrops.clear()
        saveState()
        send("Tracked M7 item drops reset.")
        log("Tracked M7 item drops reset")
    }

    fun sendRunSummary(scope: String) {
        val summary = runSummary(scope)
        sendReplacingSummary(
            listOf(
                "${summary.label}: ${summary.runs} runs, ${summary.xp.format()} XP, ${summary.profit.formatCoins()} profit.",
                "Profit/run ${summary.profitPerRun.formatCoins()}, chest ${summary.profitPerChest.formatCoins()}, hour ${summary.profitPerHour.formatCoins()}.",
                "Avg time ${formatDuration(summary.averageRunTimeSeconds)}, XP/hour ${summary.xpPerHour.format()} (${if (scope.equals("session", true)) "active session time" else "recorded run time; window time if absent"}; current profile).",
            )
        )
    }

    fun sendLegacyStatus() {
        val legacy = state.chestProfits.filter { it.accountId.isBlank() || it.profileId.isBlank() }
        send("Unknown ownership: ${legacy.size} retained chests, ${legacy.sumOf { it.profit }.formatCoins()}; ${state.runs.count { it.accountId.isBlank() || it.profileId.isBlank() }} runs.")
        send("All historical counters: ${state.totalChestsOpened} chests; ${state.totalKismetsUsed} Kismets. Missing chest records: ${(state.totalChestsOpened - state.chestProfits.size).coerceAtLeast(0)}.")
    }

    fun sendStatus() {
        send("Status: $status. Prices: ${priceService.health().status}. History: ${historyRepository.error?.message ?: if (historyWriter.dirty) "unsaved changes" else "saved"}.")
    }

    fun sendPricingStatus() {
        val health = priceService.health()
        val last = health.lastQuote
        send("Prices: ${health.status}; Bazaar ${health.bazaarItemCount} items (${bazaarValuation.options[bazaarValuation.get().coerceIn(bazaarValuation.options.indices)]}), auction ${health.auctionItemCount} items (${auctionValuation.options[auctionValuation.get().coerceIn(auctionValuation.options.indices)]}).")
        send("Devonian loading fallback: ${if (allowDevonianPriceFallback.get()) "enabled" else "disabled"}${if (health.fallbackActive) " (active)" else ""}.")
        val refreshedAt = SkyBlockApiPriceDataProvider.refreshedAt
        send("Refresh: ${if (priceRefreshInProgress) "in progress" else if (refreshedAt > 0) "${(System.currentTimeMillis() - refreshedAt) / 1000}s ago" else "waiting"}${priceRefreshError?.let { "; $it" }.orEmpty()}. Pending chests: ${state.pendingChestClaims.size}.")
        if (state.lastMissingItemIds.isNotEmpty()) send("Last chest missing: ${state.lastMissingItemIds.joinToString()}.")
        if (last != null) send("Last quote: ${last.itemId} via ${last.source} at ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(last.quotedAt), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_TIME)}.")
    }

    fun onInventoryClick(containerId: Int, slotId: Int, button: Int, clickType: ContainerInput) {
        if (!isEnabled() || !trackChestProfit.get()) return
        val screen = currentChestScreen() ?: return
        if (screen.menu.containerId != containerId || !ChestConfirmation.acceptsClick(button, clickType.name)) return
        val title = screen.title.string
        if (title == "Croesus") {
            clearPendingChestTracking()
            return
        }
        if (title.matches(runChestRegex)) {
            cacheClickedCroesusChest(screen, slotId)
            return
        }
        if (!chestNames.contains(title)) return

        log("Inventory click title=$title slot=$slotId button=$button type=$clickType pending=${pendingChestProfit?.summary()}")
        if (recordKismetUseIfClicked(screen, slotId)) return
        if (slotId != 31) return

        val parsedCandidate = runCatching { parseChestProfit(screen) }
            .onFailure { log("Chest claim parse failed: ${it.stackTraceToString()}") }
            .getOrNull()
        val candidate = parsedCandidate
        val enrichedCandidate = candidate?.withTrackedDropsFrom(parsedCandidate)
        if (enrichedCandidate == null) {
            log("Chest claim click ignored: no profit candidate for title=$title")
            return
        }

        claimAttempts.begin(containerId, claimIdentity(enrichedCandidate), System.currentTimeMillis(), enrichedCandidate)
    }

    private fun claimIdentity(candidate: ChestProfitCandidate): String =
        "${mc.player?.uuid}:${activeSkyBlockProfile()?.first}:$chestContextId:${candidate.chestName}"

    private fun recordKismetUseIfClicked(screen: AbstractContainerScreen<*>, slotId: Int): Boolean {
        val stack = screen.menu.slots.getOrNull(slotId)?.item ?: return false
        val lore = plainLore(stack)
        if (slotId != 50 || ChestConfirmation.rerolled(lore) ||
            !(stack.hoverName.string.cleanMc() + lore.joinToString()).contains("Kismet", true)) return false
        val candidate = parseChestProfit(screen) ?: return true
        rerollAttempts.begin(screen.menu.containerId, claimIdentity(candidate), System.currentTimeMillis(),
            candidate.chestName to priceService.quote("KISMET_FEATHER"))
        return true
    }

    fun onServerInventoryUpdate(containerId: Int) {
        if (!isEnabled() || !trackChestProfit.get()) return
        val screen = currentChestScreen() ?: return
        if (screen.menu.containerId != containerId) return
        val now = System.currentTimeMillis()
        val stacks = screen.menu.items.take(chestContainerSlotCount(screen.menu.items.size))
        val reroll = rerollAttempts.confirm(containerId, now) { (name, _) ->
            screen.title.string == name && screen.menu.items.getOrNull(50)?.let {
                ChestConfirmation.rerolled(plainLore(it))
            } == true
        }
        reroll?.let(::recordConfirmedKismet)
        claimAttempts.confirm(containerId, now) { candidate ->
            stacks.any { stack ->
                val sameChest = screen.title.string == candidate.chestName ||
                    stack.hoverName.string.cleanMc() == candidate.chestName
                sameChest && plainLore(stack).any { it == "Already opened!" }
            }
        }?.let { recordChestProfit(it.value, "server-confirmed", it.claimId) }
        lastChestScanKey = ""
    }

    private fun recordConfirmedKismet(attempt: ChestAttemptTracker.Attempt<Pair<String, PriceQuote>>) {
        val (name, quote) = attempt.value
        val now = System.currentTimeMillis()
        val cost = quote.unitPrice?.roundToLong()?.coerceAtLeast(0) ?: 0L
        state.totalKismetsUsed++
        state.kismetUses.add(KismetUseSample(now, name, cost, data?.profileName.orEmpty(), chestContextFloor,
            currentAccountId(), currentProfileId(), attempt.claimId, quote.available, quote.source))
        ensureSessionStarted(now, "kismet-use")
        saveState()
    }

    fun onFakeOpenKey(screen: AbstractContainerScreen<*>): Boolean {
        log("Raw H fake-open key received title=${screen.title.string}")
        return fakeOpenChest(screen)
    }

    fun matchesFakeOpenKey(event: KeyEvent): Boolean = fakeOpenKey.matches(event)

    fun onResetSelectionKey(screen: AbstractContainerScreen<*>): Boolean {
        val selected = selectedCroesusCandidate ?: return false
        selectedCroesusCandidate = null
        log("Cleared selected Croesus chest by key title=${screen.title.string} previous=${selected.summary()}")
        return false
    }

    fun fakeOpenCurrentScreen() {
        fakeOpenChest()
    }

    private fun fakeOpenChest(screenOverride: AbstractContainerScreen<*>? = null): Boolean {
        if (!isEnabled() || !trackChestProfit.get()) {
            send("Chest profit tracking is disabled.")
            return false
        }

        val screen = screenOverride ?: currentChestScreen()
        if (screen == null) {
            send("No chest screen open.")
            log("Fake open ignored: no container screen")
            return false
        }

        val title = screen.title.string
        val parsedDirectCandidate = if (chestNames.contains(title)) {
            runCatching { parseChestProfit(screen, verbose = true) }
                .onFailure { log("Fake open parse failed: ${it.stackTraceToString()}") }
                .getOrNull()
        } else {
            null
        }
        val candidate = runCatching {
            when {
                chestNames.contains(title) -> parsedDirectCandidate
                title.matches(runChestRegex) -> {
                    chestContextFloor = DungeonActivityDetector.detectFloor(null, null, title, emptyList()) ?: "N/A"
                    val candidates = parseCroesusCandidatesFromScreen(screen)
                    selectedCroesusCandidate?.chestName?.let { candidates[it] }
                        ?: candidates.values.maxByOrNull { it.profit }
                }
                else -> null
            }
        }.onFailure {
            log("Fake open parse failed: ${it.stackTraceToString()}")
        }.getOrNull()

        if (candidate == null) {
            log("Fake open ignored: unsupported title=$title")
            return false
        }

        recordChestProfit(candidate.withTrackedDropsFrom(parsedDirectCandidate), "fake-open")
        return true
    }

    private fun cacheClickedCroesusChest(screen: AbstractContainerScreen<*>, slotId: Int) {
        val clickedStack = screen.menu.slots.getOrNull(slotId)?.item ?: screen.menu.items.getOrNull(slotId) ?: return
        val chestName = clickedStack.customName?.string ?: clickedStack.hoverName.string
        if (!chestNames.contains(chestName)) return

        chestContextFloor = DungeonActivityDetector.detectFloor(null, null, screen.title.string, emptyList()) ?: "N/A"
        val parsedFromScreen = parseCroesusCandidatesFromScreen(screen)
        val parsedCandidates = parsedFromScreen.toMutableMap()


        if (parsedCandidates.isNotEmpty()) lastCroesusCandidates = parsedCandidates
        val candidate = parsedCandidates[chestName] ?: return
        selectedCroesusCandidate = candidate
        log("Selected Croesus chest from click title=${screen.title.string} slot=$slotId ${candidate.summary()}")
    }

    private data class ProfitHudRow(
        val id: String,
        val label: String,
        val value: String,
        val suffix: String = "",
        val classes: List<ClassProgress> = emptyList(),
    )

    private fun normalizedHudLineOrder(): List<String> = HudGeometry.normalizeOrder(state.hudLineOrder.orEmpty())

    fun renderHudOrderOverlay(graphics: GuiGraphicsExtractor, mouseX: Double, mouseY: Double) {
        if (!renderHud.get() || !isEnabled()) return
        drawDirect(graphics)
        val rows = editableHudLineBounds()
        if (rows.isEmpty()) return
        val dragging = draggedHudLineId != null
        val hoverId = if (isShiftDown() || dragging) hudLineAt(mouseX, mouseY)?.id() else null
        if (dragging) hoveredHudLineId = hoverId
        graphics.drawString(mc.font, Component.literal(HUD_ORDER_HINT), hudX().toInt(),
            (hudY() - (mc.font.lineHeight + HUD_LINE_GAP + 2) * hudRenderScale()).toInt().coerceAtLeast(2), HudGeometry.ACCENT, true)
        graphics.pose().pushMatrix()
        graphics.pose().translate(hudX().toFloat(), hudY().toFloat())
        graphics.pose().scale(hudRenderScale(), hudRenderScale())
        for (row in rows) {
            val highlight = when {
                row.id() == draggedHudLineId -> 0x556ECAFD
                dragging && row.id() == hoveredHudLineId -> 0x556FF4C6
                !dragging && row.id() == hoverId -> 0x33FFFFFF
                else -> continue
            }
            graphics.fill(row.x(), row.y(), row.right(), row.bottom(), highlight)
        }
        graphics.pose().popMatrix()
    }

    fun onHudOrderMouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, shiftDown: Boolean): Boolean {
        if (!shiftDown || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        val hit = hudLineAt(event.x(), event.y()) ?: return false
        draggedHudLineId = hit.id()
        hoveredHudLineId = hit.id()
        return true
    }

    fun onHudModeButtonPressed(): Boolean {
        if (mc.screen !is AbstractContainerScreen<*>) return false
        val mouse = currentScaledMousePosition()
        if (!hudModeButtonContains(mouse.first, mouse.second)) return false
        toggleHudViewMode()
        return true
    }

    fun onHudOrderMouseDragged(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggedHudLineId == null) return false
        hoveredHudLineId = hudLineAt(event.x(), event.y())?.id()
        return true
    }

    fun onHudOrderMouseReleased(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        val draggedId = draggedHudLineId ?: return false
        val targetId = hudLineAt(event.x(), event.y())?.id() ?: hoveredHudLineId
        draggedHudLineId = null
        hoveredHudLineId = null
        if (targetId != null && targetId != draggedId && moveHudLine(draggedId, targetId)) {
            log("HUD line order changed dragged=$draggedId target=$targetId order=${normalizedHudLineOrder().joinToString(",")}")
        }
        return true
    }

    private fun editableHudLineBounds(): List<HudGeometry.Row> {
        if (!renderHud.get() || !isEnabled() || currentHudMode() == HUD_MODE_ITEMS) return emptyList()
        return hudPanelLayout(buildProfitHudTopRows(), buildProfitHudBottomRows()).rows()
            .filter { it.id() in DEFAULT_HUD_LINE_ORDER }
    }

    private fun hudLineAt(mouseX: Double, mouseY: Double): HudGeometry.Row? =
        editableHudLineBounds().firstOrNull {
            it.contains((mouseX - hudX()) / hudRenderScale(), (mouseY - hudY()) / hudRenderScale())
        }

    private fun moveHudLine(draggedId: String, targetId: String): Boolean {
        if (draggedId !in DEFAULT_HUD_LINE_ORDER || targetId !in DEFAULT_HUD_LINE_ORDER) return false
        val previous = normalizedHudLineOrder()
        val order = HudGeometry.move(previous, draggedId, targetId)
        if (order == previous) return false
        state.hudLineOrder = order.toMutableList()
        saveState()
        return true
    }

    private fun isShiftDown(): Boolean {
        val handle = mc.window.handle()
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
    }

    private fun scanCurrentChestScreen() {
        if (!isEnabled() || !trackChestProfit.get()) return
        val screen = currentChestScreen()
        if (screen == null) {
            pendingChestProfit = null
            selectedCroesusCandidate = null
            lastCroesusCandidates = emptyMap()
            lastChestScanKey = ""
            lastChestScanCandidate = null
            return
        }

        val title = screen.title.string
        if (!chestNames.contains(title)) {
            pendingChestProfit = null
            lastChestScanKey = ""
            lastChestScanCandidate = null
            return
        }

        val scanKey = chestScreenScanKey(screen)
        if (scanKey == lastChestScanKey) {
            pendingChestProfit = lastChestScanCandidate
            return
        }

        val candidate = runCatching { parseChestProfit(screen) }
            .onFailure { log("Chest screen parse failed: ${it.stackTraceToString()}") }
            .getOrNull()
        lastChestScanKey = scanKey
        lastChestScanCandidate = candidate
        pendingChestProfit = candidate
        val now = System.currentTimeMillis()
        if (candidate != null && now - lastChestScreenLog >= 2_000) {
            lastChestScreenLog = now
            log("Parsed chest screen ${candidate.summary()}")
        }
    }

    private fun parseChestProfit(screen: AbstractContainerScreen<*>, verbose: Boolean = false): ChestProfitCandidate? {
        val title = screen.title.string
        val items = screen.menu.items
        val reward = items.getOrNull(31) ?: return null
        val plainLore = plainLore(reward)
        if (!plainLore.any { it == "Cost" }) return null

        val costs = chestCosts(plainLore) ?: return null
        val parsedItems = mutableListOf<ChestProfitItem>()
        val unknownItems = mutableListOf<String>()
        val trackedDrops = mutableListOf<TrackedDrop>()
        val containerSlotCount = chestContainerSlotCount(items.size)

        for (stack in (9..18).mapNotNull(items::getOrNull)) {
            if (stack.isEmpty || stack.item == Items.GRAY_STAINED_GLASS_PANE || stack.item == Items.BLACK_STAINED_GLASS_PANE) continue
            val parsed = parseChestItem(stack)
            if (parsed == null) unknownItems.add("UNPARSED_REWARD:${stack.hoverName.string.cleanMc()}")
            parsed?.let {
                parsedItems.add(it)
                it.trackedDrop?.let(trackedDrops::add)
                if (verbose) {
                    log("Chest screen item parsed chest=$title name=${stack.hoverName.string.cleanMc()} id=${it.itemId} amount=${it.amount} essence=${it.essence}")
                }
            }
        }

        if (parsedItems.isEmpty() && unknownItems.isEmpty()) return null
        val candidate = calculateChestCandidate(title, parsedItems, costs, containerSlotCount, trackedDrops)
        return candidate.copy(missingItemIds = candidate.missingItemIds + unknownItems)
    }

    private fun chestScreenScanKey(screen: AbstractContainerScreen<*>): String {
        val items = screen.menu.items
        val containerSlotCount = chestContainerSlotCount(items.size)
        return buildString {
            append(screen.menu.containerId)
            append('|')
            append(screen.title.string)
            append('|')
            append(includeEssenceProfit.get())
            append('|')
            append(includeDungeonKeyCost.get())
            append(bazaarValuation.get())
            append(auctionValuation.get())
            append(allowDevonianPriceFallback.get())
            append(missingPriceBehavior.get())
            append(System.currentTimeMillis() / 1000)
            for (index in 0 until containerSlotCount) {
                val stack = items.getOrNull(index) ?: continue
                append('|')
                append(index)
                append(':')
                append(stack.count)
                append(':')
                append(stack.hoverName.string.cleanMc())
                append(plainLore(stack).joinToString("\u0001"))
                append(':')
                append(ItemUtils.skyblockId(stack).orEmpty())
            }
            val reward = items.getOrNull(31)
            if (reward != null) {
                append("|reward:")
                append(reward.count)
                append(':')
                append(reward.hoverName.string.cleanMc())
                append(':')
                append(plainLore(reward).joinToString("\u0001"))
            }
        }
    }

    private fun parseCroesusCandidatesFromScreen(screen: AbstractContainerScreen<*>): Map<String, ChestProfitCandidate> = runCatching {
        val items = screen.menu.items.take(chestContainerSlotCount(screen.menu.items.size))
        items.mapIndexedNotNull { slot, stack ->
            val name = stack.customName?.string ?: return@mapIndexedNotNull null
            if (!chestNames.contains(name)) return@mapIndexedNotNull null
            parseCroesusChestItem(name, stack, slot)
        }.associateBy { it.chestName }
    }.onFailure {
        log("Croesus parse failed: ${it.stackTraceToString()}")
    }.getOrDefault(emptyMap())

    private fun ChestProfitCandidate.withTrackedDropsFrom(other: ChestProfitCandidate?): ChestProfitCandidate {
        if (trackedDrops.isNotEmpty() || other == null || other.trackedDrops.isEmpty()) return this
        return copy(trackedDrops = other.trackedDrops)
    }

    private fun parseCroesusChestItem(chestName: String, stack: ItemStack, slot: Int): ChestProfitCandidate? {
        val parsed = ChestLoreParser.parse(plainLore(stack), ::parseCroesusLoreItem)
        if (parsed.error != null) { log("$chestName: ${parsed.error}"); return null }
        if (parsed.rewards.isEmpty() && parsed.unknownRewards.isEmpty()) return null
        val candidate = calculateChestCandidate(chestName, parsed.rewards,
            ChestCosts(parsed.coinCost!!, parsed.requiresKey), slot, parsed.rewards.mapNotNull { it.trackedDrop })
        return if (parsed.unknownRewards.isEmpty()) candidate else candidate.copy(
            missingItemIds = candidate.missingItemIds + parsed.unknownRewards.map { "UNPARSED_REWARD:$it" })
    }

    private fun parseCroesusLoreItem(line: String): ChestProfitItem? {
        val item = DungeonRewardParser.parse(line) ?: return null
        return ChestProfitItem(item.itemId, item.quantity, item.essence, trackedDropFor(item.itemId, line))
    }

    private fun chestContainerSlotCount(totalSlots: Int): Int {
        val withoutPlayerInventory = totalSlots - 36
        return when {
            withoutPlayerInventory >= 9 -> withoutPlayerInventory
            totalSlots >= 54 -> 54
            else -> totalSlots
        }
    }

    private fun parseChestItem(stack: ItemStack): ChestProfitItem? {
        val name = stack.hoverName.string.cleanMc()
        val lore = plainLore(stack)
        val item = DungeonRewardParser.parse(name, stack.count.coerceAtLeast(1), ItemUtils.skyblockId(stack), lore) ?: return null
        val essence = if (item.essence && item.quantity == 1) lore.firstNotNullOfOrNull {
            DungeonRewardParser.parse(it)?.takeIf { reward -> reward.essence }
        } else null
        val reward = essence ?: item
        return ChestProfitItem(reward.itemId, reward.quantity, reward.essence, trackedDropFor(reward.itemId, name))
    }

    private fun trackedDropFor(itemId: String, displayName: String): TrackedDrop? {
        val definition = TrackedDungeonItems.find(itemId)
            ?: TrackedDungeonItems.find(displayName.cleanMc())
            ?: return null
        return TrackedDrop(definition.key, definition.displayName)
    }

    private fun chestCosts(lore: List<String>): ChestCosts? {
        val coins = ChestLoreParser.coinCost(lore) ?: return null
        return ChestCosts(coins, "Dungeon Chest Key" in lore.drop(lore.indexOf("Cost") + 1))
    }

    private fun calculateChestCandidate(
        chestName: String,
        items: List<ChestProfitItem>,
        costs: ChestCosts,
        scannedSlots: Int,
        trackedDrops: List<TrackedDrop>,
    ): ChestProfitCandidate {
        priceService.beginCalculation()
        val keyQuote = if (costs.requiresKey && includeDungeonKeyCost.get()) priceService.quote("DUNGEON_CHEST_KEY") else null
        val calculation = chestProfitCalculator.calculate(
            ChestCalculationInput(
                chestName = chestName,
                rewards = items.map { ParsedChestReward(it.itemId, it.amount, it.essence) },
                chestCoinCost = costs.coinCost,
                keyCost = keyQuote?.unitPrice?.roundToLong() ?: 0L,
            ),
            includeEssence = includeEssenceProfit.get(),
            includeKeyCost = includeDungeonKeyCost.get(),
            includeKismetCost = false,
        )
        val keyMissing = keyQuote != null && !keyQuote.available
        val missing = (calculation.missingItemIds + if (keyMissing) listOf("DUNGEON_CHEST_KEY") else emptyList()).distinct()
        return ChestProfitCandidate(
            chestName = chestName,
            itemCount = items.size,
            scannedSlots = scannedSlots,
            trackedDrops = trackedDrops,
            chestCoinCost = calculation.chestCoinCost,
            keyCost = calculation.keyCost,
            missingItemIds = missing,
            pricedItems = calculation.pricedItems,
        )
    }

    private fun plainLore(stack: ItemStack): List<String> = ItemUtils.lore(stack)?.map { it.cleanMc() } ?: emptyList()

    private fun currentChestScreen(): AbstractContainerScreen<*>? = mc.screen as? AbstractContainerScreen<*>

    private fun recordChestProfit(candidate: ChestProfitCandidate, source: String, claimKey: String = claimIdentity(candidate)) {
        val now = System.currentTimeMillis()
        val context = ClaimContext(claimKey, currentAccountId(), currentProfileId(), data?.profileName.orEmpty(), chestContextFloor)
        when (val result = ChestRecorder.record(state, candidate, context, source, now,
            selectedMissingPricePolicy(), includeKismetCost.get())) {
            is ClaimResult.Recorded -> {
                ensureSessionStarted(now, "chest-profit-$source")
                saveState()
            }
            is ClaimResult.Incomplete -> {
                ensureSessionStarted(now, "chest-awaiting-prices-$source")
                state.lastMissingItemIds = result.missing.toMutableList()
                saveState()
                send("Chest saved; waiting for prices: ${result.missing.joinToString()}.")
            }
            ClaimResult.Duplicate -> log("Duplicate claim ignored: $claimKey")
        }
    }

    private fun selectedMissingPricePolicy(): MissingPriceBehavior =
        MissingPriceBehavior.entries.getOrElse(missingPriceBehavior.get()) { MissingPriceBehavior.MARK_INCOMPLETE }

    private fun activeSkyBlockProfile(): Pair<UUID?, String>? {
        val api = tech.thatgravyboat.skyblockapi.api.profile.profile.ProfileAPI
        return if (api.isLoaded) api.profileId to api.profileName.orEmpty() else null
    }

    private fun profileFailureMessage(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    private fun recordSample(profile: ProfileData, recordObservedSample: Boolean) {
        val profileId = profile.profileId.replace("-", "")
        val now = System.currentTimeMillis()
        DungeonClasses.recordXpSample(state, profile, now)
        val previousAt = state.lastBaselineAt
        state.lastBaselineAt = now
        if (profileId.isBlank() || state.lastProfileId != profileId || state.lastPlayerUuid != profile.playerUuid) {
            state.lastProfileId = profileId
            state.lastPlayerUuid = profile.playerUuid
            state.lastCatacombsXp = profile.catacombsExperience
            saveState()
            return
        }

        val delta = profile.catacombsExperience - state.lastCatacombsXp
        state.lastCatacombsXp = profile.catacombsExperience
        if (!recordObservedSample) {
            log("Profile XP baseline updated without observed sample rawDelta=$delta floor=${floorValue()}")
            saveState()
            return
        }

        if (delta > 0 && previousAt > 0) {
            state.xpIntervals.add(XpInterval(previousAt, now, delta, profile.playerUuid.replace("-", ""), profileId))
        }
        saveState()
    }

    fun parseDungeonCompletionMessage(message: Component) {
        if (!isEnabled()) return
        val text = message.string.cleanMc()
        text.lines().forEach { line ->
            if (trackChestProfit.get() && ChestConfirmation.kismetUsed(line)) {
                rerollAttempts.confirmChat(System.currentTimeMillis()) { true }?.let(::recordConfirmedKismet)
            }
            if (trackChestProfit.get()) ChestConfirmation.claimedTier(line)?.let { tier ->
                claimAttempts.confirmChat(System.currentTimeMillis()) { it.chestName.equals(tier, true) }
                    ?.let { recordChestProfit(it.value, "server-chat", it.claimId) }
            }
            parseDungeonCompletionLine(line)
        }
        // Class rewards can follow the Cata XP in another server message.
        val run = ownedRuns().lastOrNull { it.timestamp == lastDungeonCompletionChatAt }
        if (CompletionParser.recordClassExperience(message, run, System.currentTimeMillis())) {
            saveState()
            log("Recorded class XP floor=${run!!.floorLabel} played=${run.dungeonClass} classes=${run.classExperience}")
        }
    }

    private fun parseDungeonCompletionLine(message: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val parsed = CompletionParser.accept(
            CompletionState(pendingCompletionAt, pendingCompletionFloor, pendingCompletionTimeSeconds,
                pendingCompletionScore, pendingCompletionGrade), message, timestamp)
        pendingCompletionAt = parsed.state.timestamp
        pendingCompletionFloor = parsed.state.floor
        pendingCompletionTimeSeconds = parsed.state.seconds
        pendingCompletionScore = parsed.state.score
        pendingCompletionGrade = parsed.state.grade
        if (parsed.state.floor.isNotBlank()) detectedFloorLabel = parsed.state.floor
        val cataXp = parsed.completion?.xp ?: return false

        val floor = pendingCompletionFloor.ifBlank { floorValue() }
        val dedupeKey = "$floor:$cataXp:$pendingCompletionTimeSeconds:$pendingCompletionScore"
        if (dedupeKey == lastDungeonCompletionChat && timestamp - lastDungeonCompletionChatAt < 10_000) {
            log("Duplicate dungeon completion chat ignored")
            return false
        }
        if (hasRunRecord(floor, cataXp, pendingCompletionTimeSeconds, pendingCompletionScore, timestamp)) {
            log("Existing dungeon completion record ignored")
            return false
        }

        clearPendingChestTracking()
        completedDungeon = mc.level?.let { it to floor }
        CompletionParser.dungeonClass(message)?.let { detectedDungeonClass = it }
        chestContextFloor = floor
        lastDungeonCompletionChat = dedupeKey
        lastDungeonCompletionChatAt = timestamp
        lastDungeonCompletionRawXp = cataXp
        lastDungeonCompletionNormalizedXp = cataXp.coerceAtLeast(0L)
        val runStartedAt = if (pendingCompletionTimeSeconds > 0) {
            timestamp - pendingCompletionTimeSeconds * 1000L
        } else {
            timestamp
        }
        ensureSessionStarted(runStartedAt, "dungeon-completion")
        state.runs.add(
            DungeonRunRecord(
                accountId = currentAccountId(),
                profileId = currentProfileId(),
                source = "completion-chat",
                timestamp = timestamp,
                floorLabel = floor,
                runTimeSeconds = pendingCompletionTimeSeconds,
                score = pendingCompletionScore,
                grade = pendingCompletionGrade,
                rawCataXp = cataXp,
                normalizedCataXp = lastDungeonCompletionNormalizedXp,
                dungeonClass = CompletionParser.dungeonClass(message) ?: detectedDungeonClass ?: data?.selectedDungeonClass.orEmpty(),
                partyClasses = currentPartyClasses(),
            )
        )
        incrementCroesusUnclaimedCount("dungeon-completion")
        saveState()
        log("Recorded dungeon completion chat XP raw=$cataXp recordedXp=$lastDungeonCompletionNormalizedXp floor=$floor time=$pendingCompletionTimeSeconds score=$pendingCompletionScore")
        return true
    }

    private fun hasRunRecord(floor: String, rawXp: Long, runTimeSeconds: Int, score: Int, timestamp: Long): Boolean =
        state.runs.any { RunReconciler.matches(it, floor, rawXp, runTimeSeconds, score, timestamp) }

    fun importRecentLogs(manual: Boolean) {
        if (!logsDir.isDirectory) {
            if (manual) send("No Minecraft logs folder found.")
            return
        }
        val progress = state.importedLogFiles.toMap()
        thread(name = "DPH Log Import", isDaemon = true) {
            val files = logsDir.listFiles()?.filter {
                it.isFile && (it.extension == "log" || it.name.endsWith(".log.gz"))
            }?.sortedBy { it.name }.orEmpty()
            val importedRuns = mutableListOf<ImportedDungeonRun>()
            val completed = mutableMapOf<String, String>()
            var undated = 0
            var failed = 0
            for (file in files) {
                val timeline = LogTimeline.forArchive(file.name) ?: run { undated++; continue }
                val fingerprint = "${file.length()}:${file.lastModified()}"
                if (!manual && progress[file.name] == fingerprint) continue
                runCatching {
                    var pending = CompletionState()
                    val fileRuns = mutableListOf<ImportedDungeonRun>()
                    file.forEachLogLine { line ->
                        val timestamp = timeline.timestamp(line)
                        val chat = extractLogChat(line)
                        if (timestamp != null && chat != null) {
                            val parsed = CompletionParser.accept(pending, chat, timestamp)
                            pending = parsed.state
                            parsed.completion?.let { run ->
                                fileRuns.add(ImportedDungeonRun(run.timestamp, run.metadata.floor.ifBlank { "N/A" },
                                    run.metadata.seconds, run.metadata.score, run.metadata.grade, run.xp))
                            }
                        }
                    }
                    importedRuns.addAll(fileRuns)
                    completed[file.name] = fingerprint
                }.onFailure { failed++; log("Log import failed file=${file.name}: $it") }
            }
            mc.execute {
                state.importedLogFiles.putAll(completed)
                mergeImportedLogRuns(importedRuns, completed.size, manual)
                if (manual && (undated > 0 || failed > 0)) send("Skipped $undated undated logs; $failed files failed. Imported ownership remains unknown.")
            }
        }
    }

    private fun mergeImportedLogRuns(importedRuns: List<ImportedDungeonRun>, fileCount: Int, manual: Boolean) {
        var imported = 0
        for (run in importedRuns) {
            if (hasRunRecord(run.floorLabel, run.rawCataXp, run.runTimeSeconds, run.score, run.timestamp)) continue
            val normalized = run.rawCataXp.coerceAtLeast(0L)
            state.runs.add(
                DungeonRunRecord(
                    source = "log-import",
                    timestamp = run.timestamp,
                    floorLabel = run.floorLabel,
                    runTimeSeconds = run.runTimeSeconds,
                    score = run.score,
                    grade = run.grade,
                    rawCataXp = run.rawCataXp,
                    normalizedCataXp = normalized,
                )
            )
            imported++
        }
        state.lastLogImportAt = System.currentTimeMillis()
        saveState()
        log("Log import complete files=$fileCount parsedRuns=${importedRuns.size} importedRuns=$imported")
        if (manual) send("Imported $imported dungeon runs from recent logs.")
    }

    private inline fun File.forEachLogLine(action: (String) -> Unit) {
        val input = if (name.endsWith(".gz")) GZIPInputStream(inputStream()) else inputStream()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach(action)
        }
    }

    private fun extractLogChat(line: String): String? {
        val marker = "[CHAT]"
        val index = line.indexOf(marker)
        if (index < 0) return null
        return line.substring(index + marker.length).trim()
    }

    private fun effectiveXpPerRun(): Long? {
        if (xpModeValue() == "Hardcoded") return hardcodedXpPerRunValue()
        return scopedObservedXp().takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToLong()
    }

    private fun currentAccountId(): String = mc.player?.uuid?.toString()?.replace("-", "").orEmpty()
    private fun currentProfileId(): String = activeSkyBlockProfile()?.first?.toString()?.replace("-", "").orEmpty()
    private fun owns(account: String, profile: String): Boolean =
        account.isNotBlank() && profile.isNotBlank() && account == currentAccountId() && profile == currentProfileId()
    private fun ownedRuns() = HistoryQueries.runs(state, currentAccountId(), currentProfileId())
    private fun ownedChests() = HistoryQueries.chests(state, currentAccountId(), currentProfileId())
    private fun ownedDrops() = HistoryQueries.drops(state, currentAccountId(), currentProfileId())

    private fun scopedRuns(now: Long = System.currentTimeMillis()): List<DungeonRunRecord> {
        if (state.chestProfitWindowMillis > 0L) {
            val cutoff = now - state.chestProfitWindowMillis
            return ownedRuns().filter { it.timestamp >= cutoff }
        }
        if (trackerModeValue() == "Total") return ownedRuns()
        if (sessionStartedAt <= 0L) return emptyList()
        return ownedRuns().filter { it.timestamp >= sessionStartedAt }
    }

    private fun progressionRuns(runs: List<DungeonRunRecord> = ownedRuns(), useLastRun: Boolean = isSessionScope()): List<DungeonRunRecord> {
        val now = System.currentTimeMillis()
        return HistoryQueries.progressionRuns(runs, floorValue(), state.averagingResetAt, now, useLastRun) {
            isInSelectedTrackerScope(it, now)
        }
    }

    private fun scopedObservedXp(): List<Pair<Long, Long>> =
        HistoryQueries.observedXp(progressionRuns(), floorValue(), state.averagingResetAt)

    private fun isInSelectedTrackerScope(timestamp: Long, now: Long): Boolean = when {
        timestamp > now -> false
        state.chestProfitWindowMillis > 0L -> timestamp >= now - state.chestProfitWindowMillis
        trackerModeValue() == "Total" -> true
        sessionStartedAt > 0L -> timestamp >= sessionStartedAt
        else -> false
    }

    private fun scopedKismetUses(now: Long = System.currentTimeMillis()): List<KismetUseSample> =
        HistoryQueries.kismets(state, currentAccountId(), currentProfileId()).filter { isInSelectedTrackerScope(it.timestamp, now) }

    private fun pendingChestCount(): Int = state.pendingChestClaims.count {
        owns(it.context.accountId, it.context.profileId) && isInSelectedTrackerScope(it.timestamp, System.currentTimeMillis())
    }

    private fun scopedRunCount(): Int = scopedRuns().size

    private fun scopedRunScopeLabel(): String = when {
        state.chestProfitWindowMillis > 0L -> formatProfitWindow(state.chestProfitWindowMillis)
        trackerModeValue() == "Total" -> "total"
        else -> "session"
    }

    private fun chestProfitStats(): ChestProfitStats {
        val now = System.currentTimeMillis()
        return HistoryQueries.profitStats(state, currentAccountId(), currentProfileId(), scopedRunScopeLabel(),
            trackerModeValue() == "Total" && state.chestProfitWindowMillis == 0L) { isInSelectedTrackerScope(it, now) }
    }

    private fun runSummary(scope: String): RunSummary {
        val now = System.currentTimeMillis()
        val cutoff = when (scope.lowercase(Locale.ROOT)) {
            "session" -> sessionStartedAt
            "weekly" -> now - 7L * DAY_MILLIS
            else -> now - DAY_MILLIS
        }
        val label = when (scope.lowercase(Locale.ROOT)) {
            "session" -> "Session"
            "weekly" -> "Last 7 days"
            else -> "Last 24 hours"
        }
        if (scope.equals("session", true) && sessionStartedAt <= 0L) {
            return RunSummary(label, 0, 0L, 0L, 0, 0, 0L, 0L, 0L, 0L)
        }
        val runs = ownedRuns().filter { it.timestamp >= cutoff }
        val chests = ownedChests().filter { it.timestamp >= cutoff }
        val runCount = runs.size
        val chestCount = chests.size
        val xp = runs.sumOf { it.rawCataXp }
        val profit = chests.sumOf { it.profit }
        val runSeconds = runs.sumOf { it.runTimeSeconds.coerceAtLeast(0) }
        val elapsedSeconds = when {
            scope.equals("session", true) -> sessionElapsedSeconds().coerceAtLeast(1L).toInt()
            runSeconds > 0 -> runSeconds
            else -> ((now - cutoff) / 1000L).coerceAtLeast(1L).toInt()
        }
        val hours = elapsedSeconds.toDouble() / 3600.0
        return RunSummary(
            label = label,
            runs = runCount,
            xp = xp,
            profit = profit,
            chests = chestCount,
            averageRunTimeSeconds = runs.map { it.runTimeSeconds }.filter { it > 0 }.averageOrZero().roundToInt(),
            profitPerRun = if (runCount > 0) (profit.toDouble() / runCount).roundToLong() else 0L,
            profitPerChest = if (chestCount > 0) (profit.toDouble() / chestCount).roundToLong() else 0L,
            profitPerHour = if (hours > 0.0) (profit.toDouble() / hours).roundToLong() else 0L,
            xpPerHour = if (hours > 0.0) (xp.toDouble() / hours).roundToLong() else 0L,
        )
    }

    private fun parseProfitWindowDays(input: String): Long? {
        val match = Regex("^(\\d+)(d|day|days|w|week|weeks|m|mo|month|months)?$", RegexOption.IGNORE_CASE).matchEntire(input.trim())
            ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        if (amount <= 0L) return null
        val unit = match.groupValues.getOrNull(2)?.lowercase(Locale.ROOT).orEmpty()
        val days = when {
            unit.startsWith("w") -> amount * 7L
            unit == "m" || unit == "mo" || unit.startsWith("month") -> amount * 30L
            else -> amount
        }
        return days.coerceAtMost(365L)
    }

    private fun formatProfitWindow(windowMillis: Long): String {
        val days = (windowMillis / DAY_MILLIS).coerceAtLeast(1L)
        return if (days % 7L == 0L && days >= 14L) {
            val weeks = days / 7L
            "last $weeks ${if (weeks == 1L) "week" else "weeks"}"
        } else {
            "last $days ${if (days == 1L) "day" else "days"}"
        }
    }

    private fun shouldRenderCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastVisibilityCheckAt <= 50L) return lastVisibilityResult
        lastVisibilityCheckAt = now
        lastVisibilityResult = isEnabled() && (showEverywhere.get() || isDungeonArea())
        return lastVisibilityResult
    }

    private fun drawDirect(graphics: GuiGraphicsExtractor) {
        val (title, topRows, bottomRows) = currentHudContent()
        val layout = hudPanelLayout(topRows, bottomRows)
        val byId = (topRows + bottomRows).associateBy { it.id }
        graphics.pose().pushMatrix()
        graphics.pose().translate(hudX().toFloat(), hudY().toFloat())
        graphics.pose().scale(hudRenderScale(), hudRenderScale())
        graphics.fill(0, 0, layout.width(), layout.height(), HudGeometry.PANEL)
        graphics.fill(HUD_SIDE_PADDING, HUD_TOP_PADDING, HUD_SIDE_PADDING + 3, HUD_TOP_PADDING + 13, HudGeometry.ACCENT)
        drawHudText(graphics, title, HUD_SIDE_PADDING + 9, HUD_TOP_PADDING, HudGeometry.WHITE, HudGeometry.TITLE_SCALE, bold = true)
        if (showFloor.get()) {
            drawHudText(graphics, floorValue(), layout.right(), HUD_TOP_PADDING + 2, HudGeometry.ACCENT,
                HudGeometry.FLOOR_SCALE, rightAligned = true, bold = true)
        }
        for (dividerY in layout.dividers()) {
            graphics.fill(HUD_SIDE_PADDING, dividerY, layout.right(), dividerY + 1, HudGeometry.LINE)
        }
        for (section in layout.sections()) {
            drawHudText(graphics, section.title(), HUD_SIDE_PADDING, section.y(), HudGeometry.ACCENT)
            if (section.scope().isNotEmpty()) {
                val right = layout.right() - if (mc.screen is AbstractContainerScreen<*> && section == layout.sections().last()) HudGeometry.BUTTON_WIDTH + 6 else 0
                drawHudText(graphics, section.scope(), right, section.y(), HudGeometry.ACCENT, rightAligned = true)
            }
        }
        for (box in layout.rows()) {
            val row = byId[box.id()] ?: continue
            if (row.id == "classProgress") {
                drawClassProgress(graphics, box, row)
            } else if (HudGeometry.isLevel(row.id)) {
                val rightAligned = box.x() > HUD_SIDE_PADDING
                val textX = if (rightAligned) box.right() else box.x()
                drawHudText(graphics, row.label, textX, box.y(), HudGeometry.MUTED, rightAligned = rightAligned)
                drawHudText(graphics, row.value, textX, box.y() + 12, HudGeometry.WHITE,
                    HudGeometry.valueScale(row.id), rightAligned, bold = true)
            } else {
                val valueScale = HudGeometry.valueScale(row.id)
                val valueY = box.y() + 2
                val labelY = if (row.id == "profit") box.y() + 4 else valueY
                drawHudText(graphics, row.label, box.x(), labelY, HudGeometry.labelColor(row.id))
                drawHudText(graphics, row.displayValue(), box.right(), valueY,
                    if (row.id == "profit") HudGeometry.PROFIT else HudGeometry.WHITE,
                    valueScale, rightAligned = true, bold = row.id == "profit")
                if (row.id == "levelProgress") {
                    val progress = row.value.removeSuffix("%").toDoubleOrNull() ?: 0.0
                    drawProgressBar(graphics, box.x(), box.y() + 14, box.width(), progress)
                }
            }
        }
        val levels = layout.rows().filter { HudGeometry.isLevel(it.id()) }
        if (levels.size == 2) {
            val arrow = if (levels.first().id() == "currentLevel") ">" else "<"
            drawHudText(graphics, arrow, layout.width() / 2 - 5, levels.first().y() + 12, HudGeometry.ACCENT, 2f)
        }
        if (mc.screen is AbstractContainerScreen<*>) drawHudModeButton(graphics, layout)
        graphics.pose().popMatrix()
    }

    private fun drawProgressBar(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, percent: Double?) {
        graphics.fill(x, y, x + width, y + 7, HudGeometry.LINE)
        val fill = HudGeometry.progressWidth(width, percent ?: 0.0)
        if (fill > 0) graphics.fill(x, y, x + fill, y + 7, HudGeometry.ACCENT)
    }

    private fun Double?.percentText(): String = this?.let { "%.1f%%".format(Locale.US, it) } ?: "N/A"

    private fun drawClassProgress(graphics: GuiGraphicsExtractor, box: HudGeometry.Row, row: ProfitHudRow) {
        val y = box.y() + 4
        if (row.classes.size == 1) {
            val progress = row.classes.single()
            drawHudText(graphics, "${progress.name} level", box.x(), y, HudGeometry.MUTED)
            drawHudText(graphics, "Target", box.right(), y, HudGeometry.MUTED, rightAligned = true)
            drawHudText(graphics, progress.level?.toString() ?: "N/A", box.x(), y + 12, HudGeometry.WHITE, 2f, bold = true)
            drawHudText(graphics, progress.target.toString(), box.right(), y + 12, HudGeometry.WHITE, 2f, rightAligned = true, bold = true)
            drawHudText(graphics, ">", box.x() + box.width() / 2 - 5, y + 12, HudGeometry.ACCENT, 2f)
            drawHudText(graphics, "Target progress", box.x(), y + HudGeometry.LEVEL_HEIGHT + 2, HudGeometry.MUTED)
            drawHudText(graphics, progress.percent.percentText(), box.right(), y + HudGeometry.LEVEL_HEIGHT + 2, HudGeometry.WHITE, rightAligned = true)
            drawProgressBar(graphics, box.x(), y + HudGeometry.LEVEL_HEIGHT + 14, box.width(), progress.percent)
        } else {
            drawHudText(graphics, row.label, box.x(), y, HudGeometry.ACCENT)
            drawHudText(graphics, row.value, box.right(), y, HudGeometry.MUTED, rightAligned = true)
            val labels = row.classes.map { "${it.name} ${it.level ?: "?"}" }
            val barX = box.x() + (labels.maxOfOrNull { mc.font.width(it) } ?: 0) + 6
            val values = row.classes.map { progress ->
                if (!showClassRuns.get()) progress.percent.percentText() else {
                    val full = progress.runs?.let { "%,d".format(Locale.US, it) } ?: "N/A"
                    if (mc.font.width(full) <= (box.right() - barX) / 2) full else progress.runs?.formatCompact() ?: "N/A"
                }
            }
            val valueWidth = maxOf(mc.font.width("100.0%"), values.maxOfOrNull { mc.font.width(it) } ?: 0)
            val barRight = box.right() - valueWidth - 6
            row.classes.forEachIndexed { index, progress ->
                val lineY = y + HudGeometry.SECTION_HEIGHT + index * HudGeometry.ROW_HEIGHT + 2
                drawHudText(graphics, labels[index], box.x(), lineY, HudGeometry.MUTED)
                drawProgressBar(graphics, barX, lineY, (barRight - barX).coerceAtLeast(0), progress.percent)
                drawHudText(graphics, values[index], box.right(), lineY, HudGeometry.WHITE, rightAligned = true)
            }
        }
    }

    private fun ProfitHudRow.displayValue() = if (suffix.isBlank()) value else "$value $suffix"

    private fun drawHudText(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int,
                            textScale: Float = 1f, rightAligned: Boolean = false, bold: Boolean = false) {
        val component = Component.literal(text).withStyle { it.withBold(bold) }
        val textX = if (rightAligned) x - mc.font.width(component) * textScale else x.toFloat()
        graphics.pose().pushMatrix()
        graphics.pose().translate(textX, y.toFloat())
        graphics.pose().scale(textScale, textScale)
        graphics.drawString(mc.font, component, 0, 0, color, true)
        graphics.pose().popMatrix()
    }

    private data class PresentationSnapshot(
        val key: List<Any?>,
        val profitTop: List<ProfitHudRow>, val profitBottom: List<ProfitHudRow>,
        val itemTop: List<ProfitHudRow>, val itemBottom: List<ProfitHudRow>,
    )
    private var presentation: PresentationSnapshot? = null
    private fun presentationSnapshot(): PresentationSnapshot {
        val key = listOf<Any?>(stateRevision, System.currentTimeMillis() / 1000,
            trackingOwner, floorValue(), data, state.hudViewMode, detectedDungeonClass,
            classProgressMode.get(), classTargetLevel.get(), allClassesGoal.get(), showClassRuns.get(),
            renderHud.get(), showEverywhere.get(), targetLevel.get(), showCurrentLevel.get(), showCurrentXp.get(), showLevelProgress.get(), showTarget.get(), showRemaining.get(), showFloor.get(), showXpPerRun.get(), showRunsLeft.get(), showProfile.get(), showLastRun.get(), showObservedCount.get(), xpMode.get(), hardcodedXpPerRun.get(), trackChestProfit.get(), showChestProfit.get(), chestProfitMode.get(), showChestCount.get(), showLastChest.get(), includeEssenceProfit.get(), includeDungeonKeyCost.get(), bazaarValuation.get(), auctionValuation.get(), missingPriceBehavior.get(), allowDevonianPriceFallback.get(), includeKismetCost.get())
        presentation?.takeIf { it.key == key }?.let { return it }
        return PresentationSnapshot(key, rawProfitHudTopRows(), rawProfitHudBottomRows(),
            rawItemTrackerTopRows(), rawItemTrackerRows()).also { presentation = it }
    }
    private fun buildProfitHudTopRows() = presentationSnapshot().profitTop
    private fun buildProfitHudBottomRows() = presentationSnapshot().profitBottom
    private fun buildItemTrackerTopRows() = presentationSnapshot().itemTop
    private fun buildItemTrackerRows() = presentationSnapshot().itemBottom

    private fun currentHudContent(): Triple<String, List<ProfitHudRow>, List<ProfitHudRow>> {
        val snapshot = presentationSnapshot()
        return if (currentHudMode() == HUD_MODE_ITEMS)
            Triple("Dungeon Items", snapshot.itemTop, snapshot.itemBottom)
        else Triple("Dungeon Profit", snapshot.profitTop, snapshot.profitBottom)
    }

    private fun currentHudMode(): String =
        if (state.hudViewMode == HUD_MODE_ITEMS) HUD_MODE_ITEMS else HUD_MODE_PROFIT

    private fun toggleHudViewMode() {
        state.hudViewMode = if (currentHudMode() == HUD_MODE_ITEMS) HUD_MODE_PROFIT else HUD_MODE_ITEMS
        saveState()
        log("HUD view mode toggled mode=${state.hudViewMode}")
    }

    private fun hudPanelLayout(topRows: List<ProfitHudRow>, bottomRows: List<ProfitHudRow>): HudGeometry.Layout {
        val allRows = buildProfitHudTopRows() + buildProfitHudBottomRows() + buildItemTrackerTopRows() + buildItemTrackerRows()
        val labelWidth = allRows.maxOfOrNull { mc.font.width(it.label) } ?: 0
        val valueWidth = allRows.maxOfOrNull {
            ceil(mc.font.width(Component.literal(it.displayValue()).withStyle { style ->
                style.withBold(HudGeometry.isLevel(it.id) || it.id == "profit")
            }) * HudGeometry.valueScale(it.id)).toInt()
        } ?: 0
        val titleWidth = listOf("Dungeon Profit", "Dungeon Items").maxOf {
            ceil(mc.font.width(Component.literal(it).withStyle { style -> style.withBold(true) }) * HudGeometry.TITLE_SCALE).toInt()
        }
        val floorWidth = if (showFloor.get()) ceil(mc.font.width(Component.literal(floorValue())
            .withStyle { it.withBold(true) }) * HudGeometry.FLOOR_SCALE).toInt() else 0
        return HudGeometry.measure(labelWidth, valueWidth, titleWidth, floorWidth,
            topRows.map { it.id }, bottomRows.map { it.id }, currentHudMode() == HUD_MODE_ITEMS,
            scopedRunScopeLabel(), classProgressMode.get() == 2)
    }

    private fun hudModeButtonBounds(layout: HudGeometry.Layout) = HudGeometry.Row("mode",
        layout.right() - HudGeometry.BUTTON_WIDTH, (layout.sections().lastOrNull()?.y() ?: HUD_TOP_PADDING) - 2,
        HudGeometry.BUTTON_WIDTH, HudGeometry.BUTTON_HEIGHT)

    private fun drawHudModeButton(graphics: GuiGraphicsExtractor, layout: HudGeometry.Layout) {
        val label = if (currentHudMode() == HUD_MODE_ITEMS) "Profit" else "Items"
        val box = hudModeButtonBounds(layout)
        graphics.fill(box.x(), box.y(), box.right(), box.bottom(), HudGeometry.LINE)
        drawHudText(graphics, label, box.x() + (box.width() - mc.font.width(label)) / 2, box.y() + 2, HudGeometry.ACCENT)
    }

    private fun hudModeButtonContains(mouseX: Double, mouseY: Double): Boolean {
        if (mc.screen !is AbstractContainerScreen<*> || !renderHud.get() || !isEnabled()) return false
        val (_, topRows, bottomRows) = currentHudContent()
        return hudModeButtonBounds(hudPanelLayout(topRows, bottomRows))
            .contains((mouseX - hudX()) / hudRenderScale(), (mouseY - hudY()) / hudRenderScale())
    }

    private fun currentScaledMousePosition(): Pair<Double, Double> {
        val window = mc.window
        return mc.mouseHandler.getScaledXPos(window) to mc.mouseHandler.getScaledYPos(window)
    }

    private fun rawProfitHudTopRows(): List<ProfitHudRow> {
        val profile = data
        val xpPerRun = effectiveXpPerRun()
        val observedXp = scopedObservedXp()
        val last = observedXp.lastOrNull()
        val remaining = profile?.let { xpRemaining(it.catacombsExperience, targetLevelValue()) } ?: 0L
        val runs = xpPerRun?.takeIf { it > 0 }?.let { ceil(remaining.toDouble() / it.toDouble()).toLong() }
        val currentLevel = profile?.let { currentCataLevel(it.catacombsExperience) } ?: 0
        return orderProfitRows(
            buildList {
                if (!historyRepository.writable || historyRepository.error != null) add(ProfitHudRow("historyError", "History", "Save blocked: /dph"))
                add(if (isSessionScope()) ProfitHudRow("sessionTime", "Session time", sessionDurationText())
                    else ProfitHudRow("sessionTime", "Run time", HistoryQueries.runTimeSeconds(scopedRuns())
                        .takeIf { it > 0 }?.let(::formatSessionDuration) ?: "N/A"))
                if (showCurrentLevel.get()) add(ProfitHudRow("currentLevel", "Cata level", if (profile == null) "N/A" else currentLevel.toString()))
                if (showTarget.get()) add(ProfitHudRow("target", "Target", targetLevelValue().toString()))
                if (showLevelProgress.get()) add(ProfitHudRow("levelProgress", "Next level", profile?.let { DungeonLevels.nextLevelProgress(it.catacombsExperience) }.percentText()))
                if (classProgressMode.get() in 1..2) add(ProfitHudRow("classProgress", "CLASSES",
                    if (showClassRuns.get()) { if (allClassesGoal.get() == 1) "Runs to 50" else "Runs to next" }
                    else if (allClassesGoal.get() == 1) "Level 50" else "Next level", classes = DungeonClasses.progress(
                        profile?.classExperience.orEmpty(), dungeonClassValue(),
                        classProgressMode.get() == 2, classTargetLevel.get().toIntOrNull() ?: 50, allClassesGoal.get() != 1,
                        if (showClassRuns.get()) classXpPerRun() else emptyMap())))
                if (showRunsLeft.get()) add(ProfitHudRow("runsLeft", "Runs left", runs?.takeIf { profile != null }?.formatCompact() ?: "N/A"))
                if (showCurrentXp.get()) add(ProfitHudRow("currentXp", "Cata XP", profile?.catacombsExperience?.formatCompact() ?: "N/A"))
                if (showRemaining.get()) add(ProfitHudRow("remaining", "Remaining XP", if (profile == null) "N/A" else remaining.formatCompact()))
                if (showFloor.get()) add(ProfitHudRow("floor", "Floor", floorValue()))
                if (showXpPerRun.get()) {
                    add(ProfitHudRow("xpPerRun", "XP/run", xpPerRun?.formatCompact() ?: "N/A"))
                    add(ProfitHudRow("xpPerHour", if (isSessionScope()) "XP/h (session)" else "XP/h (runs)", xpPerHourText()))
                }
                if (showProfile.get()) add(ProfitHudRow("profile", "Profile", profile?.profileName ?: "N/A"))
                if (showLastRun.get()) add(ProfitHudRow("lastRun", "Last run", last?.second?.formatCompact() ?: "N/A"))
                if (showObservedCount.get()) add(ProfitHudRow("observedCount", "Runs", scopedRunCount().toString()))
            }
        )
    }

    private fun rawProfitHudBottomRows(): List<ProfitHudRow> {
        val stats = chestProfitStats()
        return orderProfitRows(
            buildList {
                if (showChestProfit.get()) {
                    add(ProfitHudRow("profit", "Profit", stats.profit.formatCompactCoins()))
                    add(ProfitHudRow("avgChest", "Avg chest", stats.average.formatCompactCoins()))
                }
                if (showChestCount.get()) add(ProfitHudRow("chestsOpened", "Chests", stats.chests.toString()))
                if (pendingChestCount() > 0) add(ProfitHudRow("pendingPrices", "Pending Prices", pendingChestCount().toString()))
                add(ProfitHudRow("kismets", "Kismets", scopedKismetUses().size.toString()))
                add(ProfitHudRow("croesus", "Croesus", croesusUnopenedCountText()))
                if (showLastChest.get()) {
                    add(ProfitHudRow("lastChest", "Last Chest", ownedChests().lastOrNull { isInSelectedTrackerScope(it.timestamp, System.currentTimeMillis()) }?.chestName ?: "N/A", ownedChests().lastOrNull { isInSelectedTrackerScope(it.timestamp, System.currentTimeMillis()) }?.profit?.formatCompactCoins().orEmpty()))
                }
            }
        )
    }

    private fun currentPartyClasses(): Set<String> =
        if (DungeonAPI.dungeonFloor == null) emptySet()
        else DungeonAPI.teammates.mapNotNull { DungeonClasses.key(it.dungeonClass?.name) }.toSet()

    private fun classXpPerRun(): Map<String, Double> {
        val selected = dungeonClassValue() ?: return emptyMap()
        val now = System.currentTimeMillis()
        val samples = HistoryQueries.classXp(state, currentAccountId(), currentProfileId()).filter {
            it.floorLabel.equals(floorValue(), true) && it.dungeonClass == selected &&
                it.timestamp >= state.averagingResetAt && isInSelectedTrackerScope(it.timestamp, now)
        }
        val runs = progressionRuns(ownedRuns().filter { it.dungeonClass == selected },
            useLastRun = isSessionScope() && samples.isEmpty())
        val partyClasses = currentPartyClasses().ifEmpty { runs.maxByOrNull { it.timestamp }?.partyClasses.orEmpty() }
        return DungeonClasses.xpPerRun(samples, runs, partyClasses)
    }

    private fun rawItemTrackerTopRows(): List<ProfitHudRow> {
        val stats = chestProfitStats()
        return buildList {
            if (showChestCount.get()) add(ProfitHudRow("chestsOpened", "Chests", stats.chests.toString()))
            if (pendingChestCount() > 0) add(ProfitHudRow("pendingPrices", "Pending Prices", pendingChestCount().toString()))
            if (showChestProfit.get()) {
                add(ProfitHudRow("profit", "Profit", stats.profit.formatCompactCoins()))
                add(ProfitHudRow("avgChest", "Avg chest", stats.average.formatCompactCoins()))
            }
            add(ProfitHudRow("kismets", "Kismets", scopedKismetUses().size.toString()))
            add(ProfitHudRow("croesus", "Croesus", croesusUnopenedCountText()))
        }
    }

    private fun croesusUnopenedCountText(): String =
        state.croesusUnclaimedCount.takeIf { it >= 0 }?.toString()
            ?: "N/A"

    private fun maybeUpdateCroesusUnclaimedCountFromTab() {
        val now = System.currentTimeMillis()
        if (now - lastCroesusTabRefreshAt < CROESUS_TAB_REFRESH_INTERVAL_MILLIS) return
        lastCroesusTabRefreshAt = now
        val count = croesusUnopenedCountFromTab() ?: return
        setCroesusUnclaimedCount(count, "tab-list")
    }

    private fun croesusUnopenedCountFromTab(): Int? {
        val lines = tabListLines()
        for (line in lines) {
            val normalized = line.cleanMc()
            if (!normalized.contains("croesus", true) &&
                !normalized.contains("unclaimed chest", true) &&
                !normalized.contains("unopened chest", true)
            ) continue
            for (regex in CROESUS_TAB_COUNT_REGEXES) {
                val count = regex.find(normalized)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                if (count != null) {
                    return count
                }
            }
        }
        return null
    }

    private fun setCroesusUnclaimedCount(count: Int, source: String) {
        val safe = count.coerceAtLeast(0)
        if (state.croesusUnclaimedCount == safe) return
        state.croesusUnclaimedCount = safe
        saveState()
        log("Croesus unclaimed count set source=$source count=$safe")
    }

    private fun incrementCroesusUnclaimedCount(source: String) {
        val next = state.croesusUnclaimedCount.takeIf { it >= 0 }?.plus(1) ?: return
        setCroesusUnclaimedCount(next, source)
    }

    private fun decrementCroesusUnclaimedCount(source: String) {
        val current = state.croesusUnclaimedCount
        if (current < 0) return
        setCroesusUnclaimedCount((current - 1).coerceAtLeast(0), source)
    }

    private fun tabListLines(): List<String> {
        val connection = mc.connection ?: return emptyList()
        return connection.listedOnlinePlayers
            .sortedBy { it.tabListOrder }
            .map { info ->
                val display = info.tabListDisplayName
                    ?: PlayerTeam.formatNameForTeam(info.team, Component.literal(info.profile.name))
                display.string.cleanMc()
            }
            .filter { it.isNotBlank() }
    }

    private fun rawItemTrackerRows(): List<ProfitHudRow> {
        val counts = trackedItemCounts()
        fun count(key: String): Int = counts[key] ?: 0
        return listOf(
            ProfitHudRow("itemHandle", "Handle", count("NECRON_HANDLE").toString()),
            ProfitHudRow("itemImplosion", "Implosion", count("IMPLOSION_SCROLL").toString()),
            ProfitHudRow("itemWitherShield", "Wither Shield", count("WITHER_SHIELD_SCROLL").toString()),
            ProfitHudRow("itemShadowWarp", "Shadow Warp", count("SHADOW_WARP_SCROLL").toString()),
            ProfitHudRow("itemRecomb", "Recomb", count("RECOMBOBULATOR_3000").toString()),
            ProfitHudRow("itemAutoRecomb", "Auto Recomb", count("AUTO_RECOMBOBULATOR").toString()),
            ProfitHudRow("itemClaymore", "Claymore", count("DARK_CLAYMORE").toString()),
            ProfitHudRow("itemFifthStar", "5th Star", count("FIFTH_MASTER_STAR").toString()),
            ProfitHudRow("itemChestplate", "Chestplate", count("WITHER_CHESTPLATE").toString()),
            ProfitHudRow("itemSkullT5", "Skull T5", count("MASTER_SKULL_TIER_5").toString()),
            ProfitHudRow("itemNecronDye", "Necron Dye", count("NECRON_DYE").toString()),
        )
    }

    private fun trackedItemCounts(): Map<String, Int> =
        scopedTrackedItemDrops().groupingBy { it.itemKey }.eachCount()

    private fun scopedTrackedItemDrops(): List<TrackedItemDropSample> {
        if (state.chestProfitWindowMillis > 0L) {
            val cutoff = System.currentTimeMillis() - state.chestProfitWindowMillis
            return ownedDrops().filter { it.timestamp > 0L && it.timestamp >= cutoff }
        }
        if (trackerModeValue() == "Total") return ownedDrops()
        if (sessionStartedAt <= 0L) return emptyList()
        return ownedDrops().filter { it.timestamp > 0L && it.timestamp >= sessionStartedAt }
    }

    private fun orderProfitRows(rows: List<ProfitHudRow>): List<ProfitHudRow> {
        val byId = rows.associateBy { it.id }
        return (normalizedHudLineOrder() + rows.map { it.id }).distinct().mapNotNull { byId[it] }
    }

    private fun logRenderState(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastRenderStateLog < 5_000) return
        lastRenderStateLog = now
        log(message)
    }

    private fun isDungeonArea(): Boolean {
        val area = (Location.area ?: "").lowercase(Locale.ROOT)
        val subarea = (Location.subarea ?: "").lowercase(Locale.ROOT)
        if (subarea.contains("dungeon hub") || subarea.contains("the catacombs") || subarea.contains("catacombs")) return true
        if (area.contains("dungeon hub") || area.contains("the catacombs") || area.contains("catacombs")) return true

        val text = getScoreboardText().lowercase(Locale.ROOT)
        return text.contains("dungeon hub") || text.contains("the catacombs") || text.contains("catacombs")
    }

    private fun isActiveDungeon(scoreboard: String = getScoreboardText(), tabList: List<String> = tabListLines()): Boolean = DungeonActivityDetector.isActiveDungeon(
        area = Location.area,
        subarea = Location.subarea,
        scoreboard = scoreboard,
        tabList = tabList,
    )

    private data class ChestCosts(
        val coinCost: Long = 0,
        val requiresKey: Boolean = false,
    )

    private fun isSkyBlockArea(): Boolean {
        val area = (Location.area ?: "").lowercase(Locale.ROOT)
        val subarea = (Location.subarea ?: "").lowercase(Locale.ROOT)
        if (area.contains("skyblock") || subarea.contains("skyblock")) return true

        val text = getScoreboardText().lowercase(Locale.ROOT)
        return text.contains("skyblock") || text.contains("skyblock xp") || text.contains("purse:")
    }

    private fun getScoreboardText(): String {
        val scoreboard = mc.level?.scoreboard ?: return ""
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return ""
        val lines = scoreboard.listPlayerScores(objective)
            .filterNot { it.isHidden }
            .sortedByDescending { it.value }
            .take(15)
            .map { score ->
                val team = scoreboard.getPlayersTeam(score.owner())
                PlayerTeam.formatNameForTeam(team, score.ownerName()).string.cleanMc()
            }

        return (listOf(objective.displayName.string) + lines).joinToString("\n")
    }

    private fun send(message: String) {
        mc.player?.sendSystemMessage(Component.literal((PREFIX + message).colorize()))
    }

    private fun sendReplacingSummary(lines: List<String>) {
        val chat = mc.gui.chat
        summarySignatures.forEach { chat.deleteMessage(it) }
        lines.take(summarySignatures.size).forEachIndexed { index, line ->
            chat.addPlayerMessage(Component.literal((PREFIX + line).colorize()), summarySignatures[index], GuiMessageTag.system())
        }
    }

    private fun signature(seed: String): MessageSignature {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(StandardCharsets.UTF_8))
        val bytes = ByteArray(MessageSignature.BYTES)
        for (idx in bytes.indices) {
            bytes[idx] = digest[idx % digest.size]
        }
        return MessageSignature(bytes)
    }

    private val historyRepository by lazy {
        HistoryRepository(stateFile.toPath(), { text ->
            val loaded = gson.fromJson(text, RunState::class.java)
                ?: error("History contains null instead of a state object")
            HistoryValidation.validate(loaded)
            RunStateMigration.migrate(loaded)
            loaded
        }, { value: RunState -> gson.toJson(value) })
    }

    private fun loadState() {
        state = historyRepository.load { RunState() }
        if (!historyRepository.writable) {
            status = "History unreadable; automatic saving disabled"
            log("$status: ${historyRepository.error}")
            return
        }
        if (state.hudViewMode != HUD_MODE_ITEMS) state.hudViewMode = HUD_MODE_PROFIT
        state.hudLineOrder = normalizedHudLineOrder().toMutableList()
    }

    private val historyWriter by lazy { HistoryWriter(historyRepository) }

    private fun saveState() {
        stateRevision++
        if (!historyRepository.writable) {
            status = "History unreadable; saving disabled. Use /dph recoverbackup."
            return
        }
        val snapshot = state.copy(
            runs = state.runs.map { it.copy() }.toMutableList(),
            samples = state.samples.map { it.copy() }.toMutableList(),
            xpIntervals = state.xpIntervals.toMutableList(),
            classXpSamples = state.classXpSamples.toMutableList(),
            pendingChestClaims = state.pendingChestClaims.toMutableList(),
            importedLogFiles = state.importedLogFiles.toMutableMap(),
            chestProfits = state.chestProfits.map { it.copy(
                missingItemIds = it.missingItemIds.toMutableList(),
                pricedItems = it.pricedItems.map { item -> item.copy() }.toMutableList()) }.toMutableList(),
            kismetUses = state.kismetUses.map { it.copy() }.toMutableList(),
            trackedItemDrops = state.trackedItemDrops.map { it.copy() }.toMutableList(),
            lastMissingItemIds = state.lastMissingItemIds.toMutableList(),
            hudLineOrder = state.hudLineOrder?.toMutableList(),
        )
        historyWriter.submit(snapshot)
    }

    fun flushHistory() {
        runCatching { historyWriter.flush() }.onFailure { log("History flush failed: $it") }
    }

    fun recoverBackup() {
        flushHistory()
        runCatching { historyRepository.recoverBackup() }.onSuccess {
            state = it
            clearPendingChestTracking()
            send("Backup recovered; previous file preserved as runs.json.unreadable-<id>.")
        }.onFailure { send("Backup recovery failed: ${it.message}. Original history preserved.") }
    }

    private fun sessionReady(): Boolean = mc.player?.uuid != null && mc.player?.name?.string?.isNotBlank() == true

    private fun xpRemaining(currentXp: Long, targetLevel: Int): Long = (targetXp(targetLevel) - currentXp).coerceAtLeast(0)

    private fun targetLevelValue(): Int = targetLevel.get().toIntOrNull()?.coerceIn(1, 1_000) ?: 50

    private fun floorValue(): String = detectedFloorLabel.ifBlank {
        ownedRuns().maxByOrNull { it.timestamp }?.floorLabel ?: "N/A"
    }

    private fun dungeonClassValue(): String? = detectedDungeonClass ?: data?.selectedDungeonClass
        ?: DungeonClasses.key(ownedRuns().maxByOrNull { it.timestamp }?.dungeonClass)

    private fun xpModeValue(): String = xpMode.options.getOrElse(xpMode.get()) { xpMode.options.first() }

    private fun trackerModeValue(): String = chestProfitMode.options.getOrElse(chestProfitMode.get()) { chestProfitMode.options.first() }

    private fun shouldTrackM7Drops(floor: String = floorValue()): Boolean =
        floor.equals("M7", true) ||
            floor.equals("MM7", true) ||
            floor.equals("MASTER MODE 7", true) ||
            floor.equals("MASTER CATACOMBS - FLOOR VII", true)

    private fun hardcodedXpPerRunValue(): Long = hardcodedXpPerRun.get().toLongOrNull()?.coerceAtLeast(1L) ?: 450_000L

    private fun Long.format(): String = "%,d".format(this)

    private fun Iterable<Int>.averageOrZero(): Double = if (none()) 0.0 else average()

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return "%02dm %02ds".format(minutes, remainder)
    }

    private fun ensureSessionStarted(startedAt: Long, reason: String) {
        val now = System.currentTimeMillis()
        val activityStartedAt = startedAt.coerceIn(0L, now)
        val wasStarted = sessionStartedAt > 0L
        val wasRunning = sessionTracker.isRunning
        sessionTracker.startOrResume(activityStartedAt, now)
        if (!wasStarted) log("Dungeon session started reason=$reason startedAt=$sessionStartedAt")
        if (!wasRunning) log("Dungeon session timer resumed reason=$reason elapsed=${sessionTracker.elapsedMillis(now)}ms")
    }

    private fun pauseSessionTimer(reason: String) {
        if (!sessionTracker.isRunning) return
        val now = System.currentTimeMillis()
        sessionTracker.pause(now)
        log("Dungeon session timer paused reason=$reason elapsed=${sessionTracker.elapsedMillis(now)}ms")
    }

    private fun sessionElapsedSeconds(): Long {
        return sessionTracker.elapsedMillis(System.currentTimeMillis()) / 1000L
    }

    private fun sessionDurationText(): String =
        if (sessionStartedAt > 0L) formatSessionDuration(sessionElapsedSeconds()) else "Not started"

    private fun formatSessionDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val remainder = safe % 60L
        return buildString {
            if (hours > 0L) append("${hours}h ")
            if (minutes > 0L || hours > 0L) append("${minutes}m ")
            append("${remainder}s")
        }.trim()
    }

    private fun isSessionScope(): Boolean = state.chestProfitWindowMillis == 0L && trackerModeValue() != "Total"

    private fun xpPerHourText(): String {
        if (!isSessionScope()) return HistoryQueries.xpPerRunHour(scopedRuns())?.formatCompact() ?: "N/A"
        val recordedXp = sessionRecordedXp()
        if (recordedXp.isEmpty()) return "N/A"
        val perHour = SessionRates.xpPerHour(
            recordedXp,
            sessionTracker.elapsedMillis(System.currentTimeMillis()),
        ) ?: return "N/A"
        return perHour.formatCompact()
    }

    /** Completion chat is preferred, while profile deltas cover runs whose completion chat was missed. */
    private fun sessionRecordedXp(): List<Long> {
        if (sessionStartedAt <= 0L) return emptyList()
        val runs = ownedRuns().filter { it.timestamp >= sessionStartedAt && it.rawCataXp > 0L }
        val unattributed = state.xpIntervals.filter {
            owns(it.accountId, it.profileId) && it.start >= sessionStartedAt
        }.map { interval -> XpReconciliation.unattributed(interval, runs.map { it.timestamp to it.rawCataXp }) }
        return runs.map { it.rawCataXp } + unattributed
    }

    private fun Long.formatCompact(): String {
        val sign = if (this < 0) "-" else ""
        val abs = kotlin.math.abs(this)
        val body = when {
            abs >= 1_000_000_000L -> "%.2fb".format(Locale.US, abs / 1_000_000_000.0)
            abs >= 1_000_000L -> "%.2fm".format(Locale.US, abs / 1_000_000.0)
            abs >= 1_000L -> "${abs / 1_000L}k"
            else -> abs.toString()
        }
        return sign + body
    }

    private fun Long.formatCompactCoins(): String = formatCompact()

    private fun Long.formatCoins(): String {
        val sign = if (this < 0) "-" else ""
        val abs = kotlin.math.abs(this)
        val body = when {
            abs >= 1_000_000_000L -> "%.2fb".format(Locale.US, abs / 1_000_000_000.0)
            abs >= 1_000_000L -> "%.2fm".format(Locale.US, abs / 1_000_000.0)
            abs >= 1_000L -> "%.1fk".format(Locale.US, abs / 1_000.0)
            else -> abs.toString()
        }
        return "${sign}${body}"
    }

    private fun String.colorize(): String = replace("&", "\u00a7")

    private fun String.cleanMc(): String = replace(Regex("\u00a7."), "").trim()

    private fun log(message: String) {
        DungeonProgressHudAddon.debug(message)
    }

    private val chestNames = setOf("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")
    private val runChestRegex = "^(?:Master )?Catacombs - Floor [IV]+$".toRegex()
}
