package com.lootmatrix.customui.cinematic;

import com.lootmatrix.customui.Main;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side Forge event hooks for the cinematic camera system.
 * Intercepts camera angle computation, FOV, HUD rendering and ticking
 * to drive the cinematic camera engine.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class CinematicCameraHook {

    /** Tick the camera engine each client tick. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        CinematicCameraEngine engine = CinematicCameraEngine.getInstance();
        if (engine.isPlaying()) {
            engine.tick();
        }
    }

    /**
     * Override camera angles (yaw, pitch, roll) during cinematic playback.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        CinematicCameraEngine engine = CinematicCameraEngine.getInstance();
        if (!engine.isPlaying()) return;

        float pt = (float) event.getPartialTick();

        // Override angles
        event.setYaw(engine.getInterpolatedYaw(pt));
        event.setPitch(engine.getInterpolatedPitch(pt));
        event.setRoll(engine.getInterpolatedRoll(pt));

        // Override camera position via entity position hack:
        // Move the camera entity to the desired position.
        // The Camera class uses the entity's position + eye height as basis.
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 targetPos = engine.getInterpolatedPos(pt);

        // Use reflection instead of a mixin accessor so this stays stable across dev/prod mappings.
        CameraAccessBridge.setPosition(camera, targetPos);
        CameraAccessBridge.setDetached(camera, engine.shouldShowSelf());
    }

    /**
     * Override FOV during cinematic playback.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        CinematicCameraEngine engine = CinematicCameraEngine.getInstance();
        if (!engine.isPlaying()) return;

        float pt = (float) event.getPartialTick();
        event.setFOV(engine.getInterpolatedFov(pt));
    }

    /**
     * Hide HUD elements when cinematic hideHud flag is set.
     * Mimics spectator mode behavior: hides hotbar, health, armor, food, etc.,
     * but keeps titles, chat, and other overlays visible.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Pre event) {
        CinematicCameraEngine engine = CinematicCameraEngine.getInstance();
        if (!engine.shouldHideHud()) return;

        var overlay = event.getOverlay();

        // Hide hotbar (includes held item)
        if (overlay == VanillaGuiOverlay.HOTBAR.type()) {
            event.setCanceled(true);
            return;
        }

        // Hide crosshair
        if (overlay == VanillaGuiOverlay.CROSSHAIR.type()) {
            event.setCanceled(true);
            return;
        }

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
            return;
        }

        // Hide item name tooltip
        if (overlay == VanillaGuiOverlay.ITEM_NAME.type()) {
            event.setCanceled(true);
        }

        // Note: We intentionally DO NOT hide:
        // - VanillaGuiOverlay.TITLE_TEXT (titles and subtitles)
        // - VanillaGuiOverlay.CHAT_PANEL (chat messages)
        // - VanillaGuiOverlay.BOSS_EVENT_PROGRESS (boss bars)
        // - VanillaGuiOverlay.DEBUG_TEXT (F3 debug screen)
        // - VanillaGuiOverlay.SCOREBOARD_SIDEBAR (scoreboard)
        // This matches spectator mode behavior
    }
}
