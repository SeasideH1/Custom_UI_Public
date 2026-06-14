package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Delta-compressed per-tick update packet for the scoreboard overlay.
 * Uses a bitmask to indicate which fields are present:
 *   Bit 0: teamAProgress (float)
 *   Bit 1: teamAScore (int)
 *   Bit 2: teamBProgress (float)
 *   Bit 3: teamBScore (int)
 *   Bit 4: timerTicks (int)
 *
 * Only changed fields are serialized, keeping bandwidth minimal.
 */
public class ScoreboardOverlayUpdatePacket {

    public final byte deltaMask;
    public float teamAProgress;
    public int teamAScore;
    public float teamBProgress;
    public int teamBScore;
    public int timerTicks;

    public ScoreboardOverlayUpdatePacket(byte deltaMask, float teamAProgress, int teamAScore,
                                          float teamBProgress, int teamBScore, int timerTicks) {
        this.deltaMask = deltaMask;
        this.teamAProgress = teamAProgress;
        this.teamAScore = teamAScore;
        this.teamBProgress = teamBProgress;
        this.teamBScore = teamBScore;
        this.timerTicks = timerTicks;
    }

    public ScoreboardOverlayUpdatePacket(FriendlyByteBuf buf) {
        this.deltaMask = buf.readByte();
        if ((deltaMask & 0x01) != 0) teamAProgress = buf.readFloat();
        if ((deltaMask & 0x02) != 0) teamAScore = buf.readVarInt();
        if ((deltaMask & 0x04) != 0) teamBProgress = buf.readFloat();
        if ((deltaMask & 0x08) != 0) teamBScore = buf.readVarInt();
        if ((deltaMask & 0x10) != 0) timerTicks = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(deltaMask);
        if ((deltaMask & 0x01) != 0) buf.writeFloat(teamAProgress);
        if ((deltaMask & 0x02) != 0) buf.writeVarInt(teamAScore);
        if ((deltaMask & 0x04) != 0) buf.writeFloat(teamBProgress);
        if ((deltaMask & 0x08) != 0) buf.writeVarInt(teamBScore);
        if ((deltaMask & 0x10) != 0) buf.writeVarInt(timerTicks);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    PacketReflectionExecutor.invokeStatic(
                            "com.lootmatrix.customui.network.PacketClientBridge",
                            "handleScoreboardUpdate",
                            new Class<?>[]{ScoreboardOverlayUpdatePacket.class},
                            this
                    ));
        });
        ctx.setPacketHandled(true);
    }
}

