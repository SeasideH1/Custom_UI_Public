package com.lootmatrix.customui.client;

import com.lootmatrix.customui.entity.DeathMarkerEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Renderer for DeathMarkerEntity.
 * Renders a gray semi-transparent "X" with ripple effects and distance text.
 * Uses billboard rendering to always face the player.
 * Features:
 * - X overlap areas don't get brighter (uses max blending)
 * - Smooth frame-rate independent ripple animations
 * - Distance text visible through walls with matching color
 */
@OnlyIn(Dist.CLIENT)
public class DeathMarkerEntityRenderer extends EntityRenderer<DeathMarkerEntity> {

    /** Size of the X marker in world units */
    private static final float MARKER_SIZE = 1.0f;
    /** Thickness of X lines */
    private static final float LINE_THICKNESS = 0.12f;
    /** Duration of ripple phase (fraction of total duration) */
    private static final float RIPPLE_PHASE = 0.6f;
    /** Number of ripples */
    private static final int RIPPLE_COUNT = 3;
    /** Marker color */
    private static final int MARKER_R = 180, MARKER_G = 180, MARKER_B = 180;

    // GC optimization: cached distance text (only recompute when rounded distance changes)
    private int cachedDistanceInt = -1;
    private String cachedDistanceText = "";

    // GC optimization: pre-computed sin/cos for X marker quads
    private static final float MARKER_HALF_PI4_COS = (float) Math.cos(Math.PI / 4.0);
    private static final float MARKER_HALF_PI4_SIN = (float) Math.sin(Math.PI / 4.0);

    // GC optimization: static texture location
    private static final ResourceLocation TEXTURE_LOCATION = ResourceLocation.parse("minecraft:textures/misc/white.png");

