package dev.krister.dungeonprogresshud

import com.github.synnerz.devonian.Devonian
import com.github.synnerz.devonian.api.ItemUtils
import com.github.synnerz.devonian.api.Location
import com.github.synnerz.devonian.api.SkyblockPrices
import com.github.synnerz.devonian.api.events.GuiKeyDownEvent
import com.github.synnerz.devonian.api.events.ChatEvent
import com.github.synnerz.devonian.api.events.TickEvent
import com.github.synnerz.devonian.config.Categories
import com.github.synnerz.devonian.hud.texthud.TextHudFeature
import com.github.synnerz.devonian.utils.StringUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object DungeonProgressHudAddon : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("DungeonProgressHud")
    private val debugLogFile = FabricLoader.getInstance().configDir.resolve("DungeonProgressHud/debug.log").toFile()
    private var commandsRegistered = false
    private var registered = false
    private var feature: DungeonProgressHudFeature? = null
    private var renderHookSeen = false
    private var lastMissingFeatureLog = 0L

    override fun onInitializeClient() {
        debug("Client entrypoint initializing")
        registerCommands()
    }

    fun registerWithDevonian() {
        if (registered) return
        debug("Registering DungeonProgressHud feature with Devonian")
        registerCommands()
        val created = DungeonProgressHudFeature()
        feature = created
        Devonian.addFeatureInstance(created)
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

    fun onInventoryClick(slotId: Int, button: Int, clickType: ClickType) {
        feature?.onInventoryClick(slotId, button, clickType)
    }

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
                        withFeature { it.refresh(true) }
                        1
                    })
                    .then(literal("reset").executes {
                        withFeature { it.resetSamples() }
                        1
                    })
                    .then(literal("profit")
                        .then(literal("toggle").executes {
                            withFeature { it.toggleProfitMode() }
                            1
                        })
                    )
                    .then(literal("fake").executes {
                        withFeature { it.fakeOpenCurrentScreen() }
                        1
                    })
                    .then(literal("apikey").then(argument("key", StringArgumentType.greedyString()).executes {
                        withFeature { feature ->
                            feature.setApiKey(StringArgumentType.getString(it, "key"))
                        }
                        1
                    }))
            )
        }
    }

    private inline fun withFeature(action: (DungeonProgressHudFeature) -> Unit) {
        val current = feature ?: return
        action(current)
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

class DungeonProgressHudFeature : TextHudFeature(
    "dungeonProgressHud",
    "Shows Catacombs XP progress and estimated runs left.",
    Categories.DUNGEONS,
    "catacombs",
    displayName = "Dungeon Progress HUD",
    subcategory = "HUD",
) {
    private val PREFIX = "&6[&bDPH&6]&r "
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configDir = FabricLoader.getInstance().configDir.resolve("DungeonProgressHud").toFile()
    private val stateFile = File(configDir, "runs.json")
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val renderHud = addSwitch("renderHud", true, "Draw the HUD during normal gameplay.", "Render HUD")
    private val showEverywhere = addSwitch("showEverywhere", true, "Render everywhere instead of only Dungeon Hub/Catacombs.", "Show Everywhere")
    private val apiKey = addTextInput("apiKey", "", "Hypixel API key.", "Hypixel API Key")
    private val targetLevel = addTextInput("targetLevel", "50", "Target Catacombs level.", "Target Level")
    private val floorLabel = addTextInput("floorLabel", "M7", "Floor label used for observed samples.", "Floor Label")

    private val xpMode = addSelection("xpMode", 0, listOf("Observed Average", "Hardcoded"), "XP/run source.", "XP/Run Mode")
    private val hardcodedXpPerRun = addTextInput("hardcodedXpPerRun", "450000", "Fallback XP per run.", "Hardcoded XP/Run")
    private val scaleDailyRunXp = addSwitch("scaleDailyRunXp", true, "Divide high raw run XP by the daily multiplier.", "Scale Daily Run XP")
    private val dailyThreshold = addTextInput("dailyThreshold", "600000", "Only scale raw run XP above this value.", "Daily XP Threshold")
    private val dailyMultiplier = addTextInput("dailyMultiplier", "1.4", "Raw run XP is divided by this when daily scaling applies.", "Daily XP Multiplier")

    private val showCurrentLevel = addSwitch("showCurrentLevel", true, "Show current Catacombs level.", "Current Level")
    private val showCurrentXp = addSwitch("showCurrentXp", true, "Show current Catacombs XP.", "Current XP")
    private val showTarget = addSwitch("showTarget", true, "Show target Catacombs level.", "Target")
    private val showRemaining = addSwitch("showRemaining", true, "Show XP remaining.", "XP Remaining")
    private val showFloor = addSwitch("showFloor", true, "Show floor label.", "Floor")
    private val showXpPerRun = addSwitch("showXpPerRun", true, "Show effective XP/run.", "XP/Run")
    private val showRunsLeft = addSwitch("showRunsLeft", true, "Show estimated runs left.", "Runs Left")
    private val showProfile = addSwitch("showProfile", false, "Show selected SkyBlock profile.", "Profile")
    private val showLastRun = addSwitch("showLastRun", true, "Show last observed normalized XP delta.", "Last Run XP")
    private val showObservedCount = addSwitch("showObservedCount", false, "Show observed sample count.", "Observed Count")

    private val trackChestProfit = addSwitch("trackChestProfit", true, "Track profit when claiming dungeon reward chests.", "Track Chest Profit")
    private val showChestProfit = addSwitch("showChestProfit", true, "Show tracked dungeon chest profit.", "Chest Profit")
    private val chestProfitMode = addSelection("chestProfitMode", 0, listOf("Session", "Total"), "Choose whether chest profit lines use this session or all tracked chests.", "Chest Profit Mode")
    private val showChestCount = addSwitch("showChestCount", true, "Show tracked dungeon reward chest count.", "Chest Count")
    private val includeEssenceProfit = addSwitch("includeEssenceProfit", true, "Include essence value in chest profit.", "Include Essence")
    private val includeDungeonKeyCost = addSwitch("includeDungeonKeyCost", false, "Subtract Dungeon Chest Key value when a reward chest requires one.", "Count Dungeon Key Cost")

    private val refreshButton = addButton({ refresh(true) }, "Refresh", "Force API refresh.", "Refresh Now")
    private val resetButton = addButton({ resetSamples() }, "Reset", "Clear observed XP samples.", "Reset Observed Runs")
    private val keybindCategory by lazy {
        KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath("dungeonprogresshud", "keybinds"))
    }
    private val fakeOpenKey = KeyBindingHelper.registerKeyBinding(
        KeyMapping("key.dungeonprogresshud.fakeOpenChest", GLFW.GLFW_KEY_H, keybindCategory)
    )

    private var state = RunState()
    private var data: ProfileData? = null
    private var status = "API key missing"
    private var refreshing = false
    private var lastRefresh = 0L
    private var wasVisible = false
    private var startupRefreshAttempted = false
    private var runtimeStarted = false
    private var lastRenderStateLog = 0L
    private var renderedOnce = false
    private var pendingChestProfit: ChestProfitCandidate? = null
    private var lastCroesusCandidates: Map<String, ChestProfitCandidate> = emptyMap()
    private var selectedCroesusCandidate: ChestProfitCandidate? = null
    private var lastChestScreenLog = 0L
    private var lastRecordedChestAt = 0L
    private var lastDungeonCompletionChat = ""
    private var lastDungeonCompletionChatAt = 0L
    private var lastDungeonCompletionRawXp = 0L
    private var lastDungeonCompletionNormalizedXp = 0L
    private var sessionChestProfit = 0L
    private var sessionChestsOpened = 0

    override fun getEditText(): List<String> = listOf(
        "&bCata Level: &fC49",
        "&bCata XP: &f453,559,640",
        "&bTarget: &fC50",
        "&bRemaining: &f116,250,000",
        "&bFloor: &fM7",
        "&bXP/Run: &f450,000 &7(fallback)",
        "&bRuns Left: &a259",
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

    private fun startRuntime(reason: String) {
        if (runtimeStarted) return
        runtimeStarted = true
        log("Feature runtime started by $reason")
        loadState()
    }

    private fun clientTick() {
        if (!startupRefreshAttempted && sessionReady()) {
            startupRefreshAttempted = true
            log("Startup refresh triggered for user=${mc.user.name} uuid=${mc.user.profileId}")
            refresh(false)
        }

        scanCurrentChestScreen()
        val visible = shouldRender()
        if (visible && !wasVisible) refresh(false)
        wasVisible = visible
        setLines(buildLines())
    }

    fun renderHud(graphics: GuiGraphics) {
        startRuntime("gui render")

        if (!renderHud.get() || !shouldRender()) {
            logRenderState("Render blocked renderHud=${renderHud.get()} enabled=${isEnabled()} shouldRender=${shouldRender()} showEverywhere=${showEverywhere.get()}")
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

    fun refresh(force: Boolean) {
        if (refreshing) return
        if (!sessionReady()) {
            status = "Waiting for session"
            log("Refresh skipped: session not ready")
            return
        }
        if (!force && System.currentTimeMillis() - lastRefresh < 300_000 && data != null) return
        if (apiKey.get().isBlank()) {
            status = "API key missing"
            log("Refresh skipped: missing API key")
            return
        }

        refreshing = true
        status = "Refreshing..."
        log("Refresh started force=$force user=${mc.user.name} uuid=${mc.user.profileId}")

        thread(name = "DungeonProgressHud-API", isDaemon = true) {
            runCatching { fetchProfile() }
                .onSuccess {
                    data = it
                    status = "Loaded ${it.profileName}"
                    lastRefresh = System.currentTimeMillis()
                    log("Refresh success profile=${it.profileName} xp=${it.catacombsExperience} level=${currentCataLevel(it.catacombsExperience)}")
                    recordSample(it)
                }
                .onFailure {
                    status = it.message ?: "Refresh failed"
                    log("Refresh failed: ${it.stackTraceToString()}")
                }
            refreshing = false
        }
    }

    fun resetSamples() {
        state.samples.clear()
        state.lastCatacombsXp = data?.catacombsExperience ?: 0L
        state.lastPlayerUuid = data?.playerUuid.orEmpty()
        saveState()
        log("Observed run samples reset")
        send("Observed runs reset.")
    }

    fun setApiKey(key: String) {
        apiKey.set(key.trim())
        log("API key updated length=${key.trim().length}")
        refresh(true)
        send("API key saved.")
    }

    fun setProfitMode(mode: String) {
        val normalized = if (mode.equals("Total", true)) "Total" else "Session"
        chestProfitMode.set(if (normalized == "Total") 1 else 0)
        log("Chest profit mode set to $normalized")
        send("$normalized view.")
    }

    fun toggleProfitMode() {
        setProfitMode(if (chestProfitMode.getCurrent() == "Total") "Session" else "Total")
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

    private fun buildLines(): List<String> {
        val profile = data ?: return listOf("&bDungeon Progress: &7$status")
        val xpPerRun = effectiveXpPerRun()
        val remaining = xpRemaining(profile.catacombsExperience, targetLevelValue())
        val runs = xpPerRun.takeIf { it > 0 }?.let { ceil(remaining.toDouble() / it.toDouble()).toLong() }
        val samples = samplesForFloor()
        val last = samples.lastOrNull()
        val source = if (xpMode.getCurrent() == "Hardcoded") "hardcoded" else if (samples.isEmpty()) "fallback" else "observed"
        val currentLevel = currentCataLevel(profile.catacombsExperience)

        return buildList {
            if (showCurrentLevel.get()) add("&bCata Level: &fC$currentLevel")
            if (showCurrentXp.get()) add("&bCata XP: &f${profile.catacombsExperience.format()}")
            if (showTarget.get()) add("&bTarget: &fC${targetLevelValue()}")
            if (showRemaining.get()) add("&bRemaining: &f${remaining.format()}")
            if (showFloor.get()) add("&bFloor: &f${floorValue()}")
            if (showXpPerRun.get()) add("&bXP/Run: &f${xpPerRun.format()} &7($source)")
            if (showRunsLeft.get()) add("&bRuns Left: &a${runs?.format() ?: "N/A"}")
            if (showProfile.get()) add("&bProfile: &f${profile.profileName}")
            if (showLastRun.get()) add("&bLast Run: &f${last?.normalizedXpDelta?.format() ?: "N/A"}")
            if (showObservedCount.get()) add("&bObserved Runs: &f${samples.size}")
            if (showChestProfit.get()) {
                val stats = chestProfitStats()
                add("&bProfit: &a${stats.profit.formatCoins()} &7(${stats.label})")
                if (showChestCount.get()) add("&bChests Opened: &f${stats.chests}")
                add("&bLast Chest: &f${state.lastChestName.ifBlank { "N/A" }} &a${state.lastChestProfit.formatCoins()}")
                add("&bAvg Chest: &a${stats.average.formatCoins()}")
            }
        }
    }

    private fun scanCurrentChestScreen() {
        if (!trackChestProfit.get()) return
        val screen = currentChestScreen()
        if (screen == null) {
            pendingChestProfit = null
            return
        }

        val title = screen.title.string
        if (!chestNames.contains(title)) {
            pendingChestProfit = null
            return
        }

        val candidate = runCatching { parseChestProfit(screen) }
            .onFailure { log("Chest screen parse failed: ${it.stackTraceToString()}") }
            .getOrNull()
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
        val uuid = user.profileId?.toString()?.replace("-", "") ?: error("Session UUID unavailable")
        val encodedUuid = URLEncoder.encode(uuid, StandardCharsets.UTF_8)
        log("Fetching Hypixel profile user=${user.name} uuid=$uuid")
        val connection = URI("https://api.hypixel.net/v2/skyblock/profiles?uuid=$encodedUuid").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("API-Key", apiKey.get())
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

    private fun recordSample(profile: ProfileData) {
        if (state.lastPlayerUuid != profile.playerUuid) {
            state.lastPlayerUuid = profile.playerUuid
            state.lastCatacombsXp = profile.catacombsExperience
            saveState()
            return
        }

        val delta = profile.catacombsExperience - state.lastCatacombsXp
        state.lastCatacombsXp = profile.catacombsExperience
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
        val cleanMessage = message.cleanMc()
        if (!cleanMessage.contains("Cata EXP") || !cleanMessage.contains("Defeated")) return
        val normalizedMessage = cleanMessage.replace(Regex("\\s*\\(x\\d+\\)$"), "")
        val now = System.currentTimeMillis()
        if (normalizedMessage == lastDungeonCompletionChat && now - lastDungeonCompletionChatAt < 10_000) {
            log("Duplicate dungeon completion chat ignored")
            return
        }

        val cataXp = dungeonCompletionCataXpRegex.find(normalizedMessage)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toLongOrNull()
            ?: run {
                log("Dungeon completion chat matched but Cata EXP parse failed: $normalizedMessage")
                return
            }

        lastDungeonCompletionChat = normalizedMessage
        lastDungeonCompletionChatAt = now
        lastDungeonCompletionRawXp = cataXp
        lastDungeonCompletionNormalizedXp = normalizeRunXp(cataXp)
        state.samples.add(RunSample(now, floorValue(), cataXp, lastDungeonCompletionNormalizedXp))
        while (state.samples.size > 100) state.samples.removeAt(0)
        saveState()
        log("Recorded dungeon completion chat XP raw=$cataXp normalized=$lastDungeonCompletionNormalizedXp floor=${floorValue()}")
    }

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

    private fun shouldRender(): Boolean = isEnabled() && (showEverywhere.get() || isDungeonArea())

    private fun drawDirect(graphics: GuiGraphics, lines: List<String>) {
        graphics.pose().pushMatrix()
        val drawX = if (x.isFinite()) x.toFloat() else 10f
        val drawY = if (y.isFinite()) y.toFloat() else 10f
        graphics.pose().translate(drawX, drawY)
        graphics.pose().scale(scale, scale)

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

    private fun loadState() {
        state = runCatching {
            if (!stateFile.exists()) return
            stateFile.reader().use { gson.fromJson(it, RunState::class.java) }
        }.getOrNull() ?: RunState()
        if (state.totalChestsOpened == 0 && state.chestProfits.isNotEmpty()) {
            state.totalChestsOpened = state.chestProfits.size
            state.totalChestProfit = state.chestProfits.sumOf { it.profit }
        }
        saveState()
    }

    private fun saveState() {
        stateFile.parentFile.mkdirs()
        stateFile.writer().use { gson.toJson(state, it) }
    }

    private fun sessionReady(): Boolean = mc.user.name.isNotBlank() && mc.user.profileId != null

    private fun xpRemaining(currentXp: Long, targetLevel: Int): Long = (targetXp(targetLevel) - currentXp).coerceAtLeast(0)

    private fun targetXp(level: Int): Long = cumulativeCatacombsXp[(level.coerceIn(1, 50)) - 1]

    private fun targetLevelValue(): Int = targetLevel.get().toIntOrNull()?.coerceIn(1, 50) ?: 50

    private fun currentCataLevel(xp: Long): Int = cumulativeCatacombsXp.indexOfLast { xp >= it }.let { (it + 1).coerceIn(0, 50) }

    private fun floorValue(): String = floorLabel.get().ifBlank { "M7" }.uppercase(Locale.ROOT)

    private fun hardcodedXpPerRunValue(): Long = hardcodedXpPerRun.get().toLongOrNull()?.coerceAtLeast(1L) ?: 450_000L

    private fun dailyThresholdValue(): Long = dailyThreshold.get().toLongOrNull()?.coerceAtLeast(1L) ?: 600_000L

    private fun dailyMultiplierValue(): Double = dailyMultiplier.get().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.4

    private fun Long.format(): String = "%,d".format(this)

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
        var chestProfits: MutableList<ChestProfitSample> = mutableListOf(),
        var lastChestName: String = "",
        var lastChestProfit: Long = 0,
        var totalChestProfit: Long = 0,
        var totalChestsOpened: Int = 0,
    )

    data class RunSample(
        var timestamp: Long = 0,
        var floorLabel: String = "M7",
        var rawXpDelta: Long = 0,
        var normalizedXpDelta: Long = 0,
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

    private val cumulativeCatacombsXp = listOf(
        50L, 125L, 235L, 395L, 625L, 955L, 1425L, 2095L, 3045L, 4385L,
        6275L, 8940L, 12700L, 17960L, 25340L, 35640L, 50040L, 70040L, 97640L, 135640L,
        188140L, 259640L, 356640L, 488640L, 668640L, 911640L, 1239640L, 1683640L, 2284640L, 3084640L,
        4149640L, 5559640L, 7459640L, 9959640L, 13259640L, 17559640L, 23159640L, 30359640L, 39559640L, 51559640L,
        66559640L, 85559640L, 109559640L, 139559640L, 177559640L, 225559640L, 285559640L, 360559640L, 453559640L, 569809640L,
    )

    private val chestNames = setOf("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")
    private val runChestRegex = "^(?:Master )?Catacombs - Floor [IV]+$".toRegex()
    private val costRegex = "^(\\d[\\d,]+) Coins$".toRegex()
    private val enchantedBookRegex = "^Enchanted Book \\(([\\w ]+) ([IV]+)\\)$".toRegex()
    private val essenceRegex = "^(Wither|Undead) Essence x(\\d+)$".toRegex()
    private val dungeonCompletionCataXpRegex = "\\+([\\d,]+)\\s+Cata EXP".toRegex()
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
