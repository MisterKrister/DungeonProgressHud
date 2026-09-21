package dev.krister.dungeonprogresshud.mixin;

import dev.krister.dungeonprogresshud.DungeonProgressHudAddon;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    // Read the server component on the game thread, before chat mods replace it or replay it.
    @Inject(method = "handleSystemChat", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void dungeonprogresshud$chat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!packet.overlay()) DungeonProgressHudAddon.INSTANCE.onChatMessage(packet.content());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void dungeonprogresshud$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        DungeonProgressHudAddon.INSTANCE.onServerInventoryUpdate(packet.getContainerId());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void dungeonprogresshud$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        DungeonProgressHudAddon.INSTANCE.onServerInventoryUpdate(packet.containerId());
    }
}
