package com.lootmatrix.customui.network;

import com.lootmatrix.customui.atmosphere.AtmospherePreset;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Network packet for the atmosphere system (server → client).
 * APPLY: sends a complete AtmospherePreset for the client to activate.
 * CLEAR: signals the client to fade out the current atmosphere.
 */
public class AtmospherePacket {

    public enum Action {
        APPLY, CLEAR
    }

    private final Action action;
    private final AtmospherePreset preset; // null when CLEAR

    public AtmospherePacket(Action action, AtmospherePreset preset) {
        this.action = action;
        this.preset = preset;
    }

    /** Create a CLEAR packet. */
    public AtmospherePacket() {
        this(Action.CLEAR, null);
    }

    /** Decode constructor. */
    public AtmospherePacket(FriendlyByteBuf buf) {
        this.action = Action.values()[buf.readVarInt() % Action.values().length];
        if (this.action == Action.APPLY) {
            this.preset = AtmospherePreset.decode(buf);
        } else {
            this.preset = null;
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
        if (action == Action.APPLY && preset != null) {
            preset.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (action == Action.APPLY && preset != null) {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleAtmosphereApply",
                        new Class<?>[]{AtmospherePreset.class},
                        preset
                );
            } else {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleAtmosphereClear",
                        new Class<?>[]{},
                        new Object[]{}
                );
            }
        }));
        ctx.setPacketHandled(true);
    }
}
