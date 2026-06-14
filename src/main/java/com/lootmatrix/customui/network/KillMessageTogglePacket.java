package com.lootmatrix.customui.network;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet to toggle kill message visibility on the client.
 */
public class KillMessageTogglePacket {

    private final boolean enabled;

    public KillMessageTogglePacket(boolean enabled) {
        this.enabled = enabled;
    }

    public KillMessageTogglePacket(FriendlyByteBuf buf) {
        this.enabled = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            PacketReflectionExecutor.invokeStatic(
                    "com.lootmatrix.customui.network.PacketClientBridge",
                    "setKillMessageEnabled",
                    new Class<?>[]{boolean.class},
                    enabled
            );
        }));
        ctx.setPacketHandled(true);
    }
}

