package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Handles custom name tag rendering for players with TeamGlow effect.
 * When a player has the team glow effect:
 * - Name tag is rendered in green color (0x55FF55)
 * - Overrides team color completely
 * - Not affected by sneaking transparency
 * - No shadow
 *
 * When the player does NOT have team glow effect:
 * - Vanilla name tag rendering is used (respects team color, sneaking, etc.)
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class TeamGlowNameTagRenderer {

    // Green color for name tag (RGB)
    private static final int GREEN_COLOR = 0x55FF55;

    /**
     * Intercept name tag rendering for players with team glow effect.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // Only apply custom rendering when player has TeamGlow effect
        if (!TeamGlowRenderer.shouldRenderNameTag(player)) {
            // Let vanilla handle the rendering (respects team color, sneaking, etc.)
            return;
        }

        // Deny vanilla name tag rendering (including team color & sneaking transparency)
        // RenderNameTagEvent is not cancelable, use setResult(DENY) instead
        event.setResult(Event.Result.DENY);

        // Render our custom green name tag
        renderGreenNameTag(player, event);
    }

    /**
     * Render a custom green name tag for the player.
     * - Green color (0x55FF55)
     * - No shadow
     * - Not affected by sneaking
     * - Full brightness
     * - Visible through walls (SEE_THROUGH mode)
     */
    private static void renderGreenNameTag(Player player, RenderNameTagEvent event) {
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffer = event.getMultiBufferSource();
        EntityRenderDispatcher dispatcher = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher();
        Font font = net.minecraft.client.Minecraft.getInstance().font;

        poseStack.pushPose();

        // Position above player's head (same as vanilla)
        float yOffset = player.getBbHeight() + 0.5F;
        poseStack.translate(0.0D, yOffset, 0.0D);

        // Face the camera (billboard)
        poseStack.mulPose(dispatcher.cameraOrientation());

        // Scale (same as vanilla: -0.025 for X/Y, 0.025 for Z)
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        Matrix4f matrix = poseStack.last().pose();
        Component name = player.getDisplayName();

        // Center the text
        float halfWidth = font.width(name) / 2.0F;

        // Background color (semi-transparent black)
        int bgColor = 0x40000000;

        // Draw the name tag in green, visible through walls with full opacity
        // Use SEE_THROUGH mode so it's always visible regardless of occlusion
        font.drawInBatch(
                name,
                -halfWidth,
                0,
                GREEN_COLOR,
                false,              // No shadow
                matrix,
                buffer,
                Font.DisplayMode.SEE_THROUGH,  // Visible through walls
                bgColor,            // Background
                0xF000F0            // Full brightness (packed light)
        );

        poseStack.popPose();
    }
}
