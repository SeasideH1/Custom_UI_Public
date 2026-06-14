package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Server → Client packet to sync capture zone state.
 * Delta-compressed: only sent when zone state changes.
 */
public class CaptureZoneSyncPacket {

    private final String zoneId;
    private final String displayName;
    private final float progress;
    @Nullable private final String capturingTeam;
    @Nullable private final String ownerTeam;
    private final boolean contested;

    public CaptureZoneSyncPacket(String zoneId, String displayName, float progress,
                                  @Nullable String capturingTeam, @Nullable String ownerTeam,
                                  boolean contested) {
        this.zoneId = zoneId;
        this.displayName = displayName;
        this.progress = progress;
        this.capturingTeam = capturingTeam;
        this.ownerTeam = ownerTeam;
        this.contested = contested;
    }

    public CaptureZoneSyncPacket(FriendlyByteBuf buf) {
        this.zoneId = buf.readUtf(256);
        this.displayName = buf.readUtf(256);
        this.progress = buf.readFloat();
        this.capturingTeam = buf.readBoolean() ? buf.readUtf(256) : null;
        this.ownerTeam = buf.readBoolean() ? buf.readUtf(256) : null;
        this.contested = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(zoneId, 256);
        buf.writeUtf(displayName, 256);
        buf.writeFloat(progress);
        buf.writeBoolean(capturingTeam != null);
        if (capturingTeam != null) buf.writeUtf(capturingTeam, 256);
        buf.writeBoolean(ownerTeam != null);
        if (ownerTeam != null) buf.writeUtf(ownerTeam, 256);
        buf.writeBoolean(contested);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                // Call client handler via reflection bridge to avoid loading client classes on server
                Class<?> bridge = Class.forName("com.lootmatrix.customui.network.PacketClientBridge");
                bridge.getMethod("handleCaptureZoneSync", String.class, String.class,
                                float.class, String.class, String.class, boolean.class)
                        .invoke(null, zoneId, displayName, progress, capturingTeam, ownerTeam, contested);
            } catch (Exception e) {
                // Silently ignore on server side
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
