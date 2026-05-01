package dev.krister.dungeonprogresshud.mixin;

import dev.krister.dungeonprogresshud.DungeonProgressHudAddon;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void dungeonprogresshud$fakeOpenChest(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (input.key() == GLFW.GLFW_KEY_PERIOD || input.key() == GLFW.GLFW_KEY_ESCAPE) {
            DungeonProgressHudAddon.INSTANCE.onResetSelectionKey((AbstractContainerScreen<?>) (Object) this);
            return;
        }
        if (!DungeonProgressHudAddon.INSTANCE.matchesFakeOpenKey(input)) {
            return;
        }
        if (DungeonProgressHudAddon.INSTANCE.onFakeOpenKey((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
