package com.lootmatrix.customui.network;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * Packet to set the kill message display mode on the client.
 * Mode: "AllyTeam" = green for same team, red for enemy
 *       "AllyPlayer" = green for own kills, red for other players' kills
 */
public class KillMessageModePacket {

    private static final Logger LOGGER = LogManager.getLogger("CustomUI-KillMessage");

    private final String mode;

    public KillMessageModePacket(String mode) {
        this.mode = mode;
    }

    public KillMessageModePacket(FriendlyByteBuf buf) {
        this.mode = buf.readUtf(32);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(mode, 32);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            PacketReflectionExecutor.invokeStatic(
                    "com.lootmatrix.customui.network.PacketClientBridge",
                    "setKillMessageMode",
                    new Class<?>[]{String.class},
                    mode
            );
        }));
        ctx.setPacketHandled(true);
    }
}

