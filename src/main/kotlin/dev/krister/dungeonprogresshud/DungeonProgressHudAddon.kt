package dev.krister.dungeonprogresshud

import com.github.synnerz.devonian.Devonian
import com.github.synnerz.devonian.api.ItemUtils
import com.github.synnerz.devonian.api.Location
import com.github.synnerz.devonian.api.SkyblockPrices
import com.github.synnerz.devonian.api.events.GuiKeyDownEvent
import com.github.synnerz.devonian.api.events.ChatEvent
import com.github.synnerz.devonian.api.events.TickEvent
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
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.math.ceil
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
                    .then(literal("importlogs").executes {
                        withFeature { it.importRecentLogs(true) }
                        1
                    })
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
        private const val HUD_LINE_GAP = 2
        private const val HUD_ORDER_HINT = "&eHold Shift to drag HUD lines"
        private const val STATUS_LINE_ID = "status"
        private const val JOIN_SKYBLOCK_DETECTION_TIMEOUT_MILLIS = 300_000L
        private val JOIN_REFRESH_RETRY_DELAYS = longArrayOf(10_000L, 30_000L, 90_000L, 180_000L)
        private val DEFAULT_HUD_LINE_ORDER = listOf(
            "currentLevel",
            "levelProgress",
            "target",
            "runsLeft",
            "currentXp",
            "remaining",
            "floor",
            "xpPerRun",
            "profile",
            "lastRun",
            "observedCount",
            "profit",
            "chestsOpened",
            "lastChest",
            "avgChest",
        )
    }

    private val PREFIX = "&6[&bDPH&6]&r "
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configDir = FabricLoader.getInstance().configDir.resolve("DungeonProgressHud").toFile()
    private val stateFile = File(configDir, "runs.json")
    private val hudStyleFile = File(configDir, "hud-style.json")
    private var hudStyle = HudStyle()
    private var hudStyleLoadedAt = 0L
    private var hudStyleModifiedAt = 0L
    private val summarySignatures = (0 until 3).map { signature("dph-summary-$it") }
    private val logsDir = FabricLoader.getInstance().gameDir.resolve("logs").toFile()
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val displayDivider = addDivider("10", "DISPLAY")
    private val renderHud = addSwitch("11_renderHud", true, "Draw the HUD during normal gameplay.", "Render HUD", emptySet(), false, configTab)
    private val showEverywhere = addSwitch("12_showEverywhere", true, "Render everywhere instead of only Dungeon Hub/Catacombs.", "Show Everywhere", emptySet(), false, configTab)
    private val targetLevel = addTextInput("13_targetLevel", "51", "Target Catacombs level.", "Target Level", emptySet(), configTab)
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
    private val showObservedCount = addSwitch("1f_showObservedCount", true, "Show observed sample count.", "Observed Count", emptySet(), false, configTab)

    private val xpDivider = addDivider("20", "XP")
    private val xpMode = addSelection("21_xpMode", 0, listOf("Observed Average", "Hardcoded"), "XP/run source.", "XP/Run Mode", emptySet(), configTab)
    private val hardcodedXpPerRun = addTextInput("22_hardcodedXpPerRun", "450000", "Fallback XP per run.", "Hardcoded XP/Run", emptySet(), configTab)
    private val scaleDailyRunXp = addSwitch("23_scaleDailyRunXp", true, "Divide high raw run XP by the daily multiplier.", "Scale Daily Run XP", emptySet(), false, configTab)
    private val dailyThreshold = addTextInput("24_dailyThreshold", "600000", "Only scale raw run XP above this value.", "Daily XP Threshold", emptySet(), configTab)
    private val dailyMultiplier = addTextInput("25_dailyMultiplier", "1.4", "Raw run XP is divided by this when daily scaling applies.", "Daily XP Multiplier", emptySet(), configTab)

    private val profitDivider = addDivider("30", "PROFIT")
    private val trackChestProfit = addSwitch("31_trackChestProfit", true, "Track profit when claiming dungeon reward chests.", "Track Chest Profit", emptySet(), false, configTab)
    private val showChestProfit = addSwitch("32_showChestProfit", true, "Show tracked dungeon chest profit.", "Chest Profit", emptySet(), false, configTab)
    private val chestProfitMode = addSelection("33_chestProfitMode", 0, listOf("Session", "Total"), "Choose whether chest profit lines use this session or all tracked chests.", "Chest Profit Mode", emptySet(), configTab)
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
    private var lastRecordedChestAt = 0L
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
    private val sessionStartedAt = System.currentTimeMillis()
    private var draggedHudLineId: String? = null
    private var hoveredHudLineId: String? = null

    init {
        Config.onAfterLoad {
            migrateLegacyApiKey()
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
            if (normalized != apiKey.get()) apiKey.set(normalized)
            return
        }
        val legacy = readConfiguredApiKeyValue(LEGACY_API_KEY_CONFIG)
        if (legacy.isNotBlank()) apiKey.set(legacy.trim())
    }

    override fun getEditText(): List<String> = listOf(
        "&bCata Level: &fC49",
        "&bTarget: &fC50",
        "&bRuns Left: &a259",
        "&bCata XP: &f453,559,640",
        "&bRemaining: &f116,250,000",
        "&bFloor: &fM7",
        "&bXP/Run: &f450,000 &7(fallback)",
    )

    override fun initialize() {
        startRuntime("devonian initialize")

        on<TickEvent> {
            clientTick()
        }

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
        importRecentLogs(false)
    }

    private fun clientTick() {
        val refreshedForSkyBlockJoin = maybeRefreshAfterSkyBlockJoin()
        val refreshedForJoin = maybeRefreshAfterServerJoin()
        if (!startupRefreshAttempted && sessionReady() && !refreshedForJoin) {
            startupRefreshAttempted = true
            lastAutoRefreshAttempt = System.currentTimeMillis()
            log("Startup refresh triggered for user=${mc.user.name} uuid=${mc.user.profileId}")
            refresh(false)
        }

        scanCurrentChestScreen()
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
        startRuntime("gui render")

        val enabled = isEnabled()
        if (mc.screen is AbstractContainerScreen<*>) {
            return
        }
        val visible = shouldRenderCached()
        if (!renderHud.get() || !visible) {
            logRenderState("Render blocked renderHud=${renderHud.get()} enabled=$enabled shouldRender=$visible showEverywhere=${showEverywhere.get()}")
            return
        }

        val lines = buildLines()
        if (lines.isEmpty()) {
            logRenderState("Render blocked: no lines")
            return
        }

        drawDirect(graphics, lines)
        if (!renderedOnce) {
            renderedOnce = true
            log("HUD rendered lines=${lines.size} x=$x y=$y scale=$scale status=$status")
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
        if (configuredApiKey().isBlank()) {
            status = "API key missing"
            log("Refresh skipped: missing API key")
            if (notify) send("Refresh skipped: API key missing. Use /dph apikey <key> if the GUI field did not save.")
            return
        }

        refreshing = true
        status = "Refreshing..."
        log("Refresh started force=$force recordObservedSample=$recordObservedSample user=${mc.user.name} uuid=${mc.user.profileId}")
        if (notify) send("Refreshing API data...")

        thread(name = "DungeonProgressHud-API", isDaemon = true) {
            val result = runCatching { fetchProfile() }
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
            if (current != apiKey.get()) apiKey.set(current)
            return current
        }

        for (configName in API_KEY_CONFIG_NAMES) {
            val value = readConfiguredApiKeyValue(configName)
            if (value.isNotBlank()) {
                apiKey.set(value)
                return value
            }
        }

        return ""
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
        runCatching { Config.save() }
        status = "API key saved"
        send("API key saved. Run /dph refresh or press Refresh Now.")
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
        val normalized = if (mode.equals("Total", true)) "Total" else "Session"
        state.chestProfitWindowMillis = 0L
        saveState()
        chestProfitMode.set(if (normalized == "Total") 1 else 0)
        log("Chest profit mode set to $normalized")
        send("Chest profit view: ${normalized.lowercase(Locale.ROOT)}.")
    }

    fun toggleProfitMode() {
        if (state.chestProfitWindowMillis > 0L) {
            setProfitMode(chestProfitMode.getCurrent())
            return
        }
        setProfitMode(if (chestProfitMode.getCurrent() == "Total") "Session" else "Total")
    }

    fun setProfitWindow(input: String) {
        val parsedDays = parseProfitWindowDays(input)
        if (parsedDays == null) {
            send("Use /dph profit session, total, 7d, 14d, or 2w.")
            return
        }

        state.chestProfitWindowMillis = parsedDays * DAY_MILLIS
        saveState()
        log("Chest profit window set to ${formatProfitWindow(state.chestProfitWindowMillis)}")
        send("Chest profit view: ${formatProfitWindow(state.chestProfitWindowMillis)}.")
    }

    fun sendProfitStatus() {
        val stats = chestProfitStats()
        send("Chest profit view: ${stats.label}, ${stats.profit.formatCoins()} across ${stats.chests} chests.")
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
        if (slotId != 31) return

        val devonianCandidates = devonianChestProfitCandidates()
        val candidate = selectedCroesusCandidate?.takeIf { it.chestName == title }
            ?: devonianCandidates[title]
            ?: lastCroesusCandidates[title]
            ?: pendingChestProfit?.takeIf { it.chestName == title }
            ?: runCatching { parseChestProfit(screen) }
                .onFailure { log("Chest claim parse failed: ${it.stackTraceToString()}") }
                .getOrNull()
        if (candidate == null) {
            log("Chest claim click ignored: no profit candidate for title=$title")
            return
        }

        recordChestProfit(candidate, "claim-click")
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
        val candidate = runCatching {
            when {
                chestNames.contains(title) -> selectedCroesusCandidate?.takeIf { it.chestName == title }
                    ?: devonianCandidates[title]
                    ?: lastCroesusCandidates[title]
                    ?: pendingChestProfit?.takeIf { it.chestName == title }
                    ?: parseChestProfit(screen, verbose = true)
                title.matches(runChestRegex) -> selectedCroesusCandidate
                    ?: devonianCandidates.values.maxByOrNull { it.profit }
                    ?: parseBestCroesusChest(screen)
                else -> null
            }
        }.onFailure {
            log("Fake open parse failed: ${it.stackTraceToString()}")
        }.getOrNull()

        if (candidate == null) {
            log("Fake open ignored: unsupported title=$title")
            return false
        }

        recordChestProfit(candidate, "fake-open")
        return true
    }

    private fun cacheClickedCroesusChest(screen: AbstractContainerScreen<*>, slotId: Int) {
        val clickedStack = screen.menu.slots.getOrNull(slotId)?.item ?: screen.menu.items.getOrNull(slotId) ?: return
        val chestName = clickedStack.customName?.string ?: clickedStack.hoverName.string
        if (!chestNames.contains(chestName)) return

        val devonianCandidates = devonianChestProfitCandidates()
        val parsedCandidates = if (devonianCandidates.isNotEmpty()) devonianCandidates else runCatching {
            val items = screen.menu.items.take(chestContainerSlotCount(screen.menu.items.size))
            items.mapIndexedNotNull { slot, stack ->
                val name = stack.customName?.string ?: return@mapIndexedNotNull null
                if (!chestNames.contains(name)) return@mapIndexedNotNull null
                parseCroesusChestItem(name, stack, slot)
            }.associateBy { it.chestName }
        }.getOrDefault(emptyMap())

        if (parsedCandidates.isNotEmpty()) lastCroesusCandidates = parsedCandidates
        val candidate = parsedCandidates[chestName] ?: return
        selectedCroesusCandidate = candidate
        log("Selected Croesus chest from click title=${screen.title.string} slot=$slotId ${candidate.summary()}")
    }

    private data class HudLine(
        val id: String,
        val text: String,
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
            if (showObservedCount.get()) add(HudLine("observedCount", "&bObserved Runs: &f${samples.size}"))
            if (showChestProfit.get()) {
                val stats = chestProfitStats()
                add(HudLine("profit", "&bProfit: &a${stats.profit.formatCoins()} &7(${stats.label})"))
                if (showChestCount.get()) add(HudLine("chestsOpened", "&bChests Opened: &f${stats.chests}"))
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
        val dragging = draggedHudLineId != null
        val lines = editableHudLines()
        if (lines.isEmpty()) return

        val shiftDown = isShiftDown()
        val hoverId = if (shiftDown || dragging) hudLineAt(mouseX, mouseY, lines)?.id else null
        if (dragging) hoveredHudLineId = hoverId
        drawHudOrderOverlay(graphics, lines, hoverId)
    }

    fun onHudOrderMouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent, shiftDown: Boolean): Boolean {
        if (!shiftDown || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        val hit = hudLineAt(event.x(), event.y(), editableHudLines()) ?: return false
        draggedHudLineId = hit.id
        hoveredHudLineId = hit.id
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
        val lines = buildHudLines()
            .filter { it.id in DEFAULT_HUD_LINE_ORDER }
            .ifEmpty { buildEditPreviewHudLines() }
        return orderedHudLines(lines)
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
        if (showObservedCount.get()) add(HudLine("observedCount", "&bObserved Runs: &f12"))
        if (showChestProfit.get()) {
            add(HudLine("profit", "&bProfit: &a12.3M coins &7(session)"))
            if (showChestCount.get()) add(HudLine("chestsOpened", "&bChests Opened: &f8"))
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
        val lineHeight = mc.font.lineHeight + HUD_LINE_GAP
        val width = lines.maxOfOrNull { mc.font.width(it.text.colorize()) } ?: 90
        val left = drawX - 2.0 * renderScale
        val right = drawX + (width + 4.0) * renderScale

        return lines.mapIndexed { index, line ->
            val top = drawY + (index * lineHeight - 2.0) * renderScale
            val bottom = drawY + ((index + 1) * lineHeight).toDouble() * renderScale
            HudLineBounds(line.id, line.text, left, top, right, bottom)
        }
    }

    private fun drawHudOrderOverlay(graphics: GuiGraphics, lines: List<HudLine>, hoverId: String?) {
        val drawX = if (x.isFinite()) x.toFloat() else 10f
        val drawY = if (y.isFinite()) y.toFloat() else 10f
        val renderScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val hintY = (drawY - (mc.font.lineHeight + HUD_LINE_GAP + 2) * renderScale).coerceAtLeast(2f)
        graphics.drawString(mc.font, Component.literal(HUD_ORDER_HINT.colorize()), drawX.toInt(), hintY.toInt(), 0xFFFFFFFF.toInt(), true)

        graphics.pose().pushMatrix()
        graphics.pose().translate(drawX, drawY)
        graphics.pose().scale(renderScale, renderScale)

        val width = lines.maxOfOrNull { mc.font.width(it.text.colorize()) } ?: 90
        val hintWidth = mc.font.width(HUD_ORDER_HINT.colorize())
        val overlayWidth = width.coerceAtLeast(hintWidth)
        val lineHeight = mc.font.lineHeight + HUD_LINE_GAP

        val draggedId = draggedHudLineId
        val targetId = if (draggedId != null) hoveredHudLineId ?: hoverId else hoverId
        var yOffset = 0
        for (line in lines) {
            val highlight = when {
                line.id == draggedId -> 0x663399FF
                draggedId != null && line.id == targetId -> 0x6644CC66
                draggedId == null && line.id == hoverId -> 0x33FFFFFF
                else -> null
            }
            if (highlight != null) {
                graphics.fill(-1, yOffset - 1, overlayWidth + 3, yOffset + mc.font.lineHeight + 1, highlight)
            }
            graphics.drawString(mc.font, Component.literal(line.text.colorize()), 0, yOffset, 0xFFFFFFFF.toInt(), true)
            yOffset += lineHeight
        }

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
        val containerSlotCount = chestContainerSlotCount(items.size)

        for (stack in items.take(containerSlotCount)) {
            if (stack.isEmpty || stack.item == Items.GRAY_STAINED_GLASS_PANE) continue
            parseChestItem(stack)?.let {
                if (!it.essence || includeEssenceProfit.get()) {
                    itemValue += it.totalValue.toLong()
                }
                itemCount++
                if (verbose) {
                    log("Chest screen item parsed chest=$title name=${stack.hoverName.string.cleanMc()} id=${it.itemId} unit=${it.unitValue} amount=${it.amount} essence=${it.essence} total=${it.totalValue}")
                }
            }
        }

        return ChestProfitCandidate(title, itemValue - cost, cost, itemCount, containerSlotCount)
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
        val devonianCandidates = devonianChestProfitCandidates()
        if (devonianCandidates.isNotEmpty()) {
            lastCroesusCandidates = devonianCandidates
            val best = devonianCandidates.values.maxByOrNull { it.profit }
            log("Using Devonian Croesus candidates=${devonianCandidates.values.joinToString { "${it.chestName}:${it.profit}" }} best=${best?.summary()}")
            return best
        }

        val items = screen.menu.items.take(chestContainerSlotCount(screen.menu.items.size))
        val candidates = items.mapIndexedNotNull { slot, stack ->
            val name = stack.customName?.string ?: return@mapIndexedNotNull null
            if (!chestNames.contains(name)) return@mapIndexedNotNull null
            parseCroesusChestItem(name, stack, slot)
        }.sortedByDescending { it.profit }
        if (candidates.isNotEmpty()) lastCroesusCandidates = candidates.associateBy { it.chestName }

        val best = candidates.firstOrNull()
        log("Fake open parsed Croesus candidates=${candidates.joinToString { "${it.chestName}:${it.profit}" }} best=${best?.summary()}")
        return best
    }

    private fun devonianChestProfitCandidates(): Map<String, ChestProfitCandidate> {
        val candidates = linkedMapOf<String, ChestProfitCandidate>()
        // Lowest-level listener first, visible HUD features last. If the same chest exists in
        // multiple Devonian caches, the value displayed by Devonian's profit HUD should win.
        candidates.putAll(devonianCroesusListenerCandidates())
        candidates.putAll(devonianChestProfitFeatureCandidates())
        candidates.putAll(devonianCroesusProfitCandidates())
        if (candidates.isNotEmpty()) {
            log("Devonian chest profit candidates=${candidates.values.joinToString { "${it.chestName}:${it.profit}" }}")
        }
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
                log("Croesus item parsed chest=$chestName slot=$slot line=$line id=${it.itemId} unit=${it.unitValue} amount=${it.amount} essence=${it.essence} total=${it.totalValue}")
            }
        }

        if (itemCount == 0) return null
        return ChestProfitCandidate(chestName, itemValue - cost, cost, itemCount, scannedSlots = slot)
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
        if (price <= 0) {
            log("Croesus item price missing id=$id line=$line formatted=$formattedLine")
            return null
        }
        return ChestProfitItem(id, price, 1, essence = false)
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
        if (price <= 0) return null
        return ChestProfitItem(id, price, 1, essence = false)
    }

    private fun normalizeItemId(id: String): String = specialIds[id] ?: id

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
                floorLabel = floorValue(),
            )
        )
        while (state.chestProfits.size > 250) state.chestProfits.removeAt(0)
        saveState()
        log("Recorded chest profit source=$source ${candidate.summary()} samples=${state.chestProfits.size} sessionProfit=$sessionChestProfit totalProfit=${state.totalChestProfit}")
    }

    private fun fetchProfile(): ProfileData {
        val user = mc.user
        val uuid = user.profileId.toString().replace("-", "")
        val encodedUuid = URLEncoder.encode(uuid, StandardCharsets.UTF_8)
        log("Fetching Hypixel profile user=${user.name} uuid=$uuid")
        val connection = URI("https://api.hypixel.net/v2/skyblock/profiles?uuid=$encodedUuid").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("API-Key", configuredApiKey())
        connection.setRequestProperty("User-Agent", "DungeonProgressHud/1.0.0")

        val code = connection.responseCode
        log("Hypixel response code=$code")
        if (code == 403) error("Invalid API key")
        if (code == 429) error("Rate limited")
        if (code !in 200..299) error("Hypixel HTTP $code")

        val root = JsonParser.parseReader(connection.inputStream.reader()).asJsonObject
        if (root.get("success")?.asBoolean != true) error(root.get("cause")?.asString ?: "Hypixel API failed")
        val profiles = root.getAsJsonArray("profiles") ?: error("No SkyBlock profiles")
        val selected = profiles.map { it.asJsonObject }.firstOrNull { it.get("selected")?.asBoolean == true }
            ?: error("No selected SkyBlock profile")
        val selectedProfileName = selected.get("cute_name")?.asString ?: "Unknown"
        log("Selected profile cute_name=$selectedProfileName")
        val member = selected.getAsJsonObject("members")?.getAsJsonObject(uuid) ?: error("Selected profile missing player")
        val dungeons = member.getAsJsonObject("dungeons") ?: error("Dungeon API unavailable")
        val catacombs = dungeons.getAsJsonObject("dungeon_types")?.getAsJsonObject("catacombs") ?: error("Catacombs data unavailable")

        return ProfileData(
            playerName = user.name,
            playerUuid = uuid,
            profileName = selectedProfileName,
            catacombsExperience = catacombs.get("experience")?.asLong ?: 0L,
        )
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
        val cutoff = System.currentTimeMillis() - 8L * DAY_MILLIS
        val files = logsDir.listFiles()
            ?.filter { it.isFile && it.lastModified() >= cutoff && (it.extension == "log" || it.name.endsWith(".log.gz")) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?: emptyList()
        if (!manual && files.none { it.lastModified() > state.lastLogImportAt }) return

        val existingRuns = state.runs.toList()
        val fallbackFloor = floorValue()
        thread(name = "DPH Log Import", isDaemon = true) {
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
        val match = Regex("^(\\d+)(d|day|days|w|week|weeks)?$", RegexOption.IGNORE_CASE).matchEntire(input.trim())
            ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        if (amount <= 0L) return null
        val unit = match.groupValues.getOrNull(2)?.lowercase(Locale.ROOT).orEmpty()
        val days = if (unit.startsWith("w")) amount * 7L else amount
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

    private fun drawDirect(graphics: GuiGraphics, lines: List<String>) {
        graphics.pose().pushMatrix()
        val drawX = if (x.isFinite()) x.toFloat() else 10f
        val drawY = if (y.isFinite()) y.toFloat() else 10f
        graphics.pose().translate(drawX, drawY)
        graphics.pose().scale(scale, scale)

        val profile = data
        if (profile != null) {
            drawStyledHud(graphics, profile)
            graphics.pose().popMatrix()
            return
        }

        val width = lines.maxOfOrNull { mc.font.width(it.colorize()) } ?: 90
        val height = lines.size * (mc.font.lineHeight + 2) + 2
        graphics.fill(-2, -2, width + 4, height, 0x80000000.toInt())

        var yOffset = 0
        for (line in lines) {
            graphics.drawString(mc.font, Component.literal(line.colorize()), 0, yOffset, 0xFFFFFFFF.toInt(), true)
            yOffset += mc.font.lineHeight + 2
        }

        graphics.pose().popMatrix()
    }

    private fun loadHudStyle(): HudStyle {
        val now = System.currentTimeMillis()
        if (now - hudStyleLoadedAt < 500L) return hudStyle
        hudStyleLoadedAt = now

        runCatching {
            configDir.mkdirs()
            if (!hudStyleFile.exists()) {
                hudStyleFile.writer().use { gson.toJson(hudStyle, it) }
                hudStyleModifiedAt = hudStyleFile.lastModified()
                return hudStyle
            }

            val modified = hudStyleFile.lastModified()
            if (modified == hudStyleModifiedAt) return hudStyle
            hudStyleModifiedAt = modified
            hudStyle = hudStyleFile.reader().use { gson.fromJson(it, HudStyle::class.java) } ?: HudStyle()
            log("HUD style reloaded titleScale=${hudStyle.titleScale} metricScale=${hudStyle.metricShortScale} lootScale=${hudStyle.lootShortScale}")
        }.onFailure {
            log("HUD style reload failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        return hudStyle
    }

    private fun drawStyledHud(graphics: GuiGraphics, profile: ProfileData) {
        val style = loadHudStyle()
        val panelWidth = 340
        val panelHeight = if (showChestProfit.get()) 404 else 308
        val cyan = 0xFF32E6F0.toInt()
        val cyanDim = 0xAA159AA5.toInt()
        val cyanDark = 0x5524D8E5
        val white = 0xFFFFFFFF.toInt()
        val gray = 0xFF9BA2AA.toInt()
        val green = 0xFF66F05A.toInt()
        val purple = 0xFFD07BFF.toInt()
        val gold = 0xFFFFD45A.toInt()
        val fill = style.argb(0x061014, 0x86)
        val cardFill = style.argb(0x061016, 0xB0)

        graphics.fill(0, 0, panelWidth, panelHeight, fill)
        graphics.fill(4, 4, panelWidth - 4, 46, 0x66050B10)
        graphics.fill(8, 44, panelWidth - 8, panelHeight - 8, 0x58030A0D)
        graphics.fill(4, panelHeight - 104, panelWidth - 4, panelHeight - 4, 0x64181004)
        drawPixelBorder(graphics, 0, 0, panelWidth, panelHeight, 0xDD1E2B36.toInt(), cyanDim)
        drawPixelBorder(graphics, 4, 4, panelWidth - 4, panelHeight - 4, 0x80313D48.toInt(), cyanDark)
        drawCornerStuds(graphics, panelWidth, panelHeight, cyan)
        drawEdgeBrackets(graphics, panelWidth, panelHeight, cyanDim)
        graphics.fill(12, 36, panelWidth - 12, 39, cyanDim)
        graphics.fill(54, 6, panelWidth - 54, 34, 0xA4101822.toInt())
        graphics.fill(68, 10, panelWidth - 68, 30, 0x7A050B10)
        drawPixelBorder(graphics, 54, 6, panelWidth - 54, 34, 0x88101822.toInt(), 0xDD283642.toInt())
        graphics.fill(66, 34, panelWidth - 66, 37, 0x8832E6F0.toInt())
        drawScaledCenteredText(graphics, "CATACOMBS", 2, style.titleY + 3, panelWidth, 0xAA061014.toInt(), style.titleScale)
        drawScaledCenteredText(graphics, "CATACOMBS", 0, style.titleY, panelWidth, cyan, style.titleScale)
        drawSkull(graphics, 24, 13, cyan)
        drawSkull(graphics, panelWidth - 38, 13, cyan)
        drawHeaderCrystal(graphics, 58, 20, cyan)
        drawHeaderCrystal(graphics, panelWidth - 58, 20, cyan)

        val currentLevel = currentCataLevel(profile.catacombsExperience)
        val targetLevel = targetLevelValue()
        drawStatCard(graphics, 14, 50, 146, 88, "+ CATA LEVEL +", "C$currentLevel", cyan, white)
        drawStatCard(graphics, 180, 50, 146, 88, "+ TARGET +", "C$targetLevel", cyan, white)
        drawGem(graphics, panelWidth / 2, 93, cyan)

        val progressText = levelProgressPercent(profile.catacombsExperience)
        val progress = (progressText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0) / 100.0
        drawPanelBox(graphics, 14, 152, 312, 52, cardFill, cyanDim)
        graphics.drawString(mc.font, Component.literal("NEXT LEVEL"), 70, 162, cyan, true)
        drawIconBox(graphics, 24, 163, cyan, "")
        drawPixelSpark(graphics, 38, 176, cyan)
        graphics.fill(70, 181, 266, 193, 0xD205090C.toInt())
        drawPixelBorder(graphics, 70, 181, 266, 193, 0xC205090C.toInt(), 0xFF1D2930.toInt())
        val filled = (194 * progress).roundToInt()
        if (filled > 0) {
            graphics.fill(72, 183, 72 + filled, 191, 0xDD26EAF2.toInt())
            graphics.fill(72, 183, 72 + filled, 185, 0xF0A6FFFF.toInt())
            graphics.fill(72, 189, 72 + filled, 191, 0xBB108B96.toInt())
        }
        for (tick in 1 until 10) {
            val tx = 72 + tick * 19
            graphics.fill(tx, 182, tx + 1, 192, 0x66000000)
        }
        drawScaledText(graphics, "${progressText}%", 278, 171, green, style.progressPercentScale)

        val xpPerRun = effectiveXpPerRun()
        val remaining = xpRemaining(profile.catacombsExperience, targetLevel)
        val runs = xpPerRun.takeIf { it > 0 }?.let { ceil(remaining.toDouble() / it.toDouble()).toLong() }
        val samples = samplesForFloor()
        val last = samples.lastOrNull()
        val midTop = 220
        val midHeight = 86
        drawPanelBox(graphics, 14, midTop, 150, midHeight, cardFill, cyanDim)
        drawPanelBox(graphics, 176, midTop, 150, midHeight, cardFill, cyanDim)
        drawSectionTitle(graphics, "RUNS", 14, midTop + 8, 150, cyan)
        drawSectionTitle(graphics, "XP & HISTORY", 176, midTop + 8, 150, cyan)
        drawMetricRow(graphics, 28, midTop + 28, "RUNS LEFT", runs?.compact() ?: "N/A", green, "R", showRunsLeft.get(), style.metricShortScale)
        drawDashedLine(graphics, 28, midTop + 55, 150, 0x7726DDE8)
        drawMetricRow(graphics, 28, midTop + 60, "OBSERVED RUNS", samples.size.toString(), purple, "O", showObservedCount.get(), style.metricShortScale)
        drawMetricRow(graphics, 190, midTop + 28, "CATA XP", profile.catacombsExperience.format(), white, "XP", showCurrentXp.get(), style.metricShortScale)
        drawDashedLine(graphics, 190, midTop + 55, 312, 0x7726DDE8)
        drawMetricRow(graphics, 190, midTop + 60, "LAST RUN", last?.normalizedXpDelta?.format() ?: "N/A", white, "L", showLastRun.get(), style.metricShortScale)

        if (showChestProfit.get()) {
            val stats = chestProfitStats()
            val profitTop = 320
            drawPanelBox(graphics, 14, profitTop, 312, 72, 0x72422E09, 0xCCB88725.toInt())
            drawBox(graphics, 18, profitTop + 4, 304, 20, 0x7750390A, 0xDDC99A2E.toInt())
            drawCenteredText(graphics, "LOOT & PROFIT", 18, profitTop + 10, 304, gold, true)
            drawLootGem(graphics, panelWidth / 2, profitTop + 28, gold)
            graphics.fill(114, profitTop + 28, 117, profitTop + 66, 0xCCB88725.toInt())
            graphics.fill(220, profitTop + 28, 223, profitTop + 66, 0xCCB88725.toInt())
            drawLootMetric(graphics, 20, profitTop + 36, "PROFIT", stats.profit.formatCoins(), green, "$", style.lootShortScale)
            graphics.drawString(mc.font, Component.literal("(${stats.label})"), 42, profitTop + 61, gray, true)
            drawLootMetric(graphics, 130, profitTop + 36, "CHESTS", stats.chests.toString(), white, "#", style.lootShortScale)
            drawLootMetric(graphics, 236, profitTop + 36, "AVG CHEST", stats.average.formatCoins(), green, "D", style.lootShortScale)
        }
    }

    private fun drawStatCard(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, label: String, value: String, labelColor: Int, valueColor: Int) {
        drawBox(graphics, x, y, width, height, 0x7A081016, 0xAA26313B.toInt())
        graphics.fill(x + 8, y + 8, x + width - 8, y + height - 8, 0x88050A0E.toInt())
        graphics.fill(x + 14, y + 14, x + width - 14, y + 16, 0x3326DDE8)
        graphics.fill(x + 14, y + height - 16, x + width - 14, y + height - 14, 0x44000000)
        drawCenteredText(graphics, label, x, y + 12, width, labelColor, true)
        drawScaledCenteredText(graphics, value, x, y + 42, width, valueColor, 4.0f)
    }

    private fun drawMetric(graphics: GuiGraphics, x: Int, y: Int, label: String, value: String, valueColor: Int, visible: Boolean) {
        if (!visible) return
        graphics.drawString(mc.font, Component.literal(label), x, y, 0xFF32E6F0.toInt(), true)
        val compact = if (value.length > 12) value.replace(",", " ") else value
        graphics.drawString(mc.font, Component.literal(compact), x, y + 11, valueColor, true)
    }

    private fun drawIconBox(graphics: GuiGraphics, x: Int, y: Int, color: Int, text: String) {
        drawBox(graphics, x, y, 28, 26, 0x66101D23, color)
        if (text.isNotBlank()) drawCenteredText(graphics, text, x, y + 9, 28, color, true)
    }

    private fun drawGem(graphics: GuiGraphics, centerX: Int, centerY: Int, color: Int) {
        graphics.fill(centerX - 2, centerY - 8, centerX + 3, centerY - 3, color)
        graphics.fill(centerX - 7, centerY - 3, centerX + 8, centerY + 3, 0xAA0FD6DE.toInt())
        graphics.fill(centerX - 2, centerY + 3, centerX + 3, centerY + 8, color)
    }

    private fun drawSectionTitle(graphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int) {
        graphics.fill(x + 10, y + 4, x + 38, y + 6, 0x8826DDE8.toInt())
        graphics.fill(x + width - 38, y + 4, x + width - 10, y + 6, 0x8826DDE8.toInt())
        drawCenteredText(graphics, text, x, y, width, color, true)
    }

    private fun drawMetricRow(graphics: GuiGraphics, x: Int, y: Int, label: String, value: String, valueColor: Int, icon: String, visible: Boolean, shortScale: Float) {
        if (!visible) return
        drawBox(graphics, x, y, 20, 20, 0x55101D23, 0x7726DDE8)
        drawCenteredText(graphics, icon, x, y + 6, 20, 0xFF32E6F0.toInt(), true)
        graphics.drawString(mc.font, Component.literal(label), x + 27, y, 0xFF32E6F0.toInt(), true)
        val body = if (value.length > 10) value.replace(",", " ") else value
        if (body.length <= 6) {
            drawScaledText(graphics, body, x + 27, y + 10, valueColor, shortScale)
        } else {
            graphics.drawString(mc.font, Component.literal(body), x + 27, y + 11, valueColor, true)
        }
    }

    private fun drawLootMetric(graphics: GuiGraphics, x: Int, y: Int, label: String, value: String, valueColor: Int, icon: String, shortScale: Float) {
        drawCenteredText(graphics, icon, x, y + 2, 20, 0xFFFFD45A.toInt(), true)
        graphics.drawString(mc.font, Component.literal(label), x + 22, y, 0xFFFFD45A.toInt(), true)
        if (value.length <= 6) {
            drawScaledText(graphics, value, x + 22, y + 12, valueColor, shortScale)
        } else {
            graphics.drawString(mc.font, Component.literal(value), x + 22, y + 13, valueColor, true)
        }
    }

    private fun drawLootGem(graphics: GuiGraphics, centerX: Int, centerY: Int, color: Int) {
        graphics.fill(centerX - 8, centerY - 2, centerX + 9, centerY + 3, 0x88604008.toInt())
        graphics.fill(centerX - 5, centerY - 5, centerX + 6, centerY + 6, 0xCCB88725.toInt())
        graphics.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 3, color)
    }

    private fun drawDashedLine(graphics: GuiGraphics, left: Int, y: Int, right: Int, color: Int) {
        var x = left
        while (x < right) {
            graphics.fill(x, y, (x + 4).coerceAtMost(right), y + 1, color)
            x += 7
        }
    }

    private fun drawCornerStuds(graphics: GuiGraphics, width: Int, height: Int, color: Int) {
        listOf(6 to 6, width - 12 to 6, 6 to height - 12, width - 12 to height - 12).forEach { (x, y) ->
            graphics.fill(x, y, x + 6, y + 6, 0xAA0A1A1F.toInt())
            graphics.fill(x + 2, y + 2, x + 4, y + 4, color)
        }
    }

    private fun drawCornerTabs(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, color: Int) {
        graphics.fill(x, y, x + 12, y + 3, color)
        graphics.fill(x, y, x + 3, y + 12, color)
        graphics.fill(x + width - 12, y, x + width, y + 3, color)
        graphics.fill(x + width - 3, y, x + width, y + 12, color)
        graphics.fill(x, y + height - 3, x + 12, y + height, color)
        graphics.fill(x, y + height - 12, x + 3, y + height, color)
        graphics.fill(x + width - 12, y + height - 3, x + width, y + height, color)
        graphics.fill(x + width - 3, y + height - 12, x + width, y + height, color)
    }

    private fun drawEdgeBrackets(graphics: GuiGraphics, width: Int, height: Int, color: Int) {
        graphics.fill(width / 2 - 12, 0, width / 2 + 12, 3, color)
        graphics.fill(width / 2 - 12, height - 3, width / 2 + 12, height, color)
        graphics.fill(0, height / 2 - 12, 3, height / 2 + 12, color)
        graphics.fill(width - 3, height / 2 - 12, width, height / 2 + 12, color)
    }

    private fun drawPixelSpark(graphics: GuiGraphics, centerX: Int, centerY: Int, color: Int) {
        graphics.fill(centerX - 2, centerY - 10, centerX + 2, centerY + 10, color)
        graphics.fill(centerX - 10, centerY - 2, centerX + 10, centerY + 2, color)
        graphics.fill(centerX - 5, centerY - 5, centerX + 5, centerY + 5, 0xAA0FD6DE.toInt())
        graphics.fill(centerX - 2, centerY - 2, centerX + 2, centerY + 2, 0xEEA6FFFF.toInt())
    }

    private fun drawSkull(graphics: GuiGraphics, x: Int, y: Int, color: Int) {
        graphics.fill(x + 3, y, x + 15, y + 12, 0x88404A54.toInt())
        graphics.fill(x + 1, y + 4, x + 17, y + 14, 0x88515C66.toInt())
        graphics.fill(x + 5, y + 6, x + 8, y + 9, color)
        graphics.fill(x + 11, y + 6, x + 14, y + 9, color)
        graphics.fill(x + 7, y + 12, x + 12, y + 15, 0x88313A43.toInt())
    }

    private fun drawHeaderCrystal(graphics: GuiGraphics, centerX: Int, centerY: Int, color: Int) {
        graphics.fill(centerX - 2, centerY - 10, centerX + 3, centerY + 10, 0xAA0FD6DE.toInt())
        graphics.fill(centerX - 6, centerY - 5, centerX + 7, centerY + 5, 0x8826DDE8.toInt())
        graphics.fill(centerX - 1, centerY - 6, centerX + 2, centerY + 6, color)
        graphics.fill(centerX - 12, centerY, centerX - 7, centerY + 2, color)
        graphics.fill(centerX + 7, centerY, centerX + 12, centerY + 2, color)
    }

    private fun drawBox(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, fill: Int, border: Int) {
        graphics.fill(x, y, x + width, y + height, fill)
        drawPixelBorder(graphics, x, y, x + width, y + height, fill, border)
    }

    private fun drawPanelBox(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, fill: Int, border: Int) {
        drawBox(graphics, x, y, width, height, fill, border)
        graphics.fill(x + 4, y + 4, x + width - 4, y + 6, 0x3326DDE8)
        graphics.fill(x + 4, y + height - 6, x + width - 4, y + height - 4, 0x44000000)
        graphics.fill(x + 4, y + 4, x + 6, y + height - 4, 0x22000000)
        graphics.fill(x + width - 6, y + 4, x + width - 4, y + height - 4, 0x22000000)
        drawCornerTabs(graphics, x, y, width, height, border)
    }

    private fun drawPixelBorder(graphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int, fill: Int, border: Int) {
        graphics.fill(left, top, right, top + 2, border)
        graphics.fill(left, bottom - 2, right, bottom, border)
        graphics.fill(left, top, left + 2, bottom, border)
        graphics.fill(right - 2, top, right, bottom, border)
        graphics.fill(left, top, left + 4, top + 4, fill)
        graphics.fill(right - 4, top, right, top + 4, fill)
        graphics.fill(left, bottom - 4, left + 4, bottom, fill)
        graphics.fill(right - 4, bottom - 4, right, bottom, fill)
    }

    private fun drawCenteredText(graphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, shadow: Boolean) {
        graphics.drawString(mc.font, Component.literal(text), x + (width - mc.font.width(text)) / 2, y, color, shadow)
    }

    private fun drawScaledText(graphics: GuiGraphics, text: String, x: Int, y: Int, color: Int, textScale: Float) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        graphics.pose().scale(textScale, textScale)
        graphics.drawString(mc.font, Component.literal(text), 0, 0, color, true)
        graphics.pose().popMatrix()
    }

    private fun drawScaledCenteredText(graphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, textScale: Float) {
        val scaledWidth = (mc.font.width(text) * textScale).roundToInt()
        val drawX = x + ((width - scaledWidth) / 2).coerceAtLeast(0)
        drawScaledText(graphics, text, drawX, y, color, textScale)
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
        state.hudLineOrder = normalizedHudLineOrder().toMutableList()
        saveState()
    }

    private fun saveState() {
        stateFile.parentFile.mkdirs()
        stateFile.writer().use { gson.toJson(state, it) }
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

    private fun targetLevelValue(): Int = targetLevel.get().toIntOrNull()?.coerceAtLeast(1) ?: 51

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

    private fun hardcodedXpPerRunValue(): Long = hardcodedXpPerRun.get().toLongOrNull()?.coerceAtLeast(1L) ?: 450_000L

    private fun dailyThresholdValue(): Long = dailyThreshold.get().toLongOrNull()?.coerceAtLeast(1L) ?: 600_000L

    private fun dailyMultiplierValue(): Double = dailyMultiplier.get().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.4

    private fun Long.format(): String = "%,d".format(this)

    private fun Long.compact(): String {
        val sign = if (this < 0) "-" else ""
        val abs = kotlin.math.abs(this)
        val body = when {
            abs >= 1_000_000_000L -> "%.2fb".format(Locale.US, abs / 1_000_000_000.0)
            abs >= 1_000_000L -> "%.2fm".format(Locale.US, abs / 1_000_000.0)
            abs >= 1_000L -> "%.1fk".format(Locale.US, abs / 1_000.0)
            else -> abs.toString()
        }
        return "$sign$body"
    }

    private fun Iterable<Int>.averageOrZero(): Double = if (none()) 0.0 else average()

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        val minutes = safe / 60
        val remainder = safe % 60
        return "%02dm %02ds".format(minutes, remainder)
    }

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
        var hudLineOrder: MutableList<String>? = DEFAULT_HUD_LINE_ORDER.toMutableList(),
    )

    data class HudStyle(
        val titleScale: Float = 2.34f,
        val titleY: Int = 8,
        val progressPercentScale: Float = 1.58f,
        val metricShortScale: Float = 1.70f,
        val lootShortScale: Float = 1.74f,
    ) {
        fun argb(rgb: Int, fallbackAlpha: Int): Int {
            val alpha = fallbackAlpha.coerceIn(0, 255)
            return (alpha shl 24) or (rgb and 0x00FFFFFF)
        }
    }

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

    data class ChestProfitCandidate(
        val chestName: String,
        val profit: Long,
        val cost: Int,
        val itemCount: Int,
        val scannedSlots: Int,
    ) {
        fun summary(): String = "chest=$chestName profit=$profit cost=$cost items=$itemCount scannedSlots=$scannedSlots"
    }

    data class ChestProfitItem(
        val itemId: String,
        val unitValue: Int,
        val amount: Int,
        val essence: Boolean,
    ) {
        val totalValue: Int get() = unitValue * amount
    }

    data class ProfileData(
        val playerName: String,
        val playerUuid: String,
        val profileName: String,
        val catacombsExperience: Long,
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
