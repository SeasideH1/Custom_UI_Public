package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client scoreboard value sync for HUD template bindings.
 *
 * DEFINE: full binding table (objective/holder pairs) + the receiving player's
 *         current values. Sent on login and whenever the binding set changes.
 * DELTA:  only changed values, referenced by table index (varint), batched
 *         once per tick. Strings never travel after the DEFINE.
 */
public class HudScoreboardSyncPacket {

    public static final byte MODE_DEFINE = 0;
    public static final byte MODE_DELTA = 1;

    private final byte mode;
    // DEFINE: keys ("objective\u0000holder") aligned with values
    // DELTA: indices aligned with values
    private final List<String> keys;
    private final int[] indices;
    private final int[] values;

    public static HudScoreboardSyncPacket define(List<String> keys, int[] values) {
        return new HudScoreboardSyncPacket(MODE_DEFINE, keys, new int[0], values);
    }

    public static HudScoreboardSyncPacket delta(int[] indices, int[] values) {
        return new HudScoreboardSyncPacket(MODE_DELTA, List.of(), indices, values);
    }

    private HudScoreboardSyncPacket(byte mode, List<String> keys, int[] indices, int[] values) {
        this.mode = mode;
        this.keys = keys;
        this.indices = indices;
        this.values = values;
    }

    public HudScoreboardSyncPacket(FriendlyByteBuf buf) {
        this.mode = buf.readByte();
        if (mode == MODE_DEFINE) {
            int count = buf.readVarInt();
            this.keys = new ArrayList<>(count);
            this.values = new int[count];
            this.indices = new int[0];
            for (int i = 0; i < count; i++) {
                keys.add(buf.readUtf(256));
                values[i] = buf.readVarInt();
            }
        } else {
            int count = buf.readVarInt();
            this.keys = List.of();
            this.indices = new int[count];
            this.values = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = buf.readVarInt();
                values[i] = buf.readVarInt();
            }
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(mode);
        if (mode == MODE_DEFINE) {
            buf.writeVarInt(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                buf.writeUtf(keys.get(i), 256);
                buf.writeVarInt(values[i]);
            }
        } else {
            buf.writeVarInt(indices.length);
            for (int i = 0; i < indices.length; i++) {
                buf.writeVarInt(indices[i]);
                buf.writeVarInt(values[i]);
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (mode == MODE_DEFINE) {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudScoreboardDefine",
                        new Class<?>[]{List.class, int[].class},
                        keys, values
                );
            } else {
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudScoreboardDelta",
                        new Class<?>[]{int[].class, int[].class},
                        indices, values
                );
            }
        }));
        ctx.setPacketHandled(true);
    }
}
