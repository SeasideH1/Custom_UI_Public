package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Client → Server network probe. The server echoes it straight back as a
 * {@link NetPongPacket} carrying its current average tick time, so one tiny
 * round trip feeds RTT / jitter / packet-loss / TPS metrics on the client.
 */
public class NetPingPacket {

    private final int sequence;
    private final long clientNanos;

    public NetPingPacket(int sequence, long clientNanos) {
        this.sequence = sequence;
        this.clientNanos = clientNanos;
    }

    public NetPingPacket(FriendlyByteBuf buf) {
        this.sequence = buf.readVarInt();
        this.clientNanos = buf.readLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(sequence);
        buf.writeLong(clientNanos);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null || sender.getServer() == null) return;
            float averageTickMs = sender.getServer().getAverageTickTime();
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> sender),
                    new NetPongPacket(sequence, clientNanos, averageTickMs));
        });
        ctx.setPacketHandled(true);
    }
}
