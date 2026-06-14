package com.lootmatrix.customui.network;

import com.lootmatrix.customui.hud.HudTemplateRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: delete a world-storage HUD template from the editor
 * library. Requires permission level 2, mirroring template uploads; datapack
 * templates are read-only and rejected server-side.
 */
public class HudTemplateDeletePacket {

    private final String templateId;

    public HudTemplateDeletePacket(String templateId) {
        this.templateId = templateId;
    }

    public HudTemplateDeletePacket(FriendlyByteBuf buf) {
        this.templateId = buf.readUtf(256);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(templateId, 256);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                sender.sendSystemMessage(Component.literal("\u00a7c[CustomUI] No permission to delete HUD templates"));
                return;
            }
            String error = HudTemplateRegistry.getInstance().deleteWorldTemplate(templateId);
            if (error != null) {
                sender.sendSystemMessage(Component.literal("\u00a7c[CustomUI] Delete failed: " + error));
            } else {
                sender.sendSystemMessage(Component.literal("\u00a7a[CustomUI] HUD template deleted: " + templateId));
            }
        });
        ctx.setPacketHandled(true);
    }
}
