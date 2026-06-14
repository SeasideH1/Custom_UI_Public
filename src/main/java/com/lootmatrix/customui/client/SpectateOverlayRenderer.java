package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Renders "观战" (Spectating) text when the player is spectating another player.
 * Positioned above the health/experience bar area, centered horizontally,
 * below the actionbar text.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class SpectateOverlayRenderer {

    private static final String SPECTATING_TEXT = "观战";
    private static final int TEXT_COLOR = 0xAAFFFFFF; // Semi-transparent white
    private static final int SHADOW_COLOR = 0x44000000;

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.EXPERIENCE_BAR.type()) return;
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || mc.options.hideGui) return;

        // Only show when spectating another entity
        if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) return;

        Entity camera = mc.getCameraEntity();
        if (camera == null || camera == player) return;

        // Only show when spectating another player
        if (!(camera instanceof Player spectatedPlayer)) return;

        GuiGraphics gfx = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Position: centered horizontally, above health/exp bar, below actionbar
        // Actionbar is at screenH - 59 (roughly)
        // Health bar is at bottom - 22 (roughly)
        // Place "观战" between them
        int textWidth = font.width(SPECTATING_TEXT);
        int x = (screenW - textWidth) / 2;
        int y = screenH - 59; // Just below actionbar position

        // Draw text with shadow
        gfx.drawString(font, SPECTATING_TEXT, x, y, TEXT_COLOR, true);

        // Draw the spectated player's name below
        String playerName = spectatedPlayer.getGameProfile().getName();
        int nameWidth = font.width(playerName);
        int nameX = (screenW - nameWidth) / 2;
        gfx.drawString(font, playerName, nameX, y + 10, 0x88FFFFFF, true);
    }
}
