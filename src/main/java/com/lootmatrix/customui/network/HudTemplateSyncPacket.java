package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client HUD template definition sync.
 *
 * Definitions are pushed up-front (login / datapack reload / editor upload) and
 * cached on the client, so playback packets stay tiny and templates open with
 * zero latency. Large template sets are split into multiple chunks by byte
 * budget; the first chunk of a full sync carries the reset flag.
 */
public class HudTemplateSyncPacket {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(HudTemplateSyncPacket.class);
    /** Must cover {@link com.lootmatrix.customui.hud.HudTemplateRegistry#MAX_TEMPLATE_BYTES}. */
    public static final int MAX_SINGLE_JSON = com.lootmatrix.customui.hud.HudTemplateRegistry.MAX_TEMPLATE_BYTES;
    private static final int CHUNK_BYTE_BUDGET = 28000;

    private final boolean reset;
    private final List<String> templateJsons;

    public HudTemplateSyncPacket(boolean reset, List<String> templateJsons) {
        this.reset = reset;
        this.templateJsons = templateJsons;
    }

    /** Build chunked packets for a full sync (first chunk resets the client cache). */
    public static List<HudTemplateSyncPacket> full(List<String> jsons) {
        List<HudTemplateSyncPacket> packets = new ArrayList<>();
        List<String> chunk = new ArrayList<>();
        int chunkBytes = 0;
        boolean first = true;
        for (String json : jsons) {
            int size = json.getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_SINGLE_JSON) {
                LOGGER.warn("[CustomUI] Skipping oversized HUD template in sync ({} bytes > {})", size, MAX_SINGLE_JSON);
                continue;
            }
            if (chunkBytes + size > CHUNK_BYTE_BUDGET && !chunk.isEmpty()) {
                packets.add(new HudTemplateSyncPacket(first, chunk));
                first = false;
                chunk = new ArrayList<>();
                chunkBytes = 0;
            }
            chunk.add(json);
            chunkBytes += size;
        }
        if (!chunk.isEmpty() || first) {
            packets.add(new HudTemplateSyncPacket(first, chunk));
        }
        return packets;
    }

    /** Incremental single-template update. */
    public static HudTemplateSyncPacket merge(String json) {
        List<String> list = new ArrayList<>(1);
        list.add(json);
        return new HudTemplateSyncPacket(false, list);
    }

    public HudTemplateSyncPacket(FriendlyByteBuf buf) {
        this.reset = buf.readBoolean();
        int count = buf.readVarInt();
        this.templateJsons = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            templateJsons.add(buf.readUtf(MAX_SINGLE_JSON + 536));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(reset);
        buf.writeVarInt(templateJsons.size());
        for (String json : templateJsons) {
            buf.writeUtf(json, MAX_SINGLE_JSON + 536);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudTemplateSync",
                        new Class<?>[]{boolean.class, List.class},
                        reset, templateJsons
                )));
        ctx.setPacketHandled(true);
    }
}
