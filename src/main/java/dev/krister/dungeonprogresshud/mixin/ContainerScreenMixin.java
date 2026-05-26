package dev.krister.dungeonprogresshud.mixin;

import dev.krister.dungeonprogresshud.DungeonProgressHudAddon;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void dungeonprogresshud$hudOrderMouseClicked(MouseButtonEvent input, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        boolean shiftDown = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (DungeonProgressHudAddon.INSTANCE.onHudOrderMouseClicked((AbstractContainerScreen<?>) (Object) this, input, shiftDown)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void dungeonprogresshud$hudOrderMouseDragged(MouseButtonEvent input, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (DungeonProgressHudAddon.INSTANCE.onHudOrderMouseDragged((AbstractContainerScreen<?>) (Object) this, input, dragX, dragY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void dungeonprogresshud$hudOrderMouseReleased(MouseButtonEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (DungeonProgressHudAddon.INSTANCE.onHudOrderMouseReleased((AbstractContainerScreen<?>) (Object) this, input)) {
            cir.setReturnValue(true);
        }
    }

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
