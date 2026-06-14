package com.lootmatrix.customui.network;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Syncs TeamGlow state from server to client for Mohist/hybrid servers.
 */
public class TeamGlowSyncPacket {

    private final UUID targetId;
    private final boolean hasGlow;

    public TeamGlowSyncPacket(UUID targetId, boolean hasGlow) {
        this.targetId = targetId;
        this.hasGlow = hasGlow;
    }

    public TeamGlowSyncPacket(FriendlyByteBuf buf) {
        this.targetId = buf.readUUID();
        this.hasGlow = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(targetId);
        buf.writeBoolean(hasGlow);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "setServerGlow",
                        new Class<?>[]{UUID.class, boolean.class},
                        targetId, hasGlow
                )
        ));
        ctx.setPacketHandled(true);
    }
}

