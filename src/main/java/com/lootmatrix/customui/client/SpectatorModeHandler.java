package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for Spectator mode restrictions.
 * Prevents non-OP players from switching observation targets using number keys,
 * to prevent cheating behavior.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class SpectatorModeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpectatorModeHandler.class);

    // OP permission level required to switch spectator targets
    // Level 1 = basic op commands, Level 2 = command blocks, Level 3 = /ban etc, Level 4 = /stop etc
    private static final int REQUIRED_OP_LEVEL = 1;

    /**
     * Handle key press events to block spectator target switching for non-OP players.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(InputEvent.Key event) {
        // Only handle key press, not release
        if (event.getAction() != 1) { // 1 = GLFW_PRESS
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.gameMode == null) {
            return;
        }

        // Only active in Spectator mode
        if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
            return;
        }

        // Check if a hotbar key was pressed (1-9)
        boolean isHotbarKey = false;
        for (int i = 0; i < 9; i++) {
            if (mc.options.keyHotbarSlots[i].matches(event.getKey(), event.getScanCode())) {
                isHotbarKey = true;
                break;
            }
        }

        if (!isHotbarKey) {
            return;
        }

        // Check if player has OP permissions
        // On client side, we use hasPermissions() which checks the permission level
        // sent from server via ClientboundLoginPacket
        if (!player.hasPermissions(REQUIRED_OP_LEVEL)) {
            // Key blocking is handled by SpectatorKeybindMixin
            // This handler provides additional logging for debugging
            LOGGER.debug("[SpectatorModeHandler] Non-OP player attempted spectator target switch");
        }
    }

}


