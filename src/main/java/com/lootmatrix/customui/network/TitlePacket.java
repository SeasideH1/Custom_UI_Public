package com.lootmatrix.customui.network;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet to display a custom title on the client.
 */
public class TitlePacket {

    public final String text;
    public final int color;
    public final float alpha;
    public final float scale;
    public final float offsetX;
    public final float offsetY;
    public final long fadeInMs;
    public final long stayMs;
    public final long fadeOutMs;
    public final int line;
    /** 9-grid screen anchor ordinal ({@code HudAnchor}); -1 = legacy top-center layout. */
    public final int anchorId;
    /** 9-grid text origin ordinal; -1 = legacy centered text. */
    public final int originId;

    public TitlePacket(String text, int color, float alpha, float scale,
                       float offsetX, float offsetY,
                       long fadeInMs, long stayMs, long fadeOutMs, int line,
                       int anchorId, int originId) {
        this.text = text;
        this.color = color;
        this.alpha = alpha;
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.fadeInMs = fadeInMs;
        this.stayMs = stayMs;
        this.fadeOutMs = fadeOutMs;
        this.line = line;
        this.anchorId = anchorId;
        this.originId = originId;
    }

    public TitlePacket(FriendlyByteBuf buf) {
        this.text = buf.readUtf(512);
        this.color = buf.readInt();
        this.alpha = buf.readFloat();
        this.scale = buf.readFloat();
        this.offsetX = buf.readFloat();
        this.offsetY = buf.readFloat();
        this.fadeInMs = buf.readVarLong();
        this.stayMs = buf.readVarLong();
        this.fadeOutMs = buf.readVarLong();
        this.line = buf.readVarInt();
        this.anchorId = buf.readByte();
        this.originId = buf.readByte();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(text, 512);
        buf.writeInt(color);
        buf.writeFloat(alpha);
        buf.writeFloat(scale);
        buf.writeFloat(offsetX);
        buf.writeFloat(offsetY);
        buf.writeVarLong(fadeInMs);
        buf.writeVarLong(stayMs);
        buf.writeVarLong(fadeOutMs);
        buf.writeVarInt(line);
        buf.writeByte(anchorId);
        buf.writeByte(originId);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            PacketReflectionExecutor.invokeStatic(
                    "com.lootmatrix.customui.network.PacketClientBridge",
                    "handleTitle",
                    new Class<?>[]{
                            String.class, int.class, float.class, float.class,
                            float.class, float.class,
                            long.class, long.class, long.class, int.class,
                            int.class, int.class
                    },
                    text, color, alpha, scale, offsetX, offsetY,
                    fadeInMs, stayMs, fadeOutMs, line, anchorId, originId
            );
        }));
        ctx.setPacketHandled(true);
    }
}

