package com.lootmatrix.customui.network;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet to display a custom image title on the client.
 */
public class TitleImagePacket {

    public final String iconPath;
    public final int size;
    public final float alpha;
    public final float offsetX;
    public final float offsetY;
    public final long fadeInMs;
    public final long stayMs;
    public final long fadeOutMs;
    /** 9-grid screen anchor ordinal ({@code HudAnchor}); -1 = legacy top-center layout. */
    public final int anchorId;
    /** 9-grid image origin ordinal; -1 = legacy (horizontal center, top aligned). */
    public final int originId;

    public TitleImagePacket(String iconPath, int size, float alpha,
                            float offsetX, float offsetY,
                            long fadeInMs, long stayMs, long fadeOutMs,
                            int anchorId, int originId) {
        this.iconPath = iconPath;
        this.size = size;
        this.alpha = alpha;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.fadeInMs = fadeInMs;
        this.stayMs = stayMs;
        this.fadeOutMs = fadeOutMs;
        this.anchorId = anchorId;
        this.originId = originId;
    }

    public TitleImagePacket(FriendlyByteBuf buf) {
        this.iconPath = buf.readUtf(256);
        this.size = buf.readVarInt();
        this.alpha = buf.readFloat();
        this.offsetX = buf.readFloat();
        this.offsetY = buf.readFloat();
        this.fadeInMs = buf.readVarLong();
        this.stayMs = buf.readVarLong();
        this.fadeOutMs = buf.readVarLong();
        this.anchorId = buf.readByte();
        this.originId = buf.readByte();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(iconPath, 256);
        buf.writeVarInt(size);
        buf.writeFloat(alpha);
        buf.writeFloat(offsetX);
        buf.writeFloat(offsetY);
        buf.writeVarLong(fadeInMs);
        buf.writeVarLong(stayMs);
        buf.writeVarLong(fadeOutMs);
        buf.writeByte(anchorId);
        buf.writeByte(originId);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            PacketReflectionExecutor.invokeStatic(
                    "com.lootmatrix.customui.network.PacketClientBridge",
                    "handleTitleImage",
                    new Class<?>[]{
                            String.class, int.class, float.class, float.class, float.class,
                            long.class, long.class, long.class,
                            int.class, int.class
                    },
                    iconPath, size, alpha, offsetX, offsetY,
                    fadeInMs, stayMs, fadeOutMs, anchorId, originId
            );
        }));
        ctx.setPacketHandled(true);
    }
}

