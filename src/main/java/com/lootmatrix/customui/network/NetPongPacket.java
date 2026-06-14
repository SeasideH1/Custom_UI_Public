package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client echo reply for {@link NetPingPacket}; carries the server's
 * average tick duration (ms) so TPS rides along with the RTT probe.
 */
public class NetPongPacket {

    private final int sequence;
    private final long clientNanos;
    private final float serverTickMs;

    public NetPongPacket(int sequence, long clientNanos, float serverTickMs) {
        this.sequence = sequence;
        this.clientNanos = clientNanos;
        this.serverTickMs = serverTickMs;
    }

    public NetPongPacket(FriendlyByteBuf buf) {
        this.sequence = buf.readVarInt();
        this.clientNanos = buf.readLong();
        this.serverTickMs = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(sequence);
        buf.writeLong(clientNanos);
        buf.writeFloat(serverTickMs);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleNetPong",
                        new Class<?>[]{int.class, long.class, float.class},
                        sequence, clientNanos, serverTickMs
                )));
        ctx.setPacketHandled(true);
    }
}
