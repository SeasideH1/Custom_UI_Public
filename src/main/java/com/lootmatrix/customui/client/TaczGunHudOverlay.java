package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;

/**
 * Custom TACZ Gun HUD overlay for Adventure mode.
 * Layout (left to right): [Separator "|"] [Current Ammo (large white)] [Reserve Ammo (small gray)] [Fire Mode Icon]
 * This replaces the default TACZ HUD in Adventure mode.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class TaczGunHudOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaczGunHudOverlay.class);

    // Debug mode - set to true to see debug output
    private static final boolean DEBUG_MODE = true;
    private static long lastDebugTime = 0;
    private static final long DEBUG_INTERVAL = 2000; // Debug every 2 seconds

    // TACZ fire mode textures
    private static final ResourceLocation FIRE_MODE_SEMI = new ResourceLocation("tacz", "textures/hud/fire_mode_semi.png");
    private static final ResourceLocation FIRE_MODE_AUTO = new ResourceLocation("tacz", "textures/hud/fire_mode_auto.png");
    private static final ResourceLocation FIRE_MODE_BURST = new ResourceLocation("tacz", "textures/hud/fire_mode_burst.png");

    // Number formats
    private static final DecimalFormat CURRENT_AMMO_FORMAT = new DecimalFormat("000");
    private static final DecimalFormat RESERVE_AMMO_FORMAT = new DecimalFormat("0000");

    // HUD layout constants
    private static final float CURRENT_AMMO_SCALE = 1.5f;      // Large scale for current ammo
    private static final float RESERVE_AMMO_SCALE = 0.8f;      // Small scale for reserve ammo
    private static final int FIRE_MODE_ICON_SIZE = 10;         // Size of fire mode icon
    private static final int HUD_BOTTOM_PADDING = 43;          // Distance from bottom (match TACZ)
    private static final int HUD_RIGHT_PADDING = 75;           // Distance from right (match TACZ)
    private static final int ELEMENT_SPACING = 4;              // Spacing between elements
    private static final int SEPARATOR_WIDTH = 1;              // Width of separator bar ("|")
    private static final int SEPARATOR_HEIGHT = 18;            // Height of separator bar

    // Colors (ARGB)
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GRAY = 0xFFAAAAAA;
    private static final int COLOR_RED = 0xFFFF5555;           // Low ammo color
    private static final int COLOR_CYAN = 0xFF55FFFF;          // Dummy ammo color
    private static final int COLOR_YELLOW = 0xFFFFFF55;        // Inventory ammo color
    private static final int COLOR_DEBUG = 0xFF00FF00;         // Green for debug

    // Track if we've rendered at least once (for debugging)
    private static boolean hasRendered = false;

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        // This overlay is now disabled - the functionality is in AdventureHotbarRenderer.renderItemInfoDisplay()
        // The item info display showing fire mode, ammo, etc. is handled by AdventureHotbarRenderer
        // This method intentionally does nothing
    }

    /**
     * Debug logging helper (kept for potential future debugging).
     */
    @SuppressWarnings("unused")
    private static void debugLog(String message) {
        if (!DEBUG_MODE) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDebugTime > DEBUG_INTERVAL) {
            LOGGER.info("[TaczGunHudOverlay] " + message);
            lastDebugTime = currentTime;
        }
    }
}



