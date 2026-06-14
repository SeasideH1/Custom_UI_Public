package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles registration of custom GUI overlays.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class HUDOverlayHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HUDOverlayHandler.class);

    private static final CustomHealthOverlay CUSTOM_HEALTH_OVERLAY = new CustomHealthOverlay();

    /**
     * Registers custom GUI overlays.
     */
    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        LOGGER.info("[HUDOverlayHandler] Registering GUI overlays...");

        // Register custom health overlay at the position above the hotbar
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "custom_health_bar", CUSTOM_HEALTH_OVERLAY);

        // Register damage number overlay
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "damage_numbers", DamageNumberRenderer.getInstance());
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "kill_icon", KillIconOverlay.getInstance());

        // Register damage indicator overlay (directional indicators and vignette)
        // Register at a high layer so vignette renders on top of other HUD elements
        event.registerAbove(VanillaGuiOverlay.VIGNETTE.id(), "damage_indicator", DamageIndicatorOverlay.getInstance());

        // Register scoreboard overlay (top center of screen) - also renders kill messages
        event.registerAbove(VanillaGuiOverlay.PLAYER_HEALTH.id(), "scoreboard_overlay", ScoreboardOverlayRenderer.getInstance());

        // Register objective overlay (below scoreboard, above titles)
        event.registerAbove(VanillaGuiOverlay.PLAYER_HEALTH.id(), "objective_overlay", ObjectiveOverlayRenderer.getInstance());

        // Register animated HUD template overlay (data-driven, server-triggered playback)
        event.registerAbove(VanillaGuiOverlay.PLAYER_HEALTH.id(), "hud_templates",
                com.lootmatrix.customui.client.hud.HudTemplateOverlayRenderer.getInstance());

        LOGGER.info("[HUDOverlayHandler] All overlays registered successfully!");
    }
}
