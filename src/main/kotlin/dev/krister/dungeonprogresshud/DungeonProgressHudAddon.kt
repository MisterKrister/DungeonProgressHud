package dev.krister.dungeonprogresshud

import com.github.noamm9.features.impl.dungeon.DungeonProgressHudFeature
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import org.slf4j.LoggerFactory

object DungeonProgressHudAddon : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("DungeonProgressHud")
    private var commandsRegistered = false
    private var registered = false
    private var feature: DungeonProgressHudFeature? = null
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

    fun registerFeature(created: DungeonProgressHudFeature) {
        if (registered) return
        debug("Registering DungeonProgressHud feature with NoammAddons")
        registerCommands()
        feature = created
        if (pendingServerJoinAt > 0L) {
            created.onServerJoin(pendingServerJoinAt)
            pendingServerJoinAt = 0L
        }
        registered = true
        debug("NoammAddons feature registration complete")
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
