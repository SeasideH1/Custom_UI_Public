package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Full configuration sync packet for the scoreboard overlay.
 * Sent on player join and whenever config changes via commands.
 */
public class ScoreboardOverlayConfigPacket {

    public final String teamAIconPath;
    public final int teamABarColor;
    public final String teamBIconPath;
    public final int teamBBarColor;
    public final String glowMode;
    public final boolean visible;
    public final int timerColor;
    public final int timerTempColor;
    public final int timerTempDuration;
    public final boolean timerColorSwitch;
    public final boolean reverseFillDirection;

    public ScoreboardOverlayConfigPacket(String teamAIconPath, int teamABarColor,
                                          String teamBIconPath, int teamBBarColor,
                                          String glowMode, boolean visible,
                                          int timerColor, int timerTempColor,
                                          int timerTempDuration, boolean timerColorSwitch,
                                          boolean reverseFillDirection) {
        this.teamAIconPath = teamAIconPath;
        this.teamABarColor = teamABarColor;
        this.teamBIconPath = teamBIconPath;
        this.teamBBarColor = teamBBarColor;
        this.glowMode = glowMode;
        this.visible = visible;
        this.timerColor = timerColor;
        this.timerTempColor = timerTempColor;
        this.timerTempDuration = timerTempDuration;
        this.timerColorSwitch = timerColorSwitch;
        this.reverseFillDirection = reverseFillDirection;
    }

    public ScoreboardOverlayConfigPacket(FriendlyByteBuf buf) {
        this.teamAIconPath = buf.readUtf(256);
        this.teamABarColor = buf.readInt();
        this.teamBIconPath = buf.readUtf(256);
        this.teamBBarColor = buf.readInt();
        this.glowMode = buf.readUtf(32);
        this.visible = buf.readBoolean();
        this.timerColor = buf.readInt();
        this.timerTempColor = buf.readInt();
        this.timerTempDuration = buf.readVarInt();
        this.timerColorSwitch = buf.readBoolean();
        this.reverseFillDirection = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(teamAIconPath, 256);
        buf.writeInt(teamABarColor);
        buf.writeUtf(teamBIconPath, 256);
        buf.writeInt(teamBBarColor);
        buf.writeUtf(glowMode, 32);
        buf.writeBoolean(visible);
        buf.writeInt(timerColor);
        buf.writeInt(timerTempColor);
        buf.writeVarInt(timerTempDuration);
        buf.writeBoolean(timerColorSwitch);
        buf.writeBoolean(reverseFillDirection);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    PacketReflectionExecutor.invokeStatic(
                            "com.lootmatrix.customui.network.PacketClientBridge",
                            "handleScoreboardConfig",
                            new Class<?>[]{ScoreboardOverlayConfigPacket.class},
                            this
                    ));
        });
        ctx.setPacketHandled(true);
    }
}
