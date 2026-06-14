package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles hiding vanilla HUD elements.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class VanillaHUDHider {

    /**
     * Cancels rendering of vanilla HUD elements we want to hide.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        // Get the overlay being rendered
        var overlay = event.getOverlay();

        // Hide player health (hearts)
        if (overlay == VanillaGuiOverlay.PLAYER_HEALTH.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide armor bar
        if (overlay == VanillaGuiOverlay.ARMOR_LEVEL.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide food/hunger bar
        if (overlay == VanillaGuiOverlay.FOOD_LEVEL.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide air/oxygen bar
        if (overlay == VanillaGuiOverlay.AIR_LEVEL.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide experience bar
        if (overlay == VanillaGuiOverlay.EXPERIENCE_BAR.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide mount health
        if (overlay == VanillaGuiOverlay.MOUNT_HEALTH.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide jump bar (when riding)
        if (overlay == VanillaGuiOverlay.JUMP_BAR.type()) {
            event.setCanceled(true);
        }

        // Hide item name tooltip
        if (overlay == VanillaGuiOverlay.ITEM_NAME.type()) {
            event.setCanceled(true);
        }
    }
}
