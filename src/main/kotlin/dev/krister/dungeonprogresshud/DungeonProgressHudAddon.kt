package dev.krister.dungeonprogresshud

import com.github.synnerz.devonian.Devonian
import com.github.synnerz.devonian.api.ItemUtils
import com.github.synnerz.devonian.api.Location
import com.github.synnerz.devonian.api.SkyblockPrices
import com.github.synnerz.devonian.api.events.GuiKeyDownEvent
import com.github.synnerz.devonian.api.events.ChatEvent
import com.github.synnerz.devonian.config.Categories
import com.github.synnerz.devonian.config.Config
import com.github.synnerz.devonian.config.ConfigData
import com.github.synnerz.devonian.hud.texthud.TextHudFeature
import com.github.synnerz.devonian.utils.StringUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.MessageSignature
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val serverKey = detectedServerKey(client)
            if (serverKey != lastTickDetectedServerKey) {
                lastTickDetectedServerKey = serverKey
                if (serverKey.isNotBlank()) {
                    val current = feature
                    if (current != null) {
                        current.onServerJoin()
                    } else {
                        pendingServerJoinAt = System.currentTimeMillis()
                        debug("Tick detected server join queued until feature registration server=$serverKey")
                    }
                }
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
        val subcategory = ensureDevonianSubcategory(category, "DPH") ?: "Mod"
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

    fun renderOverlay(graphics: GuiGraphics) {
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

    fun renderScreenOverlay(graphics: GuiGraphics, screen: Screen, mouseX: Int, mouseY: Int) {
        if (screen is AbstractContainerScreen<*>) {
            feature?.renderHudOrderOverlay(graphics, mouseX.toDouble(), mouseY.toDouble())
        }
    }

    fun onInventoryClick(slotId: Int, button: Int, clickType: ClickType) {
        feature?.onInventoryClick(slotId, button, clickType)
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
        feature?.parseDungeonCompletionMessage(message.string)
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
                    .then(literal("refresh").executes {
                        withFeature { it.refresh(force = true, recordObservedSample = false, notify = true) }
                        1
                    })
                    .then(literal("apikey")
                        .then(argument("key", StringArgumentType.greedyString()).executes { context ->
                            withFeature { it.setApiKey(StringArgumentType.getString(context, "key")) }
                            1
                        })
                    )
                    .then(literal("reset").executes {
                        withFeature { it.resetSamples() }
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

    @Suppress("UNCHECKED_CAST")
    private fun ensureDevonianSubcategory(category: Categories, subcategory: String): String? {
        return runCatching {
            val categoryField = category.javaClass.getDeclaredField("subcategories")
            categoryField.isAccessible = true
            val existingSubcategories = categoryField.get(category) as List<String>
            if (subcategory !in existingSubcategories) {
                categoryField.set(category, existingSubcategories + subcategory)
            }

            val configClass = Class.forName("com.github.synnerz.devonian.config.Config")
            val categoriesField = configClass.getDeclaredField("categories")
            categoriesField.isAccessible = true
            val categories = categoriesField.get(null) as MutableMap<Categories, MutableMap<String, MutableList<ConfigData<*>>>>
            categories.getValue(category).getOrPut(subcategory) { mutableListOf() }
            subcategory
        }.getOrElse {
            debug("Failed to register Devonian subcategory $subcategory, falling back to Mod: ${it::class.simpleName}: ${it.message}")
            null
        }
    }

    fun debug(message: String) {
        val line = "[${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}] $message"
        logger.info(line)
        runCatching {
            debugLogFile.parentFile.mkdirs()
            debugLogFile.appendText(line + System.lineSeparator(), Charsets.UTF_8)
        }
    }
}

class DungeonProgressHudFeature(
    configCategory: Categories,
    private val configTab: String,
) : TextHudFeature(
    "dungeonProgressHud",
    "Shows Catacombs XP progress and estimated runs left.",
    configCategory,
    "catacombs",
    displayName = "Dungeon Progress HUD",
    subcategory = configTab,
) {
    private companion object {
        private const val FEATURE_CONFIG = "dungeonProgressHud"
        private const val API_KEY_CONFIG = "$FEATURE_CONFIG\$41_apiKey"
        private const val LEGACY_API_KEY_CONFIG = "$FEATURE_CONFIG\$apiKey"
        private val API_KEY_CONFIG_NAMES = listOf(API_KEY_CONFIG, LEGACY_API_KEY_CONFIG)
        private const val DAY_MILLIS = 86_400_000L
        private const val AUTO_REFRESH_INTERVAL_MILLIS = 300_000L
        private const val API_KEY_REFRESH_DEBOUNCE_MILLIS = 1_000L
        private const val API_KEY_REFRESH_RETRY_MILLIS = 1_000L
        private const val MIN_REFRESHABLE_API_KEY_LENGTH = 32
        private const val CROESUS_TAB_REFRESH_INTERVAL_MILLIS = 1_000L
        private const val MAX_TRACKED_ITEM_DROPS = 5_000
        private const val HUD_LINE_GAP = 2
        private const val HUD_ROW_HEIGHT = 15
        private const val HUD_TITLE_HEIGHT = 28
        private const val HUD_TOP_PADDING = 10
        private const val HUD_SIDE_PADDING = 13
        private const val HUD_PROFIT_GAP = 10
        private const val HUD_ORDER_HINT = "&eHold Shift to drag HUD lines"
        private const val STATUS_LINE_ID = "status"
        private const val JOIN_SKYBLOCK_DETECTION_TIMEOUT_MILLIS = 300_000L
        private val JOIN_REFRESH_RETRY_DELAYS = longArrayOf(10_000L, 30_000L, 90_000L, 180_000L)
        private val DEFAULT_HUD_LINE_ORDER = listOf(
            "sessionTime",
            "currentLevel",
            "target",
            "levelProgress",
            "runsLeft",
            "currentXp",
            "remaining",
            "floor",
            "xpPerRun",
            "profile",
            "lastRun",
            "observedCount",
            "profit",
            "avgChest",
            "chestsOpened",
            "kismets",
            "croesus",
            "lastChest",
        )
        private val PROFIT_HUD_LINE_IDS = setOf("profit", "avgChest", "chestsOpened", "croesus", "lastChest")
        private val ACCENT_VALUE_HUD_LINE_IDS = setOf("runsLeft", "profit", "lastChest", "avgChest")
        private const val HUD_CYAN = 0xFF42F3FF.toInt()
        private const val HUD_CYAN_DIM = 0xFF147B84.toInt()
        private const val HUD_GREEN = 0xFF63FF57.toInt()
        private const val HUD_WHITE = 0xFFFFFFFF.toInt()
        private const val HUD_MUTED = 0xFFC8C8C8.toInt()
        private const val HUD_LINE = 0x663DFAFF
        private const val HUD_BLACK = 0xFF000000.toInt()
        private const val HUD_OUTER_DARK = 0xFF061014.toInt()
        private const val HUD_INNER_DARK = 0xFF020405.toInt()
        private const val HUD_PANEL = 0xD00A0F11.toInt()
        private const val HUD_PROFIT_PANEL = 0x88101010.toInt()
        private const val HUD_SKETCH_WHITE = 0xFFE8E8E8.toInt()
        private const val HUD_MODE_PROFIT = "profit"
        private const val HUD_MODE_ITEMS = "items"
        private const val HUD_MODE_BUTTON_WIDTH = 46
        private const val HUD_MODE_BUTTON_HEIGHT = 14
        private const val HUD_MODE_BUTTON_MARGIN = 5
        private val CROESUS_TAB_COUNT_REGEXES = listOf(
            Regex("\\bunclaimed\\s+chests?\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bunopened\\s+chests?\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\b(?:unclaimed|unopened)\\b.*\\bcroesus\\b.*?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bcroesus\\b.*\\b(?:unclaimed|unopened)\\b.*?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\bcroesus\\b.*\\bchests?\\b.*?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d+)\\b.*\\bcroesus\\b.*\\bchests?\\b", RegexOption.IGNORE_CASE),
        )
        private val TRACKED_DROPS = listOf(
            TrackedDropDefinition("NECRON_HANDLE", "Handle", setOf("NECRON_HANDLE", "NECRON'S_HANDLE", "NECRONS_HANDLE")),
            TrackedDropDefinition("IMPLOSION_SCROLL", "Implosion", setOf("IMPLOSION_SCROLL", "IMPLOSION")),
            TrackedDropDefinition("WITHER_SHIELD_SCROLL", "Wither Shield", setOf("WITHER_SHIELD_SCROLL", "WITHER_SHIELD")),
            TrackedDropDefinition("SHADOW_WARP_SCROLL", "Shadow Warp", setOf("SHADOW_WARP_SCROLL", "SHADOW_WARP")),
            TrackedDropDefinition("RECOMBOBULATOR_3000", "Recomb", setOf("RECOMBOBULATOR_3000", "RECOMBOBULATOR")),
            TrackedDropDefinition("AUTO_RECOMBOBULATOR", "Auto Recomb", setOf("AUTO_RECOMBOBULATOR", "AUTO_RECOMBOBULATOR_3000")),
            TrackedDropDefinition("DARK_CLAYMORE", "Claymore", setOf("DARK_CLAYMORE")),
            TrackedDropDefinition("FIFTH_MASTER_STAR", "5th Star", setOf("FIFTH_MASTER_STAR", "5TH_MASTER_STAR", "MASTER_STAR_TIER_5")),
            TrackedDropDefinition("WITHER_CHESTPLATE", "Chestplate", setOf("WITHER_CHESTPLATE")),
            TrackedDropDefinition("MASTER_SKULL_TIER_5", "Skull T5", setOf("MASTER_SKULL_TIER_5", "MASTER_SKULL_5")),
            TrackedDropDefinition("NECRON_DYE", "Necron Dye", setOf("NECRON_DYE", "NECRONS_DYE", "NECRON'S_DYE")),
        )
        private val TRACKED_DROPS_BY_ALIAS = TRACKED_DROPS
            .flatMap { drop -> drop.aliases.map { normalizeTrackedAlias(it) to drop } }
            .toMap()

        private fun normalizeTrackedAlias(value: String): String =
            value.uppercase(Locale.ROOT)
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
    }

    private val PREFIX = "&6[&bDPH&6]&r "
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configDir = FabricLoader.getInstance().configDir.resolve("DungeonProgressHud").toFile()
    private val stateFile = File(configDir, "runs.json")
    private val summarySignatures = (0 until 3).map { signature("dph-summary-$it") }
    private val logsDir = FabricLoader.getInstance().gameDir.resolve("logs").toFile()
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val displayDivider = addDivider("10", "DISPLAY")
    private val renderHud = addSwitch("11_renderHud", true, "Draw the HUD during normal gameplay.", "Render HUD", emptySet(), false, configTab)
    private val showEverywhere = addSwitch("12_showEverywhere", true, "Render everywhere instead of only Dungeon Hub/Catacombs.", "Show Everywhere", emptySet(), false, configTab)
    private val targetLevel = addTextInput("13_targetLevel", "50", "Target Catacombs level.", "Target Level", emptySet(), configTab)
    private val floorLabel = addTextInput("14_floorLabel", "M7", "Floor label used for observed samples.", "Floor Label", emptySet(), configTab)
    private val showCurrentLevel = addSwitch("15_showCurrentLevel", true, "Show current Catacombs level.", "Current Level", emptySet(), false, configTab)
    private val showCurrentXp = addSwitch("16_showCurrentXp", true, "Show current Catacombs XP.", "Current XP", emptySet(), false, configTab)
    private val showLevelProgress = addSwitch("17_showLevelProgress", true, "Show percentage progress toward the next Catacombs level.", "Level Progress", emptySet(), false, configTab)
    private val showTarget = addSwitch("18_showTarget", true, "Show target Catacombs level.", "Target", emptySet(), false, configTab)
    private val showRemaining = addSwitch("19_showRemaining", true, "Show XP remaining.", "XP Remaining", emptySet(), false, configTab)
    private val showFloor = addSwitch("1a_showFloor", true, "Show floor label.", "Floor", emptySet(), false, configTab)
    private val showXpPerRun = addSwitch("1b_showXpPerRun", true, "Show effective XP/run.", "XP/Run", emptySet(), false, configTab)
    private val showRunsLeft = addSwitch("1c_showRunsLeft", true, "Show estimated runs left.", "Runs Left", emptySet(), false, configTab)
    private val showProfile = addSwitch("1d_showProfile", false, "Show selected SkyBlock profile.", "Profile", emptySet(), false, configTab)
    private val showLastRun = addSwitch("1e_showLastRun", true, "Show last observed normalized XP delta.", "Last Run XP", emptySet(), false, configTab)
    private val showObservedCount = addSwitch("1f_showObservedCount", false, "Show observed sample count.", "Observed Count", emptySet(), false, configTab)

    private val xpDivider = addDivider("20", "XP")
    private val xpMode = addSelection("21_xpMode", 0, listOf("Observed Average", "Hardcoded"), "XP/run source.", "XP/Run Mode", emptySet(), configTab)
    private val hardcodedXpPerRun = addTextInput("22_hardcodedXpPerRun", "450000", "Fallback XP per run.", "Hardcoded XP/Run", emptySet(), configTab)
    private val scaleDailyRunXp = addSwitch("23_scaleDailyRunXp", true, "Divide high raw run XP by the daily multiplier.", "Scale Daily Run XP", emptySet(), false, configTab)
    private val dailyThreshold = addTextInput("24_dailyThreshold", "600000", "Only scale raw run XP above this value.", "Daily XP Threshold", emptySet(), configTab)
    private val dailyMultiplier = addTextInput("25_dailyMultiplier", "1.4", "Raw run XP is divided by this when daily scaling applies.", "Daily XP Multiplier", emptySet(), configTab)

    private val profitDivider = addDivider("30", "PROFIT")
    private val trackChestProfit = addSwitch("31_trackChestProfit", true, "Track profit when claiming dungeon reward chests.", "Track Chest Profit", emptySet(), false, configTab)
    private val showChestProfit = addSwitch("32_showChestProfit", true, "Show tracked dungeon chest profit.", "Chest Profit", emptySet(), false, configTab)
    private val chestProfitMode = addSelection("33_chestProfitMode", 0, listOf("Session", "Total"), "Choose whether profit and item tracker lines use this session or all tracked chests.", "Tracker Mode", emptySet(), configTab)
    private val showChestCount = addSwitch("34_showChestCount", true, "Show tracked dungeon reward chest count.", "Chest Count", emptySet(), false, configTab)
    private val showLastChest = addSwitch("35_showLastChest", true, "Show the last opened dungeon reward chest and its profit.", "Last Chest Opened", emptySet(), false, configTab)
    private val includeEssenceProfit = addSwitch("36_includeEssenceProfit", true, "Include essence value in chest profit.", "Include Essence", emptySet(), false, configTab)
    private val includeDungeonKeyCost = addSwitch("37_includeDungeonKeyCost", false, "Subtract Dungeon Chest Key value when a reward chest requires one.", "Count Dungeon Key Cost", emptySet(), false, configTab)

    private val actionsDivider = addDivider("40", "ACTIONS")
    private val apiKey = addTextInput("41_apiKey", "", "Hypixel API key.", "Hypixel API Key", emptySet(), configTab)
    private val refreshButton = addSortedButton("42", { refresh(force = true, recordObservedSample = false, notify = true) }, "Refresh", "Force API refresh.", "Refresh Now")
    private val resetButton = addSortedButton("43", { resetSamples() }, "Reset", "Clear observed XP samples.", "Reset Observed Runs")
    private val keybindCategory by lazy {
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("dungeonprogresshud", "keybinds"))
    }
    private val fakeOpenKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping("key.dungeonprogresshud.fakeOpenChest", GLFW.GLFW_KEY_H, keybindCategory)
    )

    private var state = RunState()
    private var data: ProfileData? = null
    private var status = "API key missing"
    private var refreshing = false
    private var lastRefresh = 0L
    private var lastAutoRefreshAttempt = 0L
    private var suppressApiKeyChange = false
    private var lastApiKeyValue = ""
    private var pendingApiKeyRefreshValue = ""
    private var pendingApiKeyRefreshAt = 0L
    private var pendingApiKeyRefreshNotify = false
    @Volatile private var devonianConfigSaveRunning = false
    @Volatile private var devonianConfigSavePending = false
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
    private var lastRecordedChestAt = 0L
    private var lastRecordedKismetAt = 0L
    private var lastRecordedKismetKey = ""
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
    private var sessionStartedAt = 0L
    private var draggedHudLineId: String? = null
    private var hoveredHudLineId: String? = null

    init {
        apiKey.onChange { onApiKeyInputChanged(it) }
        Config.onAfterLoad {
            withApiKeyChangeSuppressed {
                migrateLegacyApiKey()
            }
            lastApiKeyValue = apiKey.get().trim()
        }
    }

    fun onServerJoin(joinedAt: Long = System.currentTimeMillis()) {
        startRuntime("server join")
        if (joinedAt - lastServerJoinEventAt < 5_000L && (joinRefreshStartedAt > 0L || refreshing || joinRefreshAttempts > 0 || lastRefresh >= joinRefreshStartedAt)) {
            log("Server join refresh ignored duplicate event")
            return
        }
        lastServerJoinEventAt = joinedAt
        joinRefreshStartedAt = joinedAt
        joinRefreshAttempts = 0
        nextJoinRefreshAttemptAt = 0L
        joinRefreshCompleted = false
        skyBlockJoinRefreshAttempted = false
        skyBlockJoinRefreshCompleted = false
        startupRefreshAttempted = true
        log("Server join refresh scheduled user=${mc.user.name} uuid=${mc.user.profileId}")
    }

    fun onServerDisconnect() {
        joinRefreshStartedAt = 0L
        joinRefreshAttempts = 0
        nextJoinRefreshAttemptAt = 0L
        joinRefreshCompleted = false
        skyBlockJoinRefreshAttempted = false
        skyBlockJoinRefreshCompleted = false
    }

    private fun addDivider(sortKey: String, label: String): ConfigData.Switch =
        addSwitch("${sortKey}_divider$label", false, "", "__________ $label __________", emptySet(), false, configTab)

    private fun addSortedButton(
        sortKey: String,
        action: () -> Unit,
        buttonTitle: String,
        description: String,
        displayName: String,
    ): ConfigData.Button {
        val sortParent = ConfigData.FeatureSwitch("$FEATURE_CONFIG.$sortKey", true, "", "", configTab, emptySet(), true)
        val button = ConfigData.Button(action, buttonTitle, sortParent, description, displayName, configTab, emptySet(), false)
        Config.registerCategory(button, category, configTab)
        configSwitch.subconfigs.add(button)
        return button
    }

    private fun migrateLegacyApiKey() {
        val normalized = apiKey.get().trim()
        if (normalized.isNotBlank()) {
            if (normalized != apiKey.get()) setApiKeyConfigValue(normalized)
            return
        }
        val legacy = readConfiguredApiKeyValue(LEGACY_API_KEY_CONFIG)
        if (legacy.isNotBlank()) setApiKeyConfigValue(legacy.trim())
    }

    override fun getEditText(): List<String> =
        listOf(
            "&fDungeon Profit Hud",
            "&f"
        ) +
            orderProfitRows(
                listOf(
                    ProfitHudRow("sessionTime", "Time", "Not started"),
                    ProfitHudRow("currentLevel", "Cata", "50"),
                    ProfitHudRow("target", "Target", "51"),
                    ProfitHudRow("levelProgress", "Next", "28.9%"),
                    ProfitHudRow("runsLeft", "Left", "252"),
                    ProfitHudRow("currentXp", "XP", "627.58m"),
                    ProfitHudRow("lastRun", "Last", "491k", "(4.83m/h)"),
                    ProfitHudRow("observedCount", "Runs", "100"),
                )
            ).map { editPreviewLine(it) } +
            listOf(
                "&f",
                "&f----------------",
                "&f"
            ) +
            orderProfitRows(
                listOf(
                    ProfitHudRow("chestsOpened", "Chests", "359"),
                    ProfitHudRow("profit", "Profit", "2.79b"),
                    ProfitHudRow("avgChest", "Avg Chest", "7.77m"),
                    ProfitHudRow("kismets", "Kismets", "0"),
                    ProfitHudRow("croesus", "Croesus", "0"),
                )
            ).map { editPreviewLine(it) }

    private fun editPreviewLine(row: ProfitHudRow): String {
        val paddedLabel = row.label.padEnd(7)
        val suffix = if (row.suffix.isBlank()) "" else " ${row.suffix}"
        return "&f$paddedLabel | ${row.value}$suffix"
    }

    override fun initialize() {
        startRuntime("devonian initialize")

        on<GuiKeyDownEvent> { event ->
            if (!fakeOpenKey.matches(event.event)) return@on
            fakeOpenChest(event.screen as? AbstractContainerScreen<*>)
        }

        on<ChatEvent> { event ->
            parseDungeonCompletionMessage(event.message)
        }
    }

    fun onFabricClientTick() {
        startRuntime("fabric client tick")
        clientTick()
    }

    private fun startRuntime(reason: String) {
        if (runtimeStarted) return
        runtimeStarted = true
        log("Feature runtime started by $reason")
        loadState()
        lastApiKeyValue = configuredApiKey()
        importRecentLogs(false)
    }

    private fun clientTick() {
        val refreshedForApiKey = maybeRefreshAfterApiKeyChange()
        val refreshedForSkyBlockJoin = if (!refreshedForApiKey) maybeRefreshAfterSkyBlockJoin() else false
        val refreshedForJoin = if (!refreshedForApiKey && !refreshedForSkyBlockJoin) maybeRefreshAfterServerJoin() else false
        if (!startupRefreshAttempted && sessionReady() && !refreshedForApiKey && !refreshedForJoin) {
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
        setLines(buildLines())
    }

    private fun maybeRefreshAfterSkyBlockJoin(): Boolean {
        if (joinRefreshStartedAt == 0L || skyBlockJoinRefreshCompleted) return false
        if (!sessionReady()) return false

        val now = System.currentTimeMillis()
        if (now - joinRefreshStartedAt > JOIN_SKYBLOCK_DETECTION_TIMEOUT_MILLIS) {
            skyBlockJoinRefreshCompleted = true
            log("SkyBlock join refresh timed out waiting for scoreboard")
            return false
        }
        if (!isSkyBlockArea()) return false

        if (data != null && lastRefresh >= joinRefreshStartedAt) {
            skyBlockJoinRefreshCompleted = true
            log("SkyBlock detected after server join; API already refreshed")
            return false
        }
        if (refreshing) return true
        if (skyBlockJoinRefreshAttempted) return false

        skyBlockJoinRefreshAttempted = true
        startupRefreshAttempted = true
        lastAutoRefreshAttempt = now
        log("SkyBlock detected after server join; forcing silent API refresh server=${mc.currentServer?.ip ?: "unknown"}")
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

    fun renderHud(graphics: GuiGraphics) {
        val enabled = isEnabled()
        if (mc.screen is AbstractContainerScreen<*>) {
            return
        }
        val visible = shouldRenderCached()
        if (!renderHud.get() || !visible) {
            logRenderState("Render blocked renderHud=${renderHud.get()} enabled=$enabled shouldRender=$visible showEverywhere=${showEverywhere.get()}")
            return
        }

        val hudLines = orderedHudLines(buildHudLines())
        if (hudLines.isEmpty()) {
            logRenderState("Render blocked: no lines")
            return
        }

        drawDirect(graphics, hudLines)
        if (!renderedOnce) {
            renderedOnce = true
            log("HUD rendered lines=${hudLines.size} x=$x y=$y scale=$scale status=$status")
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
        val requestApiKey = configuredApiKey()
        if (requestApiKey.isBlank()) {
            status = "API key missing"
            log("Refresh skipped: missing API key")
            if (notify) send("Refresh skipped: API key missing. Use /dph apikey <key> if the GUI field did not save.")
            return
        }
        val user = mc.user
        val request = ProfileRequest(
            playerName = user.name,
            playerUuid = user.profileId.toString().replace("-", ""),
            apiKey = requestApiKey,
        )

        refreshing = true
        status = "Refreshing..."
        log("Refresh started force=$force recordObservedSample=$recordObservedSample user=${request.playerName} uuid=${request.playerUuid}")
        if (notify) send("Refreshing API data...")

        thread(name = "DungeonProgressHud-API", isDaemon = true) {
            val result = runCatching { fetchProfile(request) }
            mc.execute {
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
                            status = it.message ?: "Refresh failed"
                            log("Refresh failed: ${it.stackTraceToString()}")
                            if (notify) send("Refresh failed: $status")
                        }
                } finally {
                    refreshing = false
                }
            }
        }
    }

    private fun configuredApiKey(): String {
        val current = apiKey.get().trim()
        if (current.isNotBlank()) {
            if (current != apiKey.get()) setApiKeyConfigValue(current)
            return current
        }

        for (configName in API_KEY_CONFIG_NAMES) {
            val value = readConfiguredApiKeyValue(configName)
            if (value.isNotBlank()) {
                setApiKeyConfigValue(value)
                return value
            }
        }

        return ""
    }

    private fun setApiKeyConfigValue(value: String) {
        withApiKeyChangeSuppressed {
            apiKey.set(value)
        }
    }

    private fun withApiKeyChangeSuppressed(block: () -> Unit) {
        suppressApiKeyChange = true
        try {
            block()
        } finally {
            suppressApiKeyChange = false
        }
    }

    private fun onApiKeyInputChanged(value: String) {
        if (suppressApiKeyChange) return
        val normalized = value.trim()
        if (normalized == lastApiKeyValue) return

        lastApiKeyValue = normalized
        if (normalized.isBlank()) {
            pendingApiKeyRefreshValue = ""
            pendingApiKeyRefreshAt = 0L
            pendingApiKeyRefreshNotify = false
            status = "API key missing"
            return
        }
        if (!isRefreshableApiKey(normalized)) {
            pendingApiKeyRefreshValue = ""
            pendingApiKeyRefreshAt = 0L
            pendingApiKeyRefreshNotify = false
            status = "API key changed"
            return
        }

        queueApiKeyRefresh(normalized, notify = false, delayMillis = API_KEY_REFRESH_DEBOUNCE_MILLIS)
    }

    private fun isRefreshableApiKey(value: String): Boolean =
        value.length >= MIN_REFRESHABLE_API_KEY_LENGTH && value.none { it.isWhitespace() }

    private fun queueApiKeyRefresh(value: String, notify: Boolean, delayMillis: Long) {
        val now = System.currentTimeMillis()
        lastApiKeyValue = value
        pendingApiKeyRefreshValue = value
        pendingApiKeyRefreshAt = now + delayMillis
        pendingApiKeyRefreshNotify = pendingApiKeyRefreshNotify || notify
        status = if (delayMillis <= 0L) "API key saved; refreshing..." else "API key saved; refresh queued"
    }

    private fun maybeRefreshAfterApiKeyChange(): Boolean {
        val queuedKey = pendingApiKeyRefreshValue
        if (queuedKey.isBlank()) return false
        if (!sessionReady()) return true

        val now = System.currentTimeMillis()
        if (now < pendingApiKeyRefreshAt) return true
        if (refreshing) {
            pendingApiKeyRefreshAt = now + API_KEY_REFRESH_RETRY_MILLIS
            return true
        }

        val current = configuredApiKey()
        if (current != queuedKey) {
            if (current.isBlank() || !isRefreshableApiKey(current)) {
                pendingApiKeyRefreshValue = ""
                pendingApiKeyRefreshAt = 0L
                pendingApiKeyRefreshNotify = false
                return false
            }
            queueApiKeyRefresh(current, pendingApiKeyRefreshNotify, API_KEY_REFRESH_DEBOUNCE_MILLIS)
            return true
        }

        val notify = pendingApiKeyRefreshNotify
        pendingApiKeyRefreshValue = ""
        pendingApiKeyRefreshAt = 0L
        pendingApiKeyRefreshNotify = false
        startupRefreshAttempted = true
        lastAutoRefreshAttempt = now
        log("API key change triggered forced refresh")
        refresh(force = true, recordObservedSample = false, notify = notify)
        return true
    }

    private fun saveDevonianConfigAsync(reason: String) {
        if (devonianConfigSaveRunning) {
            devonianConfigSavePending = true
            return
        }
        devonianConfigSaveRunning = true
        thread(name = "DungeonProgressHud-ConfigSave", isDaemon = true) {
            do {
                devonianConfigSavePending = false
                runCatching { Config.save() }
                    .onFailure { log("Devonian config save failed reason=$reason: ${it.javaClass.simpleName}: ${it.message}") }
            } while (devonianConfigSavePending)
            devonianConfigSaveRunning = false
        }
    }

    fun setApiKey(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            status = "API key missing"
            send("API key was empty.")
            return
        }

        apiKey.set(normalized)
        runCatching { Config.setConfig(LEGACY_API_KEY_CONFIG, normalized) }
        saveDevonianConfigAsync("api-key-command")
        queueApiKeyRefresh(normalized, notify = true, delayMillis = 0L)
        send("API key saved. Refreshing API data...")
        log("API key saved via command length=${normalized.length}")
    }

    private fun readConfiguredApiKeyValue(configName: String): String =
        runCatching { Config.getConfig(configName, "") }
            .getOrDefault("")
            ?.trim()
            ?: ""

    fun resetSamples() {
        state.samples.clear()
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
            setTrackerMode(chestProfitMode.getCurrent())
            return
        }
        setTrackerMode(if (chestProfitMode.getCurrent() == "Total") "Session" else "Total")
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
                "Avg time ${formatDuration(summary.averageRunTimeSeconds)}, XP/hour ${summary.xpPerHour.format()}.",
            )
        )
    }

    fun sendStatus() {
        send("Status: $status")
    }

    fun onInventoryClick(slotId: Int, button: Int, clickType: ClickType) {
        if (!trackChestProfit.get()) return
        val screen = currentChestScreen() ?: return
        val title = screen.title.string
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
        val devonianCandidates = devonianChestProfitCandidates()
        val candidate = selectedCroesusCandidate?.takeIf { it.chestName == title }
            ?: devonianCandidates[title]
            ?: lastCroesusCandidates[title]
            ?: pendingChestProfit?.takeIf { it.chestName == title }
            ?: parsedCandidate
        val enrichedCandidate = candidate?.withTrackedDropsFrom(parsedCandidate)
        if (enrichedCandidate == null) {
            log("Chest claim click ignored: no profit candidate for title=$title")
            return
        }

        recordChestProfit(enrichedCandidate, "claim-click")
    }

    private fun recordKismetUseIfClicked(screen: AbstractContainerScreen<*>, slotId: Int): Boolean {
        val stack = screen.menu.slots.getOrNull(slotId)?.item ?: screen.menu.items.getOrNull(slotId) ?: return false
        val name = stack.hoverName.string.cleanMc()
        val lore = plainLore(stack)
        val text = (listOf(name) + lore).joinToString(" ").lowercase(Locale.ROOT)
        if (!text.contains("kismet")) return false

        val now = System.currentTimeMillis()
        val key = "${screen.menu.containerId}:${screen.title.string}:$slotId"
        if (key == lastRecordedKismetKey && now - lastRecordedKismetAt < 2_000) {
            log("Duplicate kismet use ignored key=$key")
            return true
        }
        lastRecordedKismetKey = key
        lastRecordedKismetAt = now
        state.totalKismetsUsed++
        state.kismetUses.add(KismetUseSample(now, screen.title.string, data?.profileName.orEmpty(), floorValue()))
        while (state.kismetUses.size > 5000) state.kismetUses.removeAt(0)
        saveState()
        log("Recorded kismet use title=${screen.title.string} slot=$slotId total=${state.totalKismetsUsed}")
        return true
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
        if (!trackChestProfit.get()) {
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
        val devonianCandidates = devonianChestProfitCandidates()
        val parsedDirectCandidate = if (chestNames.contains(title)) {
            runCatching { parseChestProfit(screen, verbose = true) }
                .onFailure { log("Fake open parse failed: ${it.stackTraceToString()}") }
                .getOrNull()
        } else {
            null
        }
        val candidate = runCatching {
            when {
                chestNames.contains(title) -> selectedCroesusCandidate?.takeIf { it.chestName == title }
                    ?: devonianCandidates[title]
                    ?: lastCroesusCandidates[title]
                    ?: pendingChestProfit?.takeIf { it.chestName == title }
                    ?: parsedDirectCandidate
                title.matches(runChestRegex) -> selectedCroesusCandidate
                    ?: parseBestCroesusChest(screen)
                    ?: devonianCandidates.values.maxByOrNull { it.profit }
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

        val devonianCandidates = devonianChestProfitCandidates()
        val parsedFromScreen = parseCroesusCandidatesFromScreen(screen)
        val parsedCandidates = (parsedFromScreen + devonianCandidates.mapValues { (_, candidate) ->
            candidate.withTrackedDropsFrom(parsedFromScreen[candidate.chestName])
        })

        if (parsedCandidates.isNotEmpty()) lastCroesusCandidates = parsedCandidates
        val candidate = parsedCandidates[chestName] ?: return
        selectedCroesusCandidate = candidate
        log("Selected Croesus chest from click title=${screen.title.string} slot=$slotId ${candidate.summary()}")
    }

    private data class HudLine(
        val id: String,
        val text: String,
    )

    private data class HudRow(
        val id: String,
        val label: String,
        val value: String,
        val icon: ItemStack,
        val accentValue: Boolean,
        val mutedSuffix: String = "",
    )

    private data class ProfitHudRow(
        val id: String,
        val label: String,
        val value: String,
        val suffix: String = "",
    )

    private data class TrackedDropDefinition(
        val key: String,
        val displayName: String,
        val aliases: Set<String>,
    )

    data class TrackedDrop(
        val key: String,
        val displayName: String,
    )

    private data class HudPanelLayout(
        val width: Int,
        val dividerY: Int,
        val height: Int,
        val separatorX: Int,
        val valueX: Int,
    )

    private data class HudLineBounds(
        val id: String,
        val text: String,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    )

    private fun buildLines(): List<String> = orderedHudLines(buildHudLines()).map { it.text }

    private fun buildHudLines(): List<HudLine> {
        val profile = data ?: return listOf(HudLine(STATUS_LINE_ID, "&bDungeon Progress: &7$status"))
        val xpPerRun = effectiveXpPerRun()
        val remaining = xpRemaining(profile.catacombsExperience, targetLevelValue())
        val runs = xpPerRun.takeIf { it > 0 }?.let { ceil(remaining.toDouble() / it.toDouble()).toLong() }
        val samples = samplesForFloor()
        val last = samples.lastOrNull()
        val source = if (xpMode.getCurrent() == "Hardcoded") "hardcoded" else if (samples.isEmpty()) "fallback" else "observed"
        val currentLevel = currentCataLevel(profile.catacombsExperience)

        return buildList {
            if (showCurrentLevel.get()) add(HudLine("currentLevel", "&bCata Level: &fC$currentLevel"))
            if (showLevelProgress.get()) add(HudLine("levelProgress", "&bNext Level: &f${levelProgressPercent(profile.catacombsExperience)}%"))
            if (showTarget.get()) add(HudLine("target", "&bTarget: &fC${targetLevelValue()}"))
            if (showRunsLeft.get()) add(HudLine("runsLeft", "&bRuns Left: &a${runs?.format() ?: "N/A"}"))
            if (showCurrentXp.get()) add(HudLine("currentXp", "&bCata XP: &f${profile.catacombsExperience.format()}"))
            if (showRemaining.get()) add(HudLine("remaining", "&bRemaining: &f${remaining.format()}"))
            if (showFloor.get()) add(HudLine("floor", "&bFloor: &f${floorValue()}"))
            if (showXpPerRun.get()) add(HudLine("xpPerRun", "&bXP/Run: &f${xpPerRun.format()} &7($source)"))
            if (showProfile.get()) add(HudLine("profile", "&bProfile: &f${profile.profileName}"))
            if (showLastRun.get()) add(HudLine("lastRun", "&bLast Run: &f${last?.normalizedXpDelta?.format() ?: "N/A"}"))
            if (showObservedCount.get()) add(HudLine("observedCount", "&bRuns: &f${observedRunCountForFloor()}"))
            if (showChestProfit.get()) {
                val stats = chestProfitStats()
                add(HudLine("profit", "&bProfit: &a${stats.profit.formatCoins()} &7(${stats.label})"))
                if (showChestCount.get()) add(HudLine("chestsOpened", "&bChest: &f${stats.chests}"))
                if (showLastChest.get()) add(HudLine("lastChest", "&bLast Chest: &f${state.lastChestName.ifBlank { "N/A" }} &a${state.lastChestProfit.formatCoins()}"))
                add(HudLine("avgChest", "&bAvg Chest: &a${stats.average.formatCoins()}"))
            }
        }
    }

    private fun orderedHudLines(lines: List<HudLine>): List<HudLine> {
        if (lines.isEmpty()) return lines
        val byId = lines.associateBy { it.id }
        val orderedIds = normalizedHudLineOrder() + lines.map { it.id }.filter { it !in DEFAULT_HUD_LINE_ORDER }
        return orderedIds.mapNotNull { byId[it] }
    }

    private fun normalizedHudLineOrder(): List<String> {
        val normalized = state.hudLineOrder.orEmpty()
            .filter { it in DEFAULT_HUD_LINE_ORDER }
            .distinct()
            .toMutableList()
        DEFAULT_HUD_LINE_ORDER.filterTo(normalized) { it !in normalized }
        return normalized
    }

    fun renderHudOrderOverlay(graphics: GuiGraphics, mouseX: Double, mouseY: Double) {
        if (!renderHud.get() || !isEnabled()) return
        val dragging = draggedHudLineId != null
        val lines = editableHudLines()
        if (lines.isEmpty()) {
            drawDirect(graphics, lines)
            return
        }

        val shiftDown = isShiftDown()
        val hoverId = if (shiftDown || dragging) hudLineAt(mouseX, mouseY, lines)?.id else null
        if (dragging) hoveredHudLineId = hoverId
        drawHudOrderOverlay(graphics, lines, hoverId)
    }

    fun onHudOrderMouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, shiftDown: Boolean): Boolean {
        val liveMouse = currentScaledMousePosition()
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT &&
            (hudModeButtonContains(event.x(), event.y()) || hudModeButtonContains(liveMouse.first, liveMouse.second))
        ) {
            toggleHudViewMode()
            return true
        }
        if (!shiftDown || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        val hit = hudLineAt(event.x(), event.y(), editableHudLines()) ?: return false
        draggedHudLineId = hit.id
        hoveredHudLineId = hit.id
        return true
    }

    fun onHudModeButtonPressed(): Boolean {
        val screen = mc.screen
        if (screen !is AbstractContainerScreen<*>) return false
        val mouse = currentScaledMousePosition()
        if (!hudModeButtonContains(mouse.first, mouse.second)) return false
        toggleHudViewMode()
        return true
    }

    fun onHudOrderMouseDragged(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggedHudLineId == null) return false
        hoveredHudLineId = hudLineAt(event.x(), event.y(), editableHudLines())?.id
        return true
    }

    fun onHudOrderMouseReleased(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        val draggedId = draggedHudLineId ?: return false
        val targetId = hudLineAt(event.x(), event.y(), editableHudLines())?.id ?: hoveredHudLineId
        draggedHudLineId = null
        hoveredHudLineId = null

        if (targetId != null && targetId != draggedId && moveHudLine(draggedId, targetId)) {
            log("HUD line order changed dragged=$draggedId target=$targetId order=${normalizedHudLineOrder().joinToString(",")}")
        }
        return true
    }

    private fun editableHudLines(): List<HudLine> {
        if (!renderHud.get() || !isEnabled()) return emptyList()
        if (currentHudMode() == HUD_MODE_ITEMS) return emptyList()
        return (buildProfitHudTopRows() + buildProfitHudBottomRows()).map { HudLine(it.id, it.label) }
    }

    private fun buildEditPreviewHudLines(): List<HudLine> = buildList {
        if (showCurrentLevel.get()) add(HudLine("currentLevel", "&bCata Level: &fC49"))
        if (showLevelProgress.get()) add(HudLine("levelProgress", "&bNext Level: &f73.4%"))
        if (showTarget.get()) add(HudLine("target", "&bTarget: &fC50"))
        if (showRunsLeft.get()) add(HudLine("runsLeft", "&bRuns Left: &a259"))
        if (showCurrentXp.get()) add(HudLine("currentXp", "&bCata XP: &f453,559,640"))
        if (showRemaining.get()) add(HudLine("remaining", "&bRemaining: &f116,250,000"))
        if (showFloor.get()) add(HudLine("floor", "&bFloor: &fM7"))
        if (showXpPerRun.get()) add(HudLine("xpPerRun", "&bXP/Run: &f450,000 &7(fallback)"))
        if (showProfile.get()) add(HudLine("profile", "&bProfile: &fSelected"))
        if (showLastRun.get()) add(HudLine("lastRun", "&bLast Run: &f450,000"))
        if (showObservedCount.get()) add(HudLine("observedCount", "&bRuns: &f12"))
        if (showChestProfit.get()) {
            add(HudLine("profit", "&bProfit: &a12.3M coins &7(session)"))
            if (showChestCount.get()) add(HudLine("chestsOpened", "&bChest: &f8"))
            if (showLastChest.get()) add(HudLine("lastChest", "&bLast Chest: &fObsidian &a1.2M coins"))
            add(HudLine("avgChest", "&bAvg Chest: &a1.5M coins"))
        }
    }

    private fun hudLineAt(mouseX: Double, mouseY: Double, lines: List<HudLine>): HudLineBounds? =
        hudLineBounds(lines).firstOrNull { mouseX >= it.left && mouseX <= it.right && mouseY >= it.top && mouseY <= it.bottom }

    private fun hudLineBounds(lines: List<HudLine>): List<HudLineBounds> {
        if (lines.isEmpty()) return emptyList()
        val drawX = if (x.isFinite()) x else 10.0
        val drawY = if (y.isFinite()) y else 10.0
        val renderScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val topRows = buildProfitHudTopRows()
        val bottomRows = buildProfitHudBottomRows()
        val layout = hudPanelLayout("Dungeon Profit Hud", topRows, bottomRows)
        val left = drawX
        val right = drawX + layout.width * renderScale

        val bounds = mutableListOf<HudLineBounds>()
        var yOffset = HUD_TOP_PADDING + HUD_TITLE_HEIGHT
        for (row in topRows) {
            val top = drawY + yOffset * renderScale
            val bottom = drawY + (yOffset + HUD_ROW_HEIGHT) * renderScale
            bounds.add(HudLineBounds(row.id, row.label, left, top, right, bottom))
            yOffset += HUD_ROW_HEIGHT
        }
        yOffset = layout.dividerY + 1 + HUD_PROFIT_GAP
        for (row in bottomRows) {
            val top = drawY + yOffset * renderScale
            val bottom = drawY + (yOffset + HUD_ROW_HEIGHT) * renderScale
            bounds.add(HudLineBounds(row.id, row.label, left, top, right, bottom))
            yOffset += HUD_ROW_HEIGHT
        }
        return bounds
    }

    private fun drawHudOrderOverlay(graphics: GuiGraphics, lines: List<HudLine>, hoverId: String?) {
        val drawX = if (x.isFinite()) x.toFloat() else 10f
        val drawY = if (y.isFinite()) y.toFloat() else 10f
        val renderScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val hintY = (drawY - (mc.font.lineHeight + HUD_LINE_GAP + 2) * renderScale).coerceAtLeast(2f)
        graphics.drawString(mc.font, Component.literal(HUD_ORDER_HINT.colorize()), drawX.toInt(), hintY.toInt(), 0xFFFFFFFF.toInt(), true)

        drawDirect(graphics, lines)

        graphics.pose().pushMatrix()
        graphics.pose().translate(drawX, drawY)
        graphics.pose().scale(renderScale, renderScale)

        val draggedId = draggedHudLineId
        val targetId = if (draggedId != null) hoveredHudLineId ?: hoverId else hoverId
        val topRows = buildProfitHudTopRows()
        val bottomRows = buildProfitHudBottomRows()
        val layout = hudPanelLayout("Dungeon Profit Hud", topRows, bottomRows)
        val panelWidth = layout.width

        fun highlightRows(rows: List<ProfitHudRow>, startY: Int) {
            var yOffset = startY
            for (row in rows) {
                val highlight = when {
                    row.id == draggedId -> 0x663399FF
                    draggedId != null && row.id == targetId -> 0x6644CC66
                    draggedId == null && row.id == hoverId -> 0x33FFFFFF
                    else -> null
                }
                if (highlight != null) {
                    graphics.fill(8, yOffset, panelWidth - 8, yOffset + HUD_ROW_HEIGHT, highlight)
                }
                yOffset += HUD_ROW_HEIGHT
            }
        }
        highlightRows(topRows, HUD_TOP_PADDING + HUD_TITLE_HEIGHT)
        val dividerY = HUD_TOP_PADDING + HUD_TITLE_HEIGHT + topRows.size * HUD_ROW_HEIGHT + HUD_PROFIT_GAP / 2
        highlightRows(bottomRows, dividerY + 1 + HUD_PROFIT_GAP)

        graphics.pose().popMatrix()
    }

    private fun moveHudLine(draggedId: String, targetId: String): Boolean {
        if (draggedId !in DEFAULT_HUD_LINE_ORDER || targetId !in DEFAULT_HUD_LINE_ORDER) return false
        val order = normalizedHudLineOrder().toMutableList()
        val targetIndex = order.indexOf(targetId)
        if (targetIndex < 0) return false
        if (!order.remove(draggedId)) return false
        order.add(targetIndex.coerceAtMost(order.size), draggedId)
        if (order == state.hudLineOrder) return false
        state.hudLineOrder = order
        saveState()
        setLines(buildLines())
        return true
    }

    private fun isShiftDown(): Boolean {
        val handle = mc.window.handle()
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
    }

    private fun scanCurrentChestScreen() {
        if (!trackChestProfit.get()) return
        val screen = currentChestScreen()
        if (screen == null) {
            pendingChestProfit = null
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

        val cost = chestCost(plainLore)
        var itemValue = 0L
        var itemCount = 0
        val trackedDrops = mutableListOf<TrackedDrop>()
        val containerSlotCount = chestContainerSlotCount(items.size)

        for (stack in items.take(containerSlotCount)) {
            if (stack.isEmpty || stack.item == Items.GRAY_STAINED_GLASS_PANE) continue
            parseChestItem(stack)?.let {
                if (!it.essence || includeEssenceProfit.get()) {
                    itemValue += it.totalValue.toLong()
                }
                itemCount++
                it.trackedDrop?.let(trackedDrops::add)
                if (verbose) {
                    log("Chest screen item parsed chest=$title name=${stack.hoverName.string.cleanMc()} id=${it.itemId} unit=${it.unitValue} amount=${it.amount} essence=${it.essence} total=${it.totalValue}")
                }
            }
        }

        return ChestProfitCandidate(title, itemValue - cost, cost, itemCount, containerSlotCount, trackedDrops)
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
            for (index in 0 until containerSlotCount) {
                val stack = items.getOrNull(index) ?: continue
                append('|')
                append(index)
                append(':')
                append(stack.count)
                append(':')
                append(stack.hoverName.string.cleanMc())
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

    private fun parseBestCroesusChest(screen: AbstractContainerScreen<*>): ChestProfitCandidate? {
        val parsedFromScreen = parseCroesusCandidatesFromScreen(screen)
        val devonianCandidates = devonianChestProfitCandidates()
        val candidatesByName = parsedFromScreen + devonianCandidates.mapValues { (_, candidate) ->
            candidate.withTrackedDropsFrom(parsedFromScreen[candidate.chestName])
        }
        if (candidatesByName.isNotEmpty()) {
            lastCroesusCandidates = candidatesByName
            val best = candidatesByName.values.maxByOrNull { it.profit }
            log("Fake open Croesus candidates=${candidatesByName.values.joinToString { "${it.chestName}:${it.profit}" }} best=${best?.summary()}")
            return best
        }

        return null
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

    private fun devonianChestProfitCandidates(): Map<String, ChestProfitCandidate> {
        val candidates = linkedMapOf<String, ChestProfitCandidate>()
        // Lowest-level listener first, visible HUD features last. If the same chest exists in
        // multiple Devonian caches, the value displayed by Devonian's profit HUD should win.
        candidates.putAll(devonianCroesusListenerCandidates())
        candidates.putAll(devonianChestProfitFeatureCandidates())
        candidates.putAll(devonianCroesusProfitCandidates())
        return candidates
    }

    private fun devonianChestProfitFeatureCandidates(): Map<String, ChestProfitCandidate> = runCatching {
        val cls = Class.forName("com.github.synnerz.devonian.features.dungeons.ChestProfit")
        val instance = kotlinObjectInstance(cls)
        val currentChestData = (call(instance, "getCurrentChestData") ?: objectField(cls, instance, "currentChestData")) as? Map<*, *>
            ?: return@runCatching emptyMap()

        currentChestData.mapNotNull { (key, value) ->
            val chestName = key?.toString() ?: return@mapNotNull null
            if (!chestNames.contains(chestName) || value == null) return@mapNotNull null
            val itemCount = ((readMember(value, "itemData") as? Collection<*>)?.size ?: 0)
            if (itemCount <= 0) return@mapNotNull null
            val profit = (call(value, "profit") as? Number)?.toLong() ?: return@mapNotNull null
            val cost = ((readMember(value, "chestPrice") as? Number)?.toInt() ?: 0)
            ChestProfitCandidate(chestName, profit, cost, itemCount, scannedSlots = 0)
        }.associateBy { it.chestName }
    }.onFailure {
        log("Devonian ChestProfit unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(emptyMap())

    private fun devonianCroesusProfitCandidates(): Map<String, ChestProfitCandidate> = runCatching {
        val cls = Class.forName("com.github.synnerz.devonian.features.dungeons.CroesusProfit")
        val instance = kotlinObjectInstance(cls)
        val chestsData = objectField(cls, instance, "chestsData") as? Map<*, *>
            ?: return@runCatching emptyMap()

        chestsData.mapNotNull { (key, value) ->
            val chestName = key?.toString() ?: return@mapNotNull null
            if (!chestNames.contains(chestName) || value == null) return@mapNotNull null
            val bought = (readMember(value, "bought") as? Boolean) ?: false
            if (bought) return@mapNotNull null
            val itemCount = ((readMember(value, "items") as? Collection<*>)?.size ?: 0)
            if (itemCount <= 0) return@mapNotNull null
            val profit = (call(value, "totalProfit") as? Number)?.toLong() ?: return@mapNotNull null
            if (profit == Int.MIN_VALUE.toLong()) return@mapNotNull null
            val cost = ((readMember(value, "chestPrice") as? Number)?.toInt() ?: 0)
            val slot = ((readMember(value, "slotIdx") as? Number)?.toInt() ?: 0)
            ChestProfitCandidate(chestName, profit, cost, itemCount, slot)
        }.associateBy { it.chestName }
    }.onFailure {
        log("Devonian CroesusProfit unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(emptyMap())

    private fun devonianCroesusListenerCandidates(): Map<String, ChestProfitCandidate> = runCatching {
        val cls = Class.forName("com.github.synnerz.devonian.api.dungeon.CroesusListener")
        val instance = kotlinObjectInstance(cls)
        val chestsData = objectField(cls, instance, "chestsData") as? Map<*, *>
            ?: return@runCatching emptyMap()

        chestsData.mapNotNull { (key, value) ->
            val chestName = key?.toString() ?: return@mapNotNull null
            if (!chestNames.contains(chestName) || value == null) return@mapNotNull null
            val purchased = (readMember(value, "purchased") as? Boolean) ?: false
            if (purchased) return@mapNotNull null
            val itemCount = ((readMember(value, "items") as? Collection<*>)?.size ?: 0)
            if (itemCount <= 0) return@mapNotNull null
            val profit = (call(value, "totalProfit", false) as? Number)?.toLong()
                ?: (call(value, "totalProfit") as? Number)?.toLong()
                ?: return@mapNotNull null
            if (profit == Int.MIN_VALUE.toLong()) return@mapNotNull null
            val cost = ((readMember(value, "price") as? Number)?.toInt() ?: 0)
            val slot = ((readMember(value, "slot") as? Number)?.toInt() ?: 0)
            ChestProfitCandidate(chestName, profit, cost, itemCount, slot)
        }.associateBy { it.chestName }
    }.onFailure {
        log("Devonian CroesusListener unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(emptyMap())

    private fun kotlinObjectInstance(cls: Class<*>): Any? =
        runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()

    private fun objectField(cls: Class<*>, instance: Any?, name: String): Any? {
        val field = cls.getDeclaredField(name)
        field.isAccessible = true
        return runCatching { field.get(instance) }.getOrElse { field.get(null) }
    }

    private fun readMember(target: Any, name: String): Any? {
        val getter = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return call(target, getter) ?: runCatching {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(target)
        }.getOrNull()
    }

    private fun call(target: Any?, name: String, vararg args: Any?): Any? {
        val cls = target?.javaClass ?: return null
        val method = cls.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    private fun parseCroesusChestItem(chestName: String, stack: ItemStack, slot: Int): ChestProfitCandidate? {
        val plainLore = plainLore(stack)
        val formattedLore = ItemUtils.lore(stack, true) ?: emptyList()
        var cost = 0
        var itemValue = 0L
        var itemCount = 0
        val trackedDrops = mutableListOf<TrackedDrop>()

        for (idx in plainLore.indices) {
            val line = plainLore[idx]
            if (line == "Already opened!") return null
            if (line == "No chests opened yet!" || line == "Contents" || line.isBlank()) continue

            if (line == "Cost") {
                cost = chestCost(plainLore)
                continue
            }

            parseCroesusLoreItem(line, formattedLore.getOrNull(idx) ?: line)?.let {
                if (!it.essence || includeEssenceProfit.get()) itemValue += it.totalValue.toLong()
                itemCount++
                it.trackedDrop?.let(trackedDrops::add)
            }
        }

        if (itemCount == 0) return null
        return ChestProfitCandidate(chestName, itemValue - cost, cost, itemCount, scannedSlots = slot, trackedDrops = trackedDrops)
    }

    private fun parseCroesusLoreItem(line: String, formattedLine: String): ChestProfitItem? {
        enchantedBookRegex.matchEntire(line)?.groupValues?.drop(1)?.let { match ->
            val enchantName = match[0].replace(" ", "_").uppercase(Locale.ROOT)
            val tier = StringUtils.parseRoman(match[1])
            var id = "ENCHANTMENT_${enchantName}_$tier"
            var price = SkyblockPrices.buyPrice(id).roundToInt()
            if (price == 0) {
                id = "ENCHANTMENT_ULTIMATE_${enchantName}_$tier"
                price = SkyblockPrices.buyPrice(id).roundToInt()
            }
            if (price > 0) return ChestProfitItem(id, price, 1, essence = false)
        }

        essenceRegex.matchEntire(line)?.groupValues?.drop(1)?.let { match ->
            val type = match[0].uppercase(Locale.ROOT)
            val amount = match[1].toIntOrNull() ?: return null
            val id = "ESSENCE_$type"
            val price = SkyblockPrices.buyPrice(id).roundToInt()
            if (price > 0) return ChestProfitItem(id, price, amount, essence = true)
        }

        var id = line.uppercase(Locale.ROOT)
            .replace("- ", "")
            .replace("'", "")
            .replace(" ", "_")
        id = normalizeItemId(id)

        val price = itemPrice(id)
        val trackedDrop = trackedDropFor(id, line)
        if (price <= 0) {
            if (trackedDrop != null) return ChestProfitItem(id, 0, 1, essence = false, trackedDrop = trackedDrop)
            log("Croesus item price missing id=$id line=$line formatted=$formattedLine")
            return null
        }
        return ChestProfitItem(id, price, 1, essence = false, trackedDrop = trackedDrop)
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
        val plainLore = plainLore(stack)

        enchantedBookRegex.matchEntire(name)?.groupValues?.drop(1)?.let { match ->
            val enchantName = match[0].replace(" ", "_").uppercase(Locale.ROOT)
            val tier = StringUtils.parseRoman(match[1])
            var id = "ENCHANTMENT_${enchantName}_$tier"
            var price = SkyblockPrices.buyPrice(id).roundToInt()
            if (price == 0) {
                id = "ENCHANTMENT_ULTIMATE_${enchantName}_$tier"
                price = SkyblockPrices.buyPrice(id).roundToInt()
            }
            if (price > 0) return ChestProfitItem(id, price, 1, essence = false)
        }

        for (line in plainLore) {
            essenceRegex.matchEntire(line)?.groupValues?.drop(1)?.let { match ->
                val type = match[0].uppercase(Locale.ROOT)
                val amount = match[1].toIntOrNull() ?: return@let
                val id = "ESSENCE_$type"
                val price = SkyblockPrices.buyPrice(id).roundToInt()
                if (price > 0) return ChestProfitItem(id, price, amount, essence = true)
            }
        }

        var id = ItemUtils.skyblockId(stack).orEmpty()
        if (id.isBlank()) {
            id = name.uppercase(Locale.ROOT)
                .replace("- ", "")
                .replace("'", "")
                .replace(" ", "_")
        }
        id = normalizeItemId(id)

        val price = itemPrice(id)
        val trackedDrop = trackedDropFor(id, name)
        if (price <= 0) {
            if (trackedDrop != null) return ChestProfitItem(id, 0, 1, essence = false, trackedDrop = trackedDrop)
            return null
        }
        return ChestProfitItem(id, price, 1, essence = false, trackedDrop = trackedDrop)
    }

    private fun normalizeItemId(id: String): String = specialIds[id] ?: id

    private fun trackedDropFor(itemId: String, displayName: String): TrackedDrop? {
        val definition = TRACKED_DROPS_BY_ALIAS[normalizeTrackedAlias(itemId)]
            ?: TRACKED_DROPS_BY_ALIAS[normalizeTrackedAlias(displayName.cleanMc())]
            ?: return null
        return TrackedDrop(definition.key, definition.displayName)
    }

    private fun itemPrice(id: String): Int {
        val direct = SkyblockPrices.buyPrice(id).roundToInt()
        if (direct > 0) return direct
        return hardcodedItemPrices[id] ?: 0
    }

    private fun chestCost(lore: List<String>): Int {
        val costIndex = lore.indexOf("Cost")
        if (costIndex < 0) return 0
        val coins = lore.getOrNull(costIndex + 1)?.let {
            costRegex.matchEntire(it)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
        } ?: 0
        val keyCost = if (includeDungeonKeyCost.get() && lore.getOrNull(costIndex + 2) == "Dungeon Chest Key") {
            SkyblockPrices.buyPrice("DUNGEON_CHEST_KEY").roundToInt()
        } else {
            0
        }
        return coins + keyCost
    }

    private fun plainLore(stack: ItemStack): List<String> = ItemUtils.lore(stack)?.map { it.cleanMc() } ?: emptyList()

    private fun currentChestScreen(): AbstractContainerScreen<*>? = mc.screen as? AbstractContainerScreen<*>

    private fun recordChestProfit(candidate: ChestProfitCandidate, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecordedChestAt < 2_000 && state.lastChestName == candidate.chestName && state.lastChestProfit == candidate.profit) {
            log("Duplicate chest profit ignored source=$source: ${candidate.summary()}")
            return
        }

        val floor = floorValue()
        ensureSessionStarted(now, "chest-profit-$source")
        lastRecordedChestAt = now
        state.lastChestName = candidate.chestName
        state.lastChestProfit = candidate.profit
        sessionChestProfit += candidate.profit
        sessionChestsOpened++
        state.totalChestProfit += candidate.profit
        state.totalChestsOpened++
        state.chestProfits.add(
            ChestProfitSample(
                timestamp = now,
                chestName = candidate.chestName,
                profit = candidate.profit,
                profileName = data?.profileName.orEmpty(),
                floorLabel = floor,
            )
        )
        if (shouldTrackM7Drops(floor)) {
            candidate.trackedDrops.forEach { drop ->
                state.trackedItemDrops.add(
                    TrackedItemDropSample(
                        timestamp = now,
                        itemKey = drop.key,
                        displayName = drop.displayName,
                        chestName = candidate.chestName,
                        floorLabel = floor,
                        profileName = data?.profileName.orEmpty(),
                    )
                )
            }
        }
        while (state.trackedItemDrops.size > MAX_TRACKED_ITEM_DROPS) state.trackedItemDrops.removeAt(0)
        decrementCroesusUnclaimedCount("chest-profit-$source")
        while (state.chestProfits.size > 250) state.chestProfits.removeAt(0)
        saveState()
        log("Recorded chest profit source=$source ${candidate.summary()} tracked=${candidate.trackedDrops.joinToString { it.key }} samples=${state.chestProfits.size} sessionProfit=$sessionChestProfit totalProfit=${state.totalChestProfit}")
    }

    private fun fetchProfile(request: ProfileRequest): ProfileData {
        val encodedUuid = URLEncoder.encode(request.playerUuid, StandardCharsets.UTF_8)
        log("Fetching Hypixel profile user=${request.playerName} uuid=${request.playerUuid}")
        val connection = URI("https://api.hypixel.net/v2/skyblock/profiles?uuid=$encodedUuid").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("API-Key", request.apiKey)
            connection.setRequestProperty("User-Agent", "DungeonProgressHud/1.0.0")

            val code = connection.responseCode
            log("Hypixel response code=$code")
            if (code == 403) error("Invalid API key")
            if (code == 429) error("Rate limited")
            if (code !in 200..299) error("Hypixel HTTP $code")

            val root = connection.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader).asJsonObject
            }
            if (root.get("success")?.asBoolean != true) error(root.get("cause")?.asString ?: "Hypixel API failed")
            val profiles = root.getAsJsonArray("profiles") ?: error("No SkyBlock profiles")
            val selected = profiles.map { it.asJsonObject }.firstOrNull { it.get("selected")?.asBoolean == true }
                ?: error("No selected SkyBlock profile")
            val selectedProfileName = selected.get("cute_name")?.asString ?: "Unknown"
            log("Selected profile cute_name=$selectedProfileName")
            val member = selected.getAsJsonObject("members")?.getAsJsonObject(request.playerUuid) ?: error("Selected profile missing player")
            val dungeons = member.getAsJsonObject("dungeons") ?: error("Dungeon API unavailable")
            val catacombs = dungeons.getAsJsonObject("dungeon_types")?.getAsJsonObject("catacombs") ?: error("Catacombs data unavailable")

            return ProfileData(
                playerName = request.playerName,
                playerUuid = request.playerUuid,
                profileName = selectedProfileName,
                catacombsExperience = catacombs.get("experience")?.asLong ?: 0L,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun recordSample(profile: ProfileData, recordObservedSample: Boolean) {
        if (state.lastPlayerUuid != profile.playerUuid) {
            state.lastPlayerUuid = profile.playerUuid
            state.lastCatacombsXp = profile.catacombsExperience
            saveState()
            return
        }

        val delta = profile.catacombsExperience - state.lastCatacombsXp
        state.lastCatacombsXp = profile.catacombsExperience
        if (!recordObservedSample) {
            log("API XP baseline updated without observed sample rawDelta=$delta floor=${floorValue()}")
            saveState()
            return
        }

        val normalized = normalizeRunXp(delta)
        log("Recorded sample rawDelta=$delta normalized=$normalized floor=${floorValue()}")
        if (isRecentChatRunSample(delta, normalized)) {
            log("API XP sample skipped because it matches recent dungeon completion chat rawDelta=$delta normalized=$normalized")
            saveState()
            return
        }
        if (normalized > 0) {
            ensureSessionStarted(System.currentTimeMillis(), "api-xp-sample")
            state.samples.add(RunSample(System.currentTimeMillis(), floorValue(), delta, normalized))
            while (state.samples.size > 100) state.samples.removeAt(0)
        }
        saveState()
    }

    fun parseDungeonCompletionMessage(message: String) {
        message.cleanMc().lines().forEach { parseDungeonCompletionLine(it) }
    }

    private fun parseDungeonCompletionLine(message: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val normalizedMessage = message.trim().replace(Regex("\\s*\\(x\\d+\\)$"), "")
        if (normalizedMessage.isBlank()) return false
        if (timestamp - pendingCompletionAt > 30_000) {
            pendingCompletionFloor = ""
            pendingCompletionTimeSeconds = 0
            pendingCompletionScore = 0
            pendingCompletionGrade = ""
        }

        parseCompletionFloor(normalizedMessage)?.let {
            pendingCompletionAt = timestamp
            pendingCompletionFloor = it
        }
        if (normalizedMessage.contains("Defeated", true)) {
            pendingCompletionAt = timestamp
            pendingCompletionTimeSeconds = parseCompletionTimeSeconds(normalizedMessage)
        }
        parseCompletionScore(normalizedMessage)?.let {
            pendingCompletionAt = timestamp
            pendingCompletionScore = it.first
            pendingCompletionGrade = it.second
        }

        val cataXp = dungeonCompletionCataXpRegex.find(normalizedMessage)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toLongOrNull()
            ?: run {
                return false
            }

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

        lastDungeonCompletionChat = dedupeKey
        lastDungeonCompletionChatAt = timestamp
        lastDungeonCompletionRawXp = cataXp
        lastDungeonCompletionNormalizedXp = normalizeRunXp(cataXp)
        val runStartedAt = if (pendingCompletionTimeSeconds > 0) {
            timestamp - pendingCompletionTimeSeconds * 1000L
        } else {
            timestamp
        }
        ensureSessionStarted(runStartedAt, "dungeon-completion")
        state.samples.add(RunSample(timestamp, floor, cataXp, lastDungeonCompletionNormalizedXp))
        while (state.samples.size > 100) state.samples.removeAt(0)
        state.runs.add(
            DungeonRunRecord(
                timestamp = timestamp,
                floorLabel = floor,
                runTimeSeconds = pendingCompletionTimeSeconds,
                score = pendingCompletionScore,
                grade = pendingCompletionGrade,
                rawCataXp = cataXp,
                normalizedCataXp = lastDungeonCompletionNormalizedXp,
            )
        )
        incrementCroesusUnclaimedCount("dungeon-completion")
        while (state.runs.size > 500) state.runs.removeAt(0)
        saveState()
        log("Recorded dungeon completion chat XP raw=$cataXp normalized=$lastDungeonCompletionNormalizedXp floor=$floor time=$pendingCompletionTimeSeconds score=$pendingCompletionScore")
        return true
    }

    private fun hasRunRecord(floor: String, rawXp: Long, runTimeSeconds: Int, score: Int, timestamp: Long): Boolean =
        state.runs.any {
            it.floorLabel.equals(floor, true) &&
                it.rawCataXp == rawXp &&
                it.runTimeSeconds == runTimeSeconds &&
                it.score == score &&
                kotlin.math.abs(it.timestamp - timestamp) < 20 * 60 * 1000
        }

    private fun hasRunRecord(records: List<DungeonRunRecord>, floor: String, rawXp: Long, runTimeSeconds: Int, score: Int, timestamp: Long): Boolean =
        records.any {
            it.floorLabel.equals(floor, true) &&
                it.rawCataXp == rawXp &&
                it.runTimeSeconds == runTimeSeconds &&
                it.score == score &&
                kotlin.math.abs(it.timestamp - timestamp) < 20 * 60 * 1000
        }

    private fun hasImportedRunRecord(records: List<ImportedDungeonRun>, floor: String, rawXp: Long, runTimeSeconds: Int, score: Int, timestamp: Long): Boolean =
        records.any {
            it.floorLabel.equals(floor, true) &&
                it.rawCataXp == rawXp &&
                it.runTimeSeconds == runTimeSeconds &&
                it.score == score &&
                kotlin.math.abs(it.timestamp - timestamp) < 20 * 60 * 1000
        }

    private fun parseCompletionFloor(message: String): String? =
        dungeonCompletionFloorRegex.find(message)?.groupValues?.getOrNull(2)?.uppercase(Locale.ROOT)

    private fun parseCompletionTimeSeconds(message: String): Int {
        val match = dungeonCompletionTimeRegex.find(message) ?: return 0
        val minutes = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return 0
        val seconds = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return 0
        return minutes * 60 + seconds
    }

    private fun parseCompletionScore(message: String): Pair<Int, String>? {
        val match = dungeonCompletionScoreRegex.find(message) ?: return null
        val score = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val grade = match.groupValues.getOrNull(2).orEmpty()
        return score to grade
    }

    fun importRecentLogs(manual: Boolean) {
        if (!logsDir.isDirectory) {
            if (manual) send("No Minecraft logs folder found.")
            return
        }

        val existingRuns = state.runs.toList()
        val fallbackFloor = floorValue()
        val lastLogImportAt = state.lastLogImportAt
        thread(name = "DPH Log Import", isDaemon = true) {
            val cutoff = System.currentTimeMillis() - 8L * DAY_MILLIS
            val files = logsDir.listFiles()
                ?.filter { it.isFile && it.lastModified() >= cutoff && (it.extension == "log" || it.name.endsWith(".log.gz")) }
                ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
                ?: emptyList()
            if (!manual && files.none { it.lastModified() > lastLogImportAt }) return@thread

            val importedRuns = mutableListOf<ImportedDungeonRun>()
            var pendingAt = 0L
            var pendingFloor = ""
            var pendingTimeSeconds = 0
            var pendingScore = 0
            var pendingGrade = ""
            var lastDedupeKey = ""
            var lastDedupeAt = 0L

            for (file in files) {
                runCatching {
                    file.forEachLogLine { line ->
                        val chat = extractLogChat(line)
                        if (chat != null) {
                            val timestamp = parseLogTimestamp(line, file)
                            val parsed = parseImportedDungeonCompletionLine(
                                chat,
                                timestamp,
                                fallbackFloor,
                                existingRuns,
                                importedRuns,
                                pendingAt,
                                pendingFloor,
                                pendingTimeSeconds,
                                pendingScore,
                                pendingGrade,
                                lastDedupeKey,
                                lastDedupeAt,
                            )
                            pendingAt = parsed.pending.timestamp
                            pendingFloor = parsed.pending.floor
                            pendingTimeSeconds = parsed.pending.runTimeSeconds
                            pendingScore = parsed.pending.score
                            pendingGrade = parsed.pending.grade
                            lastDedupeKey = parsed.lastDedupeKey
                            lastDedupeAt = parsed.lastDedupeAt
                            parsed.run?.let { importedRuns.add(it) }
                        }
                    }
                }.onFailure {
                    log("Log import failed file=${file.name}: ${it.javaClass.simpleName}: ${it.message}")
                }
            }

            mc.execute { mergeImportedLogRuns(importedRuns, files.size, manual) }
        }
    }

    private fun parseImportedDungeonCompletionLine(
        message: String,
        timestamp: Long,
        fallbackFloor: String,
        existingRuns: List<DungeonRunRecord>,
        importedRuns: List<ImportedDungeonRun>,
        previousPendingAt: Long,
        previousPendingFloor: String,
        previousPendingTimeSeconds: Int,
        previousPendingScore: Int,
        previousPendingGrade: String,
        previousDedupeKey: String,
        previousDedupeAt: Long,
    ): ImportedParseResult {
        val normalizedMessage = message.cleanMc().trim().replace(Regex("\\s*\\(x\\d+\\)$"), "")
        if (normalizedMessage.isBlank()) {
            return ImportedParseResult(
                PendingCompletionState(previousPendingAt, previousPendingFloor, previousPendingTimeSeconds, previousPendingScore, previousPendingGrade),
                previousDedupeKey,
                previousDedupeAt,
                null,
            )
        }

        var pendingAt = previousPendingAt
        var pendingFloor = previousPendingFloor
        var pendingTimeSeconds = previousPendingTimeSeconds
        var pendingScore = previousPendingScore
        var pendingGrade = previousPendingGrade
        if (timestamp - pendingAt > 30_000) {
            pendingFloor = ""
            pendingTimeSeconds = 0
            pendingScore = 0
            pendingGrade = ""
        }

        parseCompletionFloor(normalizedMessage)?.let {
            pendingAt = timestamp
            pendingFloor = it
        }
        if (normalizedMessage.contains("Defeated", true)) {
            pendingAt = timestamp
            pendingTimeSeconds = parseCompletionTimeSeconds(normalizedMessage)
        }
        parseCompletionScore(normalizedMessage)?.let {
            pendingAt = timestamp
            pendingScore = it.first
            pendingGrade = it.second
        }

        val cataXp = dungeonCompletionCataXpRegex.find(normalizedMessage)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toLongOrNull()
        val pending = PendingCompletionState(pendingAt, pendingFloor, pendingTimeSeconds, pendingScore, pendingGrade)
        if (cataXp == null) return ImportedParseResult(pending, previousDedupeKey, previousDedupeAt, null)

        val floor = pendingFloor.ifBlank { fallbackFloor }
        val dedupeKey = "$floor:$cataXp:$pendingTimeSeconds:$pendingScore"
        if (dedupeKey == previousDedupeKey && timestamp - previousDedupeAt < 10_000) {
            return ImportedParseResult(pending, previousDedupeKey, previousDedupeAt, null)
        }
        if (hasRunRecord(existingRuns, floor, cataXp, pendingTimeSeconds, pendingScore, timestamp) ||
            hasImportedRunRecord(importedRuns, floor, cataXp, pendingTimeSeconds, pendingScore, timestamp)
        ) {
            return ImportedParseResult(pending, previousDedupeKey, previousDedupeAt, null)
        }

        return ImportedParseResult(
            pending,
            dedupeKey,
            timestamp,
            ImportedDungeonRun(timestamp, floor, pendingTimeSeconds, pendingScore, pendingGrade, cataXp),
        )
    }

    private fun mergeImportedLogRuns(importedRuns: List<ImportedDungeonRun>, fileCount: Int, manual: Boolean) {
        var imported = 0
        for (run in importedRuns) {
            if (hasRunRecord(run.floorLabel, run.rawCataXp, run.runTimeSeconds, run.score, run.timestamp)) continue
            val normalized = normalizeRunXp(run.rawCataXp)
            state.samples.add(RunSample(run.timestamp, run.floorLabel, run.rawCataXp, normalized))
            state.runs.add(
                DungeonRunRecord(
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
        while (state.samples.size > 100) state.samples.removeAt(0)
        while (state.runs.size > 500) state.runs.removeAt(0)
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

    private fun parseLogTimestamp(line: String, file: File): Long {
        val time = Regex("^\\[(\\d{2}):(\\d{2}):(\\d{2})]").find(line)?.let {
            LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        } ?: return file.lastModified()
        return file.lastModifiedDate().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun File.lastModifiedDate() =
        java.time.Instant.ofEpochMilli(lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun isRecentChatRunSample(raw: Long, normalized: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDungeonCompletionChatAt > 10 * 60 * 1000) return false
        return raw == lastDungeonCompletionRawXp || normalized == lastDungeonCompletionNormalizedXp
    }

    private fun normalizeRunXp(raw: Long): Long {
        if (raw <= 0) return 0
        if (!scaleDailyRunXp.get()) return raw
        if (raw <= dailyThresholdValue()) return raw
        return (raw / dailyMultiplierValue()).roundToLong().coerceAtLeast(1)
    }

    private fun effectiveXpPerRun(): Long {
        if (xpMode.getCurrent() == "Hardcoded") return hardcodedXpPerRunValue()
        return samplesForFloor().takeIf { it.isNotEmpty() }?.map { it.normalizedXpDelta }?.average()?.roundToLong()
            ?: hardcodedXpPerRunValue()
    }

    private fun samplesForFloor(): List<RunSample> {
        val floor = floorValue()
        return state.samples.filter { it.floorLabel.equals(floor, true) && it.normalizedXpDelta > 0 }
    }

    private fun observedRunCountForFloor(): Int {
        val floor = floorValue()
        val runCount = state.runs.count { it.floorLabel.equals(floor, true) && it.normalizedCataXp > 0 }
        return runCount.takeIf { it > 0 } ?: samplesForFloor().size
    }

    private fun chestProfitStats(): ChestProfitStats {
        if (state.chestProfitWindowMillis > 0L) {
            val cutoff = System.currentTimeMillis() - state.chestProfitWindowMillis
            val samples = state.chestProfits.filter { it.timestamp >= cutoff }
            val profit = samples.sumOf { it.profit }
            val chests = samples.size
            val average = if (chests > 0) {
                (profit.toDouble() / chests.toDouble()).roundToLong()
            } else {
                0L
            }
            return ChestProfitStats(formatProfitWindow(state.chestProfitWindowMillis), profit, chests, average)
        }

        if (chestProfitMode.getCurrent() == "Total") {
            val average = if (state.totalChestsOpened > 0) {
                (state.totalChestProfit.toDouble() / state.totalChestsOpened.toDouble()).roundToLong()
            } else {
                0L
            }
            return ChestProfitStats("total", state.totalChestProfit, state.totalChestsOpened, average)
        }

        val average = if (sessionChestsOpened > 0) {
            (sessionChestProfit.toDouble() / sessionChestsOpened.toDouble()).roundToLong()
        } else {
            0L
        }
        return ChestProfitStats("session", sessionChestProfit, sessionChestsOpened, average)
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
        val runs = state.runs.filter { it.timestamp >= cutoff }
        val chests = state.chestProfits.filter { it.timestamp >= cutoff }
        val runCount = runs.size
        val chestCount = chests.size
        val xp = runs.sumOf { it.normalizedCataXp }
        val profit = chests.sumOf { it.profit }
        val runSeconds = runs.sumOf { it.runTimeSeconds.coerceAtLeast(0) }
        val elapsedSeconds = when {
            runSeconds > 0 -> runSeconds
            scope.equals("session", true) -> ((now - sessionStartedAt) / 1000L).coerceAtLeast(1L).toInt()
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

    private fun drawDirect(graphics: GuiGraphics, lines: List<HudLine>) {
        val (title, topRows, bottomRows) = currentHudContent()
        if (topRows.isEmpty() && bottomRows.isEmpty()) return

        graphics.pose().pushMatrix()
        val drawX = if (x.isFinite()) x.toFloat() else 10f
        val drawY = if (y.isFinite()) y.toFloat() else 10f
        val renderScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        graphics.pose().translate(drawX, drawY)
        graphics.pose().scale(renderScale, renderScale)

        val fontHeight = mc.font.lineHeight
        val layout = hudPanelLayout(title, topRows, bottomRows)

        drawProfitPanel(graphics, layout.width, layout.height, layout.dividerY)

        val buttonX = layout.width - HUD_MODE_BUTTON_WIDTH - HUD_MODE_BUTTON_MARGIN
        val titleRight = if (mc.screen is AbstractContainerScreen<*>) buttonX - 4 else layout.width
        val titleX = ((titleRight - mc.font.width(title)) / 2).coerceAtLeast(HUD_SIDE_PADDING)
        graphics.drawString(mc.font, Component.literal(title), titleX, HUD_TOP_PADDING, HUD_SKETCH_WHITE, true)
        if (mc.screen is AbstractContainerScreen<*>) {
            drawHudModeButton(graphics, layout.width)
        }
        var yOffset = HUD_TOP_PADDING + HUD_TITLE_HEIGHT
        yOffset = drawProfitRows(graphics, topRows, HUD_SIDE_PADDING, layout.separatorX, layout.valueX, yOffset, HUD_ROW_HEIGHT, fontHeight)
        drawProfitScopeLabel(graphics, layout)
        yOffset = layout.dividerY + 1 + HUD_PROFIT_GAP
        drawProfitRows(graphics, bottomRows, HUD_SIDE_PADDING, layout.separatorX, layout.valueX, yOffset, HUD_ROW_HEIGHT, fontHeight)

        graphics.pose().popMatrix()
    }

    private fun currentHudContent(): Triple<String, List<ProfitHudRow>, List<ProfitHudRow>> =
        if (currentHudMode() == HUD_MODE_ITEMS) {
            Triple("Dungeon Item Tracker", buildItemTrackerTopRows(), buildItemTrackerRows())
        } else {
            Triple("Dungeon Profit Hud", buildProfitHudTopRows(), buildProfitHudBottomRows())
        }

    private fun currentHudMode(): String =
        if (state.hudViewMode == HUD_MODE_ITEMS) HUD_MODE_ITEMS else HUD_MODE_PROFIT

    private fun toggleHudViewMode() {
        state.hudViewMode = if (currentHudMode() == HUD_MODE_ITEMS) HUD_MODE_PROFIT else HUD_MODE_ITEMS
        saveState()
        log("HUD view mode toggled mode=${state.hudViewMode}")
    }

    private fun hudPanelLayout(title: String, topRows: List<ProfitHudRow>, bottomRows: List<ProfitHudRow>): HudPanelLayout {
        val allRows = sharedHudLayoutRows()
        val labelWidth = allRows.maxOfOrNull { mc.font.width(it.label) } ?: 72
        val valueWidth = allRows.maxOfOrNull { mc.font.width(it.value) + if (it.suffix.isBlank()) 0 else 10 + mc.font.width(it.suffix) } ?: 64
        val separatorX = HUD_SIDE_PADDING + labelWidth + 8
        val valueX = separatorX + 8
        val titleButtonSpace = if (mc.screen is AbstractContainerScreen<*>) {
            HUD_MODE_BUTTON_WIDTH + HUD_MODE_BUTTON_MARGIN * 2
        } else {
            0
        }
        val sharedTitleWidth = max(mc.font.width("Dungeon Profit Hud"), mc.font.width("Dungeon Item Tracker"))
        val width = max(valueX + valueWidth + HUD_SIDE_PADDING, sharedTitleWidth + HUD_SIDE_PADDING * 2 + titleButtonSpace)
        val dividerY = HUD_TOP_PADDING + HUD_TITLE_HEIGHT + topRows.size * HUD_ROW_HEIGHT + HUD_PROFIT_GAP / 2
        val height = dividerY + 1 + HUD_PROFIT_GAP + bottomRows.size * HUD_ROW_HEIGHT + HUD_TOP_PADDING
        return HudPanelLayout(width, dividerY, height, separatorX, valueX)
    }

    private fun sharedHudLayoutRows(): List<ProfitHudRow> =
        buildProfitHudTopRows() + buildProfitHudBottomRows() + buildItemTrackerTopRows() + buildItemTrackerRows()

    private fun drawHudModeButton(graphics: GuiGraphics, panelWidth: Int) {
        val label = if (currentHudMode() == HUD_MODE_ITEMS) "Profit" else "Items"
        val x = panelWidth - HUD_MODE_BUTTON_WIDTH - HUD_MODE_BUTTON_MARGIN
        val y = HUD_TOP_PADDING + (mc.font.lineHeight - HUD_MODE_BUTTON_HEIGHT) / 2
        drawRoundedFill(graphics, x, y, HUD_MODE_BUTTON_WIDTH, HUD_MODE_BUTTON_HEIGHT, 0x99101010.toInt())
        drawRoundedBorder(graphics, x, y, HUD_MODE_BUTTON_WIDTH, HUD_MODE_BUTTON_HEIGHT, HUD_SKETCH_WHITE)
        val textX = x + (HUD_MODE_BUTTON_WIDTH - mc.font.width(label)) / 2
        val textY = y + (HUD_MODE_BUTTON_HEIGHT - mc.font.lineHeight) / 2
        graphics.drawString(mc.font, Component.literal(label), textX, textY, HUD_SKETCH_WHITE, true)
    }

    private fun drawProfitScopeLabel(graphics: GuiGraphics, layout: HudPanelLayout) {
        val label = "(${chestProfitStats().label})"
        val x = (layout.width - HUD_SIDE_PADDING - mc.font.width(label)).coerceAtLeast(layout.valueX)
        val y = if (currentHudMode() == HUD_MODE_ITEMS) {
            HUD_TOP_PADDING + HUD_TITLE_HEIGHT - 8
        } else {
            layout.dividerY + 4
        }
        graphics.drawString(mc.font, Component.literal(label), x, y, HUD_SKETCH_WHITE, true)
    }

    private fun hudModeButtonContains(mouseX: Double, mouseY: Double): Boolean {
        if (mc.screen !is AbstractContainerScreen<*>) return false
        if (!renderHud.get() || !isEnabled()) return false
        val (title, topRows, bottomRows) = currentHudContent()
        val layout = hudPanelLayout(title, topRows, bottomRows)
        val drawX = if (x.isFinite()) x else 10.0
        val drawY = if (y.isFinite()) y else 10.0
        val renderScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val left = drawX + (layout.width - HUD_MODE_BUTTON_WIDTH - HUD_MODE_BUTTON_MARGIN - 6) * renderScale
        val top = drawY + (HUD_TOP_PADDING - 3) * renderScale
        val right = drawX + layout.width * renderScale
        val bottom = drawY + (HUD_TOP_PADDING + HUD_MODE_BUTTON_HEIGHT + 3) * renderScale
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom
    }

    private fun currentScaledMousePosition(): Pair<Double, Double> {
        val window = mc.window
        return mc.mouseHandler.getScaledXPos(window) to mc.mouseHandler.getScaledYPos(window)
    }

    private fun buildProfitHudTopRows(): List<ProfitHudRow> {
        val profile = data
        val xpPerRun = effectiveXpPerRun()
        val samples = samplesForFloor()
        val last = samples.lastOrNull()
        val remaining = profile?.let { xpRemaining(it.catacombsExperience, targetLevelValue()) } ?: 0L
        val runs = xpPerRun.takeIf { it > 0 }?.let { ceil(remaining.toDouble() / it.toDouble()).toLong() }
        val currentLevel = profile?.let { currentCataLevel(it.catacombsExperience) } ?: 0
        return orderProfitRows(
            buildList {
                add(ProfitHudRow("sessionTime", "Session Time", sessionDurationText()))
                if (showCurrentLevel.get()) add(ProfitHudRow("currentLevel", "Cata Level", currentLevel.toString()))
                if (showTarget.get()) add(ProfitHudRow("target", "Target", targetLevelValue().toString()))
                if (showLevelProgress.get()) add(ProfitHudRow("levelProgress", "Next Level", profile?.let { "${levelProgressPercent(it.catacombsExperience)}%" } ?: "N/A"))
                if (showRunsLeft.get()) add(ProfitHudRow("runsLeft", "Runs Left", runs?.formatCompact() ?: "N/A"))
                if (showCurrentXp.get()) add(ProfitHudRow("currentXp", "Cata XP", profile?.catacombsExperience?.formatCompact() ?: "N/A"))
                if (showRemaining.get()) add(ProfitHudRow("remaining", "Remaining", remaining.formatCompact()))
                if (showFloor.get()) add(ProfitHudRow("floor", "Floor", floorValue()))
                if (showXpPerRun.get()) add(ProfitHudRow("xpPerRun", "XP/Run", xpPerRun.formatCompact(), xpPerHourSuffix()))
                if (showProfile.get()) add(ProfitHudRow("profile", "Profile", profile?.profileName ?: "N/A"))
                if (showLastRun.get()) add(ProfitHudRow("lastRun", "Last Run", last?.normalizedXpDelta?.formatCompact() ?: "N/A"))
                if (showObservedCount.get()) add(ProfitHudRow("observedCount", "Runs", observedRunCountForFloor().toString()))
            }
        )
    }

    private fun buildProfitHudBottomRows(): List<ProfitHudRow> {
        val stats = chestProfitStats()
        return orderProfitRows(
            buildList {
                if (showChestProfit.get()) {
                    add(ProfitHudRow("profit", "Profit", stats.profit.formatCompactCoins()))
                    add(ProfitHudRow("avgChest", "Avg Chest", stats.average.formatCompactCoins()))
                }
                if (showChestCount.get()) add(ProfitHudRow("chestsOpened", "Chests", stats.chests.toString()))
                add(ProfitHudRow("kismets", "Kismets", state.totalKismetsUsed.toString()))
                add(ProfitHudRow("croesus", "Croesus", croesusUnopenedCountText()))
                if (showLastChest.get()) {
                    add(ProfitHudRow("lastChest", "Last Chest", state.lastChestName.ifBlank { "N/A" }, state.lastChestProfit.formatCompactCoins()))
                }
            }
        )
    }

    private fun buildItemTrackerTopRows(): List<ProfitHudRow> {
        val stats = chestProfitStats()
        return buildList {
            if (showChestCount.get()) add(ProfitHudRow("chestsOpened", "Chests", stats.chests.toString()))
            if (showChestProfit.get()) {
                add(ProfitHudRow("profit", "Profit", stats.profit.formatCompactCoins()))
                add(ProfitHudRow("avgChest", "Avg Chest", stats.average.formatCompactCoins()))
            }
            add(ProfitHudRow("kismets", "Kismets", state.totalKismetsUsed.toString()))
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
        val next = state.croesusUnclaimedCount.takeIf { it >= 0 }?.plus(1) ?: 1
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

    private fun buildItemTrackerRows(): List<ProfitHudRow> {
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
            return state.trackedItemDrops.filter { it.timestamp > 0L && it.timestamp >= cutoff }
        }
        if (chestProfitMode.getCurrent() == "Total") return state.trackedItemDrops
        if (sessionStartedAt <= 0L) return emptyList()
        return state.trackedItemDrops.filter { it.timestamp > 0L && it.timestamp >= sessionStartedAt }
    }

    private fun orderProfitRows(rows: List<ProfitHudRow>): List<ProfitHudRow> {
        val byId = rows.associateBy { it.id }
        return normalizedHudLineOrder().mapNotNull { byId[it] }
    }

    private fun drawProfitRows(
        graphics: GuiGraphics,
        rows: List<ProfitHudRow>,
        labelX: Int,
        separatorX: Int,
        valueX: Int,
        startY: Int,
        rowHeight: Int,
        fontHeight: Int,
    ): Int {
        var yOffset = startY
        for (row in rows) {
            val textY = yOffset + (rowHeight - fontHeight) / 2
            graphics.drawString(mc.font, Component.literal(row.label), labelX, textY, HUD_SKETCH_WHITE, true)
            graphics.drawString(mc.font, Component.literal("|"), separatorX, textY, HUD_SKETCH_WHITE, true)
            graphics.drawString(mc.font, Component.literal(row.value), valueX, textY, HUD_SKETCH_WHITE, true)
            if (row.suffix.isNotBlank()) {
                graphics.drawString(mc.font, Component.literal(row.suffix), valueX + mc.font.width(row.value) + 10, textY, HUD_SKETCH_WHITE, true)
            }
            yOffset += rowHeight
        }
        return yOffset
    }

    private fun hudRow(line: HudLine): HudRow {
        val plain = line.text.colorize().cleanMc()
        val splitAt = plain.indexOf(':')
        val label = if (splitAt >= 0) plain.substring(0, splitAt + 1) else plain
        var value = if (splitAt >= 0) plain.substring(splitAt + 1).trim() else ""
        var suffix = ""
        val suffixStart = value.lastIndexOf(" (")
        if (suffixStart > 0 && value.endsWith(")")) {
            suffix = value.substring(suffixStart).trim()
            value = value.substring(0, suffixStart).trim()
        }
        return HudRow(line.id, label, value.ifBlank { "..." }, hudIcon(line.id), line.id in ACCENT_VALUE_HUD_LINE_IDS, suffix)
    }

    private fun hudPanelWidth(rows: List<HudRow>): Int = hudContentWidth(rows) + 10

    private fun hudContentWidth(rows: List<HudRow>): Int {
        val labelX = 32
        val labelWidth = rows.maxOfOrNull { mc.font.width(it.label) } ?: 88
        val valueSepX = max(96, labelX + labelWidth + 6)
        val valueX = valueSepX + 7
        val valueWidth = rows.maxOfOrNull {
            mc.font.width(it.value) + if (it.mutedSuffix.isBlank()) 0 else 5 + mc.font.width(it.mutedSuffix)
        } ?: 72
        return max(valueX + valueWidth + 7, 166)
    }

    private fun hudIcon(id: String): ItemStack = ItemStack(
        when (id) {
            "currentLevel" -> Items.SKELETON_SKULL
            "target" -> Items.COMPASS
            "levelProgress" -> Items.EXPERIENCE_BOTTLE
            "runsLeft" -> Items.FEATHER
            "currentXp" -> Items.NETHER_STAR
            "remaining" -> Items.REDSTONE
            "floor" -> Items.MAP
            "xpPerRun" -> Items.WRITABLE_BOOK
            "profile" -> Items.PLAYER_HEAD
            "lastRun" -> Items.CLOCK
            "observedCount" -> Items.ENDER_EYE
            "profit" -> Items.GOLD_INGOT
            "chestsOpened" -> Items.CHEST
            "lastChest" -> Items.ENDER_CHEST
            "avgChest" -> Items.EMERALD
            else -> Items.PAPER
        }
    )

    private fun drawProfitPanel(graphics: GuiGraphics, width: Int, height: Int, dividerY: Int) {
        drawRoundedFill(graphics, 0, 0, width, height, HUD_PROFIT_PANEL)
        drawRoundedBorder(graphics, 0, 0, width, height, HUD_SKETCH_WHITE)
        graphics.fill(1, dividerY, width - 1, dividerY + 1, HUD_SKETCH_WHITE)
    }

    private fun drawRoundedFill(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int) {
        graphics.fill(x + 4, y, x + width - 4, y + 1, color)
        graphics.fill(x + 2, y + 1, x + width - 2, y + 2, color)
        graphics.fill(x + 1, y + 2, x + width - 1, y + 4, color)
        graphics.fill(x, y + 4, x + width, y + height - 4, color)
        graphics.fill(x + 1, y + height - 4, x + width - 1, y + height - 2, color)
        graphics.fill(x + 2, y + height - 2, x + width - 2, y + height - 1, color)
        graphics.fill(x + 4, y + height - 1, x + width - 4, y + height, color)
    }

    private fun drawRoundedBorder(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int) {
        graphics.fill(x + 4, y, x + width - 4, y + 1, color)
        graphics.fill(x + 2, y + 1, x + 4, y + 2, color)
        graphics.fill(x + width - 4, y + 1, x + width - 2, y + 2, color)
        graphics.fill(x + 1, y + 2, x + 2, y + 4, color)
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + 4, color)
        graphics.fill(x, y + 4, x + 1, y + height - 4, color)
        graphics.fill(x + width - 1, y + 4, x + width, y + height - 4, color)
        graphics.fill(x + 1, y + height - 4, x + 2, y + height - 2, color)
        graphics.fill(x + width - 2, y + height - 4, x + width - 1, y + height - 2, color)
        graphics.fill(x + 2, y + height - 2, x + 4, y + height - 1, color)
        graphics.fill(x + width - 4, y + height - 2, x + width - 2, y + height - 1, color)
        graphics.fill(x + 4, y + height - 1, x + width - 4, y + height, color)
    }

    private fun drawFramedPanel(graphics: GuiGraphics, width: Int, height: Int) {
        graphics.fill(2, 3, width + 2, height + 3, 0x66000000)
        graphics.fill(0, 0, width, height, HUD_OUTER_DARK)
        graphics.fill(3, 3, width - 3, height - 3, HUD_CYAN_DIM)
        graphics.fill(5, 5, width - 5, height - 5, HUD_INNER_DARK)
        graphics.fill(9, 9, width - 9, height - 9, HUD_PANEL)

        graphics.fill(0, 0, width, 2, HUD_BLACK)
        graphics.fill(0, height - 2, width, height, HUD_BLACK)
        graphics.fill(0, 0, 2, height, HUD_BLACK)
        graphics.fill(width - 2, 0, width, height, HUD_BLACK)

        val corner = 8
        graphics.fill(2, 2, corner, 5, HUD_CYAN)
        graphics.fill(2, 2, 5, corner, HUD_CYAN)
        graphics.fill(width - corner, 2, width - 2, 5, HUD_CYAN)
        graphics.fill(width - 5, 2, width - 2, corner, HUD_CYAN)
        graphics.fill(2, height - 5, corner, height - 2, HUD_CYAN)
        graphics.fill(2, height - corner, 5, height - 2, HUD_CYAN)
        graphics.fill(width - corner, height - 5, width - 2, height - 2, HUD_CYAN)
        graphics.fill(width - 5, height - corner, width - 2, height - 2, HUD_CYAN)
    }

    private fun drawDashedVertical(graphics: GuiGraphics, x: Int, top: Int, bottom: Int) {
        var y = top
        while (y < bottom) {
            graphics.fill(x, y, x + 1, (y + 5).coerceAtMost(bottom), HUD_CYAN)
            y += 8
        }
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
            .sortedByDescending { it.value }
            .take(15)
            .map { score ->
                val name = score.ownerName().string
                val team = scoreboard.getPlayersTeam(name)
                PlayerTeam.formatNameForTeam(team, Component.literal(name)).string
            }

        return (listOf(objective.displayName.string) + lines).joinToString("\n")
    }

    private fun send(message: String) {
        mc.player?.displayClientMessage(Component.literal((PREFIX + message).colorize()), false)
    }

    private fun sendReplacingSummary(lines: List<String>) {
        val chat = mc.gui.chat
        summarySignatures.forEach { chat.deleteMessage(it) }
        lines.take(summarySignatures.size).forEachIndexed { index, line ->
            chat.addMessage(Component.literal((PREFIX + line).colorize()), summarySignatures[index], null)
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

    private fun loadState() {
        state = runCatching {
            if (!stateFile.exists()) return
            stateFile.reader().use { gson.fromJson(it, RunState::class.java) }
        }.getOrNull() ?: RunState()
        if (state.totalChestsOpened == 0 && state.chestProfits.isNotEmpty()) {
            state.totalChestsOpened = state.chestProfits.size
            state.totalChestProfit = state.chestProfits.sumOf { it.profit }
        }
        if (state.hudViewMode != HUD_MODE_ITEMS) {
            state.hudViewMode = HUD_MODE_PROFIT
        }
        if (state.totalKismetsUsed == 0 && state.kismetUses.isNotEmpty()) {
            state.totalKismetsUsed = state.kismetUses.size
        }
        state.hudLineOrder = normalizedHudLineOrder().toMutableList()
        saveState()
    }

    private fun saveState() {
        stateFile.parentFile.mkdirs()
        val tempFile = File(stateFile.parentFile, "${stateFile.name}.tmp")
        tempFile.writer().use { gson.toJson(state, it) }
        runCatching {
            Files.move(tempFile.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tempFile.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sessionReady(): Boolean = mc.user.name.isNotBlank()

    private fun xpRemaining(currentXp: Long, targetLevel: Int): Long = (targetXp(targetLevel) - currentXp).coerceAtLeast(0)

    private fun targetXp(level: Int): Long {
        val normalizedLevel = level.coerceAtLeast(1)
        if (normalizedLevel <= CATACOMBS_LINEAR_LEVEL_START) {
            return cumulativeCatacombsXp[normalizedLevel - 1]
        }

        return CATACOMBS_LEVEL_50_XP + (normalizedLevel - CATACOMBS_LINEAR_LEVEL_START) * CATACOMBS_POST_50_XP_PER_LEVEL
    }

    private fun targetLevelValue(): Int = targetLevel.get().toIntOrNull()?.coerceAtLeast(1) ?: 50

    private fun currentCataLevel(xp: Long): Int {
        if (xp >= CATACOMBS_LEVEL_50_XP) {
            val post50Levels = ((xp - CATACOMBS_LEVEL_50_XP) / CATACOMBS_POST_50_XP_PER_LEVEL)
                .coerceAtMost((Int.MAX_VALUE - CATACOMBS_LINEAR_LEVEL_START).toLong())
                .toInt()
            return CATACOMBS_LINEAR_LEVEL_START + post50Levels
        }

        return cumulativeCatacombsXp.indexOfLast { xp >= it }.let { it + 1 }
    }

    private fun levelProgressPercent(xp: Long): String {
        val current = currentCataLevel(xp)
        val previousXp = if (current <= 0) 0L else targetXp(current)
        val nextXp = targetXp(current + 1)
        val progress = ((xp - previousXp).toDouble() / (nextXp - previousXp).toDouble()).coerceIn(0.0, 1.0)
        return "%.1f".format(Locale.US, progress * 100.0)
    }

    private fun floorValue(): String = floorLabel.get().ifBlank { "M7" }.uppercase(Locale.ROOT)

    private fun shouldTrackM7Drops(floor: String = floorValue()): Boolean =
        floor.equals("M7", true) ||
            floor.equals("MM7", true) ||
            floor.equals("MASTER MODE 7", true) ||
            floor.equals("MASTER CATACOMBS - FLOOR VII", true)

    private fun hardcodedXpPerRunValue(): Long = hardcodedXpPerRun.get().toLongOrNull()?.coerceAtLeast(1L) ?: 450_000L

    private fun dailyThresholdValue(): Long = dailyThreshold.get().toLongOrNull()?.coerceAtLeast(1L) ?: 600_000L

    private fun dailyMultiplierValue(): Double = dailyMultiplier.get().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.4

    private fun Long.format(): String = "%,d".format(this)

    private fun Iterable<Int>.averageOrZero(): Double = if (none()) 0.0 else average()

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return "%02dm %02ds".format(minutes, remainder)
    }

    private fun ensureSessionStarted(startedAt: Long, reason: String) {
        if (sessionStartedAt > 0L) return
        sessionStartedAt = startedAt.coerceAtMost(System.currentTimeMillis())
        log("Dungeon profit session started reason=$reason startedAt=$sessionStartedAt")
    }

    private fun sessionDurationText(): String =
        if (sessionStartedAt > 0L) {
            formatSessionDuration(((System.currentTimeMillis() - sessionStartedAt) / 1000L).coerceAtLeast(0L))
        } else {
            "Not started"
        }

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

    private fun xpPerHourSuffix(): String {
        val lastRun = state.runs.lastOrNull { it.normalizedCataXp > 0 && it.runTimeSeconds > 0 } ?: return ""
        val perHour = (lastRun.normalizedCataXp.toDouble() * 3600.0 / lastRun.runTimeSeconds.toDouble()).roundToLong()
        return "(${perHour.formatCompact()}/h)"
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

    data class RunState(
        var samples: MutableList<RunSample> = mutableListOf(),
        var lastCatacombsXp: Long = 0,
        var lastPlayerUuid: String = "",
        var runs: MutableList<DungeonRunRecord> = mutableListOf(),
        var chestProfits: MutableList<ChestProfitSample> = mutableListOf(),
        var lastChestName: String = "",
        var lastChestProfit: Long = 0,
        var totalChestProfit: Long = 0,
        var totalChestsOpened: Int = 0,
        var chestProfitWindowMillis: Long = 0,
        var lastLogImportAt: Long = 0,
        var hudViewMode: String = HUD_MODE_PROFIT,
        var trackedItemDrops: MutableList<TrackedItemDropSample> = mutableListOf(),
        var croesusUnclaimedCount: Int = -1,
        var totalKismetsUsed: Int = 0,
        var kismetUses: MutableList<KismetUseSample> = mutableListOf(),
        var hudLineOrder: MutableList<String>? = DEFAULT_HUD_LINE_ORDER.toMutableList(),
    )

    data class RunSample(
        var timestamp: Long = 0,
        var floorLabel: String = "M7",
        var rawXpDelta: Long = 0,
        var normalizedXpDelta: Long = 0,
    )

    data class DungeonRunRecord(
        var timestamp: Long = 0,
        var floorLabel: String = "M7",
        var runTimeSeconds: Int = 0,
        var score: Int = 0,
        var grade: String = "",
        var rawCataXp: Long = 0,
        var normalizedCataXp: Long = 0,
    )

    data class ChestProfitSample(
        var timestamp: Long = 0,
        var chestName: String = "",
        var profit: Long = 0,
        var profileName: String = "",
        var floorLabel: String = "M7",
    )

    data class TrackedItemDropSample(
        var timestamp: Long = 0,
        var itemKey: String = "",
        var displayName: String = "",
        var chestName: String = "",
        var floorLabel: String = "M7",
        var profileName: String = "",
    )

    data class KismetUseSample(
        var timestamp: Long = 0,
        var chestName: String = "",
        var profileName: String = "",
        var floorLabel: String = "M7",
    )

    data class ChestProfitCandidate(
        val chestName: String,
        val profit: Long,
        val cost: Int,
        val itemCount: Int,
        val scannedSlots: Int,
        val trackedDrops: List<TrackedDrop> = emptyList(),
    ) {
        fun summary(): String = "chest=$chestName profit=$profit cost=$cost items=$itemCount scannedSlots=$scannedSlots tracked=${trackedDrops.joinToString { it.key }}"
    }

    data class ChestProfitItem(
        val itemId: String,
        val unitValue: Int,
        val amount: Int,
        val essence: Boolean,
        val trackedDrop: TrackedDrop? = null,
    ) {
        val totalValue: Int get() = unitValue * amount
    }

    data class ProfileData(
        val playerName: String,
        val playerUuid: String,
        val profileName: String,
        val catacombsExperience: Long,
    )

    data class ProfileRequest(
        val playerName: String,
        val playerUuid: String,
        val apiKey: String,
    )

    data class ChestProfitStats(
        val label: String,
        val profit: Long,
        val chests: Int,
        val average: Long,
    )

    data class RunSummary(
        val label: String,
        val runs: Int,
        val xp: Long,
        val profit: Long,
        val chests: Int,
        val averageRunTimeSeconds: Int,
        val profitPerRun: Long,
        val profitPerChest: Long,
        val profitPerHour: Long,
        val xpPerHour: Long,
    )

    data class PendingCompletionState(
        val timestamp: Long,
        val floor: String,
        val runTimeSeconds: Int,
        val score: Int,
        val grade: String,
    )

    data class ImportedDungeonRun(
        val timestamp: Long,
        val floorLabel: String,
        val runTimeSeconds: Int,
        val score: Int,
        val grade: String,
        val rawCataXp: Long,
    )

    data class ImportedParseResult(
        val pending: PendingCompletionState,
        val lastDedupeKey: String,
        val lastDedupeAt: Long,
        val run: ImportedDungeonRun?,
    )

    private val cumulativeCatacombsXp = listOf(
        50L, 125L, 235L, 395L, 625L, 955L, 1425L, 2095L, 3045L, 4385L,
        6275L, 8940L, 12700L, 17960L, 25340L, 35640L, 50040L, 70040L, 97640L, 135640L,
        188140L, 259640L, 356640L, 488640L, 668640L, 911640L, 1239640L, 1683640L, 2284640L, 3084640L,
        4149640L, 5559640L, 7459640L, 9959640L, 13259640L, 17559640L, 23159640L, 30359640L, 39559640L, 51559640L,
        66559640L, 85559640L, 109559640L, 139559640L, 177559640L, 225559640L, 285559640L, 360559640L, 453559640L, 569809640L,
    )
    private val CATACOMBS_LINEAR_LEVEL_START = 50
    private val CATACOMBS_POST_50_XP_PER_LEVEL = 200_000_000L
    private val CATACOMBS_LEVEL_50_XP = cumulativeCatacombsXp[CATACOMBS_LINEAR_LEVEL_START - 1]

    private val chestNames = setOf("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")
    private val runChestRegex = "^(?:Master )?Catacombs - Floor [IV]+$".toRegex()
    private val costRegex = "^(\\d[\\d,]+) Coins$".toRegex()
    private val enchantedBookRegex = "^Enchanted Book \\(([\\w ]+) ([IV]+)\\)$".toRegex()
    private val essenceRegex = "^(Wither|Undead) Essence x(\\d+)$".toRegex()
    private val dungeonCompletionCataXpRegex = "\\+([\\d,]+)\\s+Cata EXP".toRegex()
    private val dungeonCompletionFloorRegex = "(Master Mode|The Catacombs|Catacombs)\\s*-\\s*([MF]?\\d+)".toRegex(RegexOption.IGNORE_CASE)
    private val dungeonCompletionTimeRegex = "\\bin\\s+(\\d{1,2})m\\s*(\\d{1,2})s\\b".toRegex(RegexOption.IGNORE_CASE)
    private val dungeonCompletionScoreRegex = "Score:\\s*(\\d+)\\s*\\(([A-Z+]+)\\)".toRegex(RegexOption.IGNORE_CASE)
    private val specialIds = mapOf(
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
    private val hardcodedItemPrices = mapOf(
        "SHARD_POWER_DRAGON" to 450_000,
        "SHARD_APEX_DRAGON" to 500_000,
    )
}
