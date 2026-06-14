package com.lootmatrix.customui.network;

import com.lootmatrix.customui.hud.GuiSessionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server GUI template interactions.
 *
 * OPEN   the player opened a GUI (key press / chained openTemplate)
 * CLICK  the player clicked an interactive element; the server re-validates
 *        the element's scoreboard condition before running any function
 * CLOSE  the player closed the GUI (ESC / close key / close button)
 */
public class GuiInteractPacket {

    public enum Action { OPEN, CLICK, CLOSE }

    private final Action action;
    private final String templateId;
    private final String elementId;
    /** Client-side condition verdict (informational; the server re-evaluates). */
    private final boolean clientPass;

    private GuiInteractPacket(Action action, String templateId, String elementId, boolean clientPass) {
        this.action = action;
        this.templateId = templateId;
        this.elementId = elementId;
        this.clientPass = clientPass;
    }

    public static GuiInteractPacket open(String templateId) {
        return new GuiInteractPacket(Action.OPEN, templateId, "", true);
    }

    public static GuiInteractPacket click(String templateId, String elementId, boolean clientPass) {
        return new GuiInteractPacket(Action.CLICK, templateId, elementId, clientPass);
    }

    public static GuiInteractPacket close(String templateId) {
        return new GuiInteractPacket(Action.CLOSE, templateId, "", true);
    }

    public GuiInteractPacket(FriendlyByteBuf buf) {
        this.action = Action.values()[Math.floorMod(buf.readByte(), Action.values().length)];
        this.templateId = buf.readUtf(256);
        this.elementId = buf.readUtf(256);
        this.clientPass = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeUtf(templateId, 256);
        buf.writeUtf(elementId, 256);
        buf.writeBoolean(clientPass);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            switch (action) {
                case OPEN -> {
                    // Activation gate: a stale/cheating client gets the GUI closed again
                    if (!GuiSessionManager.onOpen(sender, templateId)) {
                        ModNetworkHandler.INSTANCE.send(
                                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sender),
                                new HudPlayPacket(HudPlayPacket.Action.GUI_CLOSE, templateId, 0));
                    }
                }
                case CLICK -> GuiSessionManager.onClick(sender, templateId, elementId);
                case CLOSE -> GuiSessionManager.onClose(sender, templateId);
            }
        });
        ctx.setPacketHandled(true);
    }
}
