package com.lootmatrix.customui.network;

import com.lootmatrix.customui.ui.UITemplate;
import com.lootmatrix.customui.ui.UITemplateRegistry;
import com.lootmatrix.customui.ui.UIWidget;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Client → Server packet for UI widget actions.
 * The client sends which widget was interacted with; the server looks up
 * the action definition from the template (never trusts client-supplied commands).
 */
public class UIWidgetActionPacket {

    private static final Logger LOGGER = LoggerFactory.getLogger(UIWidgetActionPacket.class);

    public enum ActionType { CLICK, TOGGLE, SUBMIT, CLOSE }

    private final String templateId;
    private final String widgetId;
    private final ActionType actionType;
    private final String payload;

    public UIWidgetActionPacket(String templateId, String widgetId, ActionType actionType, String payload) {
        this.templateId = templateId;
        this.widgetId = widgetId;
        this.actionType = actionType;
        this.payload = payload;
    }

    /** Decode constructor. */
    public UIWidgetActionPacket(FriendlyByteBuf buf) {
        this.templateId = buf.readUtf(256);
        this.widgetId = buf.readUtf(256);
        this.actionType = ActionType.values()[Math.floorMod(buf.readByte(), ActionType.values().length)];
        this.payload = buf.readUtf(1024);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(templateId, 256);
        buf.writeUtf(widgetId, 256);
        buf.writeByte(actionType.ordinal());
        buf.writeUtf(payload, 1024);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            handleOnServer(player);
        });
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(ServerPlayer player) {
        UITemplateRegistry registry = UITemplateRegistry.getInstance();

        // Handle close action
        if (actionType == ActionType.CLOSE) {
            registry.closeSession(player);
            return;
        }

        // Validate session is active
        if (!registry.isActive(player, templateId)) {
            LOGGER.warn("[CustomUI] Player {} sent action for inactive template {}",
                    player.getName().getString(), templateId);
            return;
        }

        // Find template and widget
        UITemplate template = registry.getTemplate(templateId);
        if (template == null) return;

        UIWidget widget = template.findWidget(widgetId);
        if (widget == null) {
            LOGGER.warn("[CustomUI] Widget '{}' not found in template '{}'", widgetId, templateId);
            return;
        }

        // Get action from template definition (NOT from client data)
        UITemplate.UIAction action = null;
        switch (actionType) {
            case CLICK:  action = widget.onClick;  break;
            case TOGGLE: action = widget.onToggle; break;
            case SUBMIT: action = widget.onSubmit; break;
        }

        if (action == null || action.command.isEmpty()) return;

        // Check cooldown
        if (action.cooldownTicks > 0) {
            long currentTick = player.getServer().getTickCount();
            if (!registry.checkCooldown(player.getUUID(), templateId + ":" + widgetId,
                    action.cooldownTicks, currentTick)) {
                return;
            }
        }

        // Execute command with elevated permissions (template is server-defined)
        String command = action.command.replace("%value%", payload);

        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(2)
                .withSuppressedOutput();

        try {
            player.getServer().getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            LOGGER.error("[CustomUI] Failed to execute action command '{}': {}", command, e.getMessage());
        }

        // Close menu if configured
        if (action.closeOnExecute) {
            registry.closeSession(player);
            UITemplateSyncPacket closePacket = new UITemplateSyncPacket(UITemplateSyncPacket.Action.CLOSE);
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), closePacket);
        }
    }
}