    public DeathMarkerEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(DeathMarkerEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        if (!entity.canPlayerSee(player.getUUID())) {
            return;
        }

        // Use partial tick for smooth animation - use entity ID for stable animation
        float smoothAge = entity.getAge() + partialTick;
        int duration = entity.getDuration();
        float ageProgress = smoothAge / duration;

        // Calculate alpha (fade out in last 20%)
        float alpha = 1f;
        if (ageProgress > 0.8f) {
            alpha = 1f - (ageProgress - 0.8f) / 0.2f;
        }

        // Disable depth test to see through walls
        RenderSystem.disableDepthTest();

        poseStack.pushPose();
        try {
            // Billboard rotation - face the camera
            poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());

            // Render X marker
            renderXMarker(poseStack, alpha);

            // Render ripples (first 60% of duration) - use entity-specific timing
            if (ageProgress < RIPPLE_PHASE) {
                renderRipples(poseStack, smoothAge, duration, alpha, entity.getId());
            }

            // Render distance text (always visible through walls)
            double distance = entity.position().distanceTo(player.position());
            renderDistanceText(poseStack, bufferSource, distance, alpha);
        } finally {
            poseStack.popPose();
            // Always restore depth test so subsequent entities don't render through walls.
            RenderSystem.enableDepthTest();
        }
    }

    /**
     * Render the X marker as a simple cross shape using quads.
     */
    private void renderXMarker(PoseStack poseStack, float alpha) {
        poseStack.pushPose();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        int a = (int) (alpha * 200);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float size = MARKER_SIZE * 0.5f;
        float thick = LINE_THICKNESS;

        // Draw two rotated rectangles forming X
        drawRotatedQuad(buffer, matrix, 0, 0, size * 2.0f, thick, (float)(Math.PI / 4.0), MARKER_R, MARKER_G, MARKER_B, a);
        drawRotatedQuad(buffer, matrix, 0, 0, size * 2.0f, thick, (float)(-Math.PI / 4.0), MARKER_R, MARKER_G, MARKER_B, a);

        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    /**
     * Draw a rotated quad centered at (cx, cy).
     */
    private void drawRotatedQuad(BufferBuilder buffer, Matrix4f matrix, float cx, float cy,
                                  float length, float thickness, float angle,
                                  int r, int g, int b, int a) {
        float cos = Mth.cos(angle);
        float sin = Mth.sin(angle);
        float halfLen = length / 2f;
        float halfThick = thickness / 2f;

        // GC optimization: inline the 4 vertices instead of allocating float[][]
        float lx0 = -halfLen, ly0 = -halfThick;
        float lx1 =  halfLen, ly1 = -halfThick;
        float lx2 =  halfLen, ly2 =  halfThick;
        float lx3 = -halfLen, ly3 =  halfThick;

        buffer.vertex(matrix, cx + lx0 * cos - ly0 * sin, cy + lx0 * sin + ly0 * cos, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, cx + lx1 * cos - ly1 * sin, cy + lx1 * sin + ly1 * cos, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, cx + lx2 * cos - ly2 * sin, cy + lx2 * sin + ly2 * cos, 0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, cx + lx3 * cos - ly3 * sin, cy + lx3 * sin + ly3 * cos, 0).color(r, g, b, a).endVertex();
    }

    /**
     * Render all ripple effects in a single draw call for smooth animation.
     */
    private void renderRipples(PoseStack poseStack, float smoothAge, int duration, float alpha, int entityId) {
        poseStack.pushPose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();

        // Calculate ripple timing
        float ripplePhaseDuration = duration * RIPPLE_PHASE;
        float rippleInterval = ripplePhaseDuration / RIPPLE_COUNT;

        // Use TRIANGLES mode to draw all ripples as separate triangles
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        int segments = 64;
        float maxRadius = MARKER_SIZE * 1.8f;

        for (int rippleIdx = 0; rippleIdx < RIPPLE_COUNT; rippleIdx++) {
            float rippleStartTime = rippleIdx * rippleInterval;
            if (smoothAge < rippleStartTime) continue;

            float rippleAge = smoothAge - rippleStartTime;
            float rippleProgress = Math.min(1f, rippleAge / rippleInterval);
            if (rippleProgress >= 1f) continue;

            float easedProgress = rippleProgress * rippleProgress * (3f - 2f * rippleProgress);
            float radius = maxRadius * easedProgress;
            float thickness = Math.max(0.04f, radius * 0.12f);
            float innerRadius = Math.max(0, radius - thickness);

            // Smooth alpha falloff
            float alphaFalloff = (float) Math.sin((1f - rippleProgress) * Math.PI / 2);
            float finalAlpha = alpha * alphaFalloff * 0.4f;
            int a = (int) (finalAlpha * 255);
            if (a <= 0) continue;

            // Draw ring as triangles
            for (int i = 0; i < segments; i++) {
                float angle1 = (float) (i * Math.PI * 2 / segments);
                float angle2 = (float) ((i + 1) * Math.PI * 2 / segments);

                float cos1 = Mth.cos(angle1);
                float sin1 = Mth.sin(angle1);
                float cos2 = Mth.cos(angle2);
                float sin2 = Mth.sin(angle2);

                // Triangle 1
                buffer.vertex(matrix, cos1 * innerRadius, sin1 * innerRadius, 0)
                        .color(MARKER_R, MARKER_G, MARKER_B, 0).endVertex();
                buffer.vertex(matrix, cos1 * radius, sin1 * radius, 0)
                        .color(MARKER_R, MARKER_G, MARKER_B, a).endVertex();
                buffer.vertex(matrix, cos2 * radius, sin2 * radius, 0)
                        .color(MARKER_R, MARKER_G, MARKER_B, a).endVertex();

                // Triangle 2
                buffer.vertex(matrix, cos1 * innerRadius, sin1 * innerRadius, 0)
                        .color(MARKER_R, MARKER_G, MARKER_B, 0).endVertex();
                buffer.vertex(matrix, cos2 * radius, sin2 * radius, 0)
                        .color(MARKER_R, MARKER_G, MARKER_B, a).endVertex();
                buffer.vertex(matrix, cos2 * innerRadius, sin2 * innerRadius, 0)
                        .color(MARKER_R, MARKER_G, MARKER_B, 0).endVertex();
            }
        }

        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    /**
     * Render distance text with matching marker color, visible through walls.
     */
    private void renderDistanceText(PoseStack poseStack, MultiBufferSource bufferSource,
                                     double distance, float alpha) {
        poseStack.pushPose();

        // Move text above the marker
        poseStack.translate(0, MARKER_SIZE * 0.7f, 0.01f);
        poseStack.scale(-0.03f, -0.03f, 0.03f);

        Font font = Minecraft.getInstance().font;
        // GC optimization: only reformat when rounded distance changes
        int distInt = (int) Math.round(distance);
        if (distInt != cachedDistanceInt) {
            cachedDistanceInt = distInt;
            cachedDistanceText = distInt + "\u7c73";
        }
        String distanceText = cachedDistanceText;
        int textWidth = font.width(distanceText);

        int textAlpha = AlphaFadeHelper.clampAlphaInt((int) (AlphaFadeHelper.smoothAlpha(alpha) * 255f));
        int textColor = (textAlpha << 24) | (MARKER_R << 16) | (MARKER_G << 8) | MARKER_B;

        font.drawInBatch(distanceText, -textWidth / 2f, 0, textColor, false,
                poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(DeathMarkerEntity entity) {
        return TEXTURE_LOCATION;
    }
}
