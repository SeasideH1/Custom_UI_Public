package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client entity field sync for HUD template bindings.
 *
 * DEFINE: full binding table + per-player current string values.
 * DELTA:  changed values referenced by table index (varint), batched per tick.
 */
public class HudEntitySyncPacket {

    public static final byte MODE_DEFINE = 0;
    public static final byte MODE_DELTA = 1;

    private static final int MAX_VALUE_LEN = 512;

    private final byte mode;
    private final List<String> keys;
    private final int[] indices;
    private final List<String> values;

    public static HudEntitySyncPacket define(List<String> keys, List<String> values) {
        return new HudEntitySyncPacket(MODE_DEFINE, keys, new int[0], values);
    }

    public static HudEntitySyncPacket delta(int[] indices, List<String> values) {
        return new HudEntitySyncPacket(MODE_DELTA, List.of(), indices, values);
    }

    private HudEntitySyncPacket(byte mode, List<String> keys, int[] indices, List<String> values) {
        this.mode = mode;
        this.keys = keys;
        this.indices = indices;
        this.values = values;
    }

    public HudEntitySyncPacket(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        if (mode == MODE_DEFINE) {
            int count = buf.readVarInt();
            this.keys = new ArrayList<>(count);
            this.values = new ArrayList<>(count);
            this.indices = new int[0];
            for (int i = 0; i < count; i++) {
                keys.add(buf.readUtf(256));
                values.add(buf.readUtf(MAX_VALUE_LEN));
            }
        } else {
            int count = buf.readVarInt();
            this.keys = List.of();
            this.indices = new int[count];
            this.values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                indices[i] = buf.readVarInt();
                values.add(buf.readUtf(MAX_VALUE_LEN));
            }
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        if (mode == MODE_DEFINE) {
            buf.writeVarInt(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                buf.writeUtf(keys.get(i), 256);
                buf.writeUtf(values.get(i), MAX_VALUE_LEN);
            }
        } else {
            buf.writeVarInt(indices.length);
            for (int i = 0; i < indices.length; i++) {
                buf.writeVarInt(indices[i]);
                buf.writeUtf(values.get(i), MAX_VALUE_LEN);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (mode == MODE_DEFINE) {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudEntityDefine",
                        new Class<?>[]{List.class, List.class},
                        keys, values
                );
            } else {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudEntityDelta",
                        new Class<?>[]{int[].class, List.class},
                        indices, values
                );
            }
        }));
        ctx.setPacketHandled(true);
    }
}
