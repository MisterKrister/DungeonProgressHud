package dev.krister.dungeonprogresshud.mixin;

import dev.krister.dungeonprogresshud.DungeonProgressHudAddon;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void dungeonprogresshud$hudModeButtonClicked(long windowHandle, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS || buttonInfo.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (DungeonProgressHudAddon.INSTANCE.onHudModeButtonPressed()) {
            ci.cancel();
        }
    }
}
