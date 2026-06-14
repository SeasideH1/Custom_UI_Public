package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client: drop one HUD template definition from the client cache
 * (template deleted from world storage). A ~30 byte packet instead of a full
 * template re-sync.
 */
public class HudTemplateRemovePacket {

    private final String templateId;

    public HudTemplateRemovePacket(String templateId) {
        this.templateId = templateId;
    }

    public HudTemplateRemovePacket(FriendlyByteBuf buf) {
        this.templateId = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(templateId, 256);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudTemplateRemove",
                        new Class<?>[]{String.class},
                        templateId
                )));
        ctx.setPacketHandled(true);
    }
}
