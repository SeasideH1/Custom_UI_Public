package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.client.render.UIBlurRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws a shared frosted-glass blur behind the hotbar + health region each frame.
 * Uses UIBlurRenderer's current API: prepareBlur() → renderBlurRect(gfx, x,y,w,h, r,g,b,a).
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HUDBlurHandler {

    /**
     * Blur is intentionally disabled for now.
     * The current framebuffer path can leave an opaque dark block behind the HUD,
     * so keep the handler inert until the blur pass is redesigned.
     */
    private static final boolean DISABLED = true;

    /** Blur tint: semi-transparent dark overlay */
    private static final float TINT_R = 0.05f;
    private static final float TINT_G = 0.05f;
    private static final float TINT_B = 0.08f;
    private static final float TINT_A = 0.55f;

    /** Padding around the combined region */
    private static final int PAD = 4;

    private HUDBlurHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (DISABLED) return;
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (!shouldRenderHudBlur(mc, player)) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Hotbar: 182×22, right-aligned with CustomHotbarRenderer.HOTBAR_RIGHT_PADDING
        int hotbarW = 182;
        int hotbarH = 22;
        int hotbarX = screenW - hotbarW - CustomHotbarRenderer.HOTBAR_RIGHT_PADDING;
        int hotbarY = screenH - hotbarH;

        // Blur the hotbar region (with padding)
        float bx = hotbarX - PAD;
        float by = hotbarY - PAD;
        float bw = hotbarW + PAD * 2;
        float bh = hotbarH + PAD * 2;

        UIBlurRenderer.prepareBlur();
        UIBlurRenderer.renderBlurRect(event.getGuiGraphics(), bx, by, bw, bh,
                TINT_R, TINT_G, TINT_B, TINT_A);
    }

    private static boolean shouldRenderHudBlur(Minecraft mc, Player player) {
        if (player == null || mc.level == null || mc.screen != null || mc.options.hideGui || mc.gameMode == null) {
            return false;
        }
        GameType gameType = mc.gameMode.getPlayerMode();
        return gameType == GameType.SURVIVAL || gameType == GameType.ADVENTURE;
    }
}
