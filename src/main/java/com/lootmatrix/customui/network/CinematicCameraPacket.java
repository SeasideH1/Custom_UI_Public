package com.lootmatrix.customui.network;

import com.lootmatrix.customui.cinematic.CameraPath;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Network packet that sends a complete cinematic camera path from server to client.
 * When action=START, contains the full path data.
 * When action=STOP, signals the client to stop the current cinematic.
 */
public class CinematicCameraPacket {

    public enum Action {
        START, STOP
    }

    private final Action action;
    private final CameraPath path; // null when STOP

    public CinematicCameraPacket(Action action, CameraPath path) {
        this.action = action;
        this.path = path;
    }

    /** Create a STOP packet. */
    public CinematicCameraPacket() {
        this(Action.STOP, null);
    }

    /** Decode constructor. */
    public CinematicCameraPacket(FriendlyByteBuf buf) {
        this.action = Action.values()[buf.readVarInt() % Action.values().length];
        if (this.action == Action.START) {
            this.path = CameraPath.decode(buf);
        } else {
            this.path = null;
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(action.ordinal());
        if (action == Action.START && path != null) {
            path.encode(buf);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (action == Action.START && path != null) {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleCinematicStart",
                        new Class<?>[]{CameraPath.class},
                        path
                );
            } else {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleCinematicStop",
                        new Class<?>[]{},
                        new Object[]{}
                );
            }
        }));
        ctx.setPacketHandled(true);
    }
}
