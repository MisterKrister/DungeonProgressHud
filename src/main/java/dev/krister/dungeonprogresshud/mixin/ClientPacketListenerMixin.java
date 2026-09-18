package dev.krister.dungeonprogresshud.mixin;

import dev.krister.dungeonprogresshud.DungeonProgressHudAddon;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void dungeonprogresshud$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        DungeonProgressHudAddon.INSTANCE.onServerInventoryUpdate(packet.getContainerId());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void dungeonprogresshud$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        DungeonProgressHudAddon.INSTANCE.onServerInventoryUpdate(packet.containerId());
    }
}
