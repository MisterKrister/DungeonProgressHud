package dev.krister.hudscreenshottest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class HudScreenshotTestMod implements ClientModInitializer {
    private static final String WORLD_ID = "New World";
    private static final String TRIGGER_FILE = "hud-screenshot-trigger.txt";
    private static final int CAPTURE_DELAY_TICKS = 12;
    private static int ticks;
    private static boolean openRequested;
    private static boolean firstScreenshotQueued;
    private static boolean readyLogged;
    private static long lastTriggerModified;
    private static int pendingCaptureTick = -1;
    private static String pendingCaptureReason = "";
    private static int captureCount;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(HudScreenshotTestMod::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("hudshot")
                .executes(context -> {
                    requestCapture(Minecraft.getInstance(), "command");
                    context.getSource().sendFeedback(Component.literal("Queued HUD screenshot."));
                    return 1;
                })
                .then(literal("open")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        openRequested = false;
                        openWorldIfNeeded(client);
                        context.getSource().sendFeedback(Component.literal("Requested test world open."));
                        return 1;
                    }))
                .then(literal("where")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        context.getSource().sendFeedback(Component.literal("Trigger file: " + triggerPath(client)));
                        return 1;
                    }))
        ));
    }

    private static void tick(Minecraft client) {
        if (!isTestingInstance(client)) return;
        ticks++;

        if (ticks >= 40 && client.level == null) {
            openWorldIfNeeded(client);
            return;
        }

        if (client.level == null || client.player == null) return;

        pollTriggerFile(client);
        if (!firstScreenshotQueued && shouldCapture(client)) {
            firstScreenshotQueued = true;
            requestCapture(client, "auto-ready");
        }

        if (pendingCaptureTick >= 0 && ticks >= pendingCaptureTick) {
            takeScreenshot(client, pendingCaptureReason);
            pendingCaptureTick = -1;
            pendingCaptureReason = "";
        }
    }

    private static void openWorldIfNeeded(Minecraft client) {
        if (openRequested || client.level != null) return;
        openRequested = true;
        log(client, "Opening singleplayer world: " + WORLD_ID);
        client.createWorldOpenFlows().openWorld(WORLD_ID, () -> log(client, "World open cancelled or failed."));
    }

    private static void requestCapture(Minecraft client, String reason) {
        if (client.level == null || client.player == null) {
            log(client, "Screenshot queued before world ready reason=" + reason);
            pendingCaptureTick = ticks + 60;
            pendingCaptureReason = reason;
            return;
        }
        pendingCaptureTick = ticks + CAPTURE_DELAY_TICKS;
        pendingCaptureReason = reason;
        log(client, "Screenshot queued reason=" + reason + " captureTick=" + pendingCaptureTick);
    }

    private static void takeScreenshot(Minecraft client, String reason) {
        captureCount++;
        String name = "dph-ui-test-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + captureCount + ".png";
        log(client, "Taking screenshot: " + name + " reason=" + reason);
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), 1, component -> log(client, component.getString()));
    }

    private static void pollTriggerFile(Minecraft client) {
        if (ticks % 5 != 0) return;
        Path trigger = triggerPath(client);
        try {
            if (!Files.exists(trigger)) return;
            long modified = Files.getLastModifiedTime(trigger).toMillis();
            if (modified <= lastTriggerModified) return;
            lastTriggerModified = modified;
            String content = Files.readString(trigger).trim();
            if (content.equalsIgnoreCase("open")) {
                openRequested = false;
                openWorldIfNeeded(client);
                return;
            }
            requestCapture(client, content.isBlank() ? "file-trigger" : "file-trigger:" + content);
        } catch (Exception error) {
            log(client, "Trigger poll failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static boolean shouldCapture(Minecraft client) {
        if (ticks < 500) return false;
        if (hasFreshDphData(client)) {
            if (!readyLogged) {
                readyLogged = true;
                log(client, "Fresh DPH data seen; waiting final frames.");
            }
            return ticks >= 620;
        }
        return ticks >= 1800;
    }

    private static boolean hasFreshDphData(Minecraft client) {
        try {
            Path log = client.gameDirectory.toPath().resolve("config").resolve("DungeonProgressHud").resolve("debug.log");
            if (!Files.exists(log)) return false;
            String text = Files.readString(log);
            return text.contains("Refresh success") && text.contains("HUD rendered");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isTestingInstance(Minecraft client) {
        Path gameDir = client.gameDirectory.toPath().toAbsolutePath().normalize();
        String normalized = gameDir.toString().toLowerCase();
        return normalized.contains("game testing")
            || normalized.contains("game testisg")
            || normalized.contains("gametesting");
    }

    private static Path triggerPath(Minecraft client) {
        return client.gameDirectory.toPath().resolve(TRIGGER_FILE);
    }

    private static void log(Minecraft client, String message) {
        try {
            Path log = client.gameDirectory.toPath().resolve("hud-screenshot-test.log");
            Files.writeString(log, "[" + LocalDateTime.now() + "] " + message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
