package com.lootmatrix.customui.network;

import com.lootmatrix.customui.hud.HudTemplateRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: persist a template edited in the HUD editor into world
 * storage (singleplayer save folder or the dedicated/integrated server world).
 * Requires permission level 2; size-capped server-side.
 */
public class HudTemplateUploadPacket {

    private final String templateJson;

    public HudTemplateUploadPacket(String templateJson) {
        this.templateJson = templateJson;
    }

    public HudTemplateUploadPacket(FriendlyByteBuf buf) {
        this.templateJson = buf.readUtf(HudTemplateRegistry.MAX_TEMPLATE_BYTES);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(templateJson, HudTemplateRegistry.MAX_TEMPLATE_BYTES);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                sender.sendSystemMessage(Component.literal("\u00a7c[CustomUI] No permission to save HUD templates"));
                return;
            }
            String error = HudTemplateRegistry.getInstance().saveWorldTemplate(templateJson);
            if (error != null) {
                sender.sendSystemMessage(Component.literal("\u00a7c[CustomUI] Save failed: " + error));
            } else {
                sender.sendSystemMessage(Component.literal("\u00a7a[CustomUI] HUD template saved to world storage"));
            }
        });
        ctx.setPacketHandled(true);
    }
}
