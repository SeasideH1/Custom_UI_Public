package com.lootmatrix.customui.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client HUD playback control. Tiny packet: template definitions are
 * already cached client-side, only the playback state travels.
 *
 * PLAY    start (or restart) a template instance at elapsedTicks
 * SEEK    jump the active instance to elapsedTicks (keyframe-stage adjustment)
 * STOP    stop one template instance
 * STOP_ALL stop everything
 * EDITOR  open the HUD editor (templateId may be empty for a blank template)
 * GUI_OPEN  open a screenType="gui" template as an interactive screen
 * GUI_CLOSE close the GUI screen (templateId may be empty for "any")
 * GUI_ACTIVATE / GUI_DEACTIVATE  allow/forbid opening a GUI with interact keys
 */
public class HudPlayPacket {

    public enum Action { PLAY, SEEK, STOP, STOP_ALL, EDITOR, GUI_OPEN, GUI_CLOSE, GUI_ACTIVATE, GUI_DEACTIVATE }

    private final Action action;
    private final String templateId;
    private final int elapsedTicks;

    public HudPlayPacket(Action action, String templateId, int elapsedTicks) {
        this.action = action;
        this.templateId = templateId;
        this.elapsedTicks = elapsedTicks;
    }

    public HudPlayPacket(FriendlyByteBuf buf) {
        this.action = Action.values()[buf.readByte() % Action.values().length];
        this.templateId = buf.readUtf(256);
        this.elapsedTicks = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeUtf(templateId, 256);
        buf.writeVarInt(elapsedTicks);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                PacketReflectionExecutor.invokeStatic(
                        "com.lootmatrix.customui.network.PacketClientBridge",
                        "handleHudPlay",
                        new Class<?>[]{int.class, String.class, int.class},
                        action.ordinal(), templateId, elapsedTicks
                )));
        ctx.setPacketHandled(true);
    }
}
