package com.lootmatrix.customui.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Damage indicator overlay that shows:
 * 1. Directional damage indicators (red arc shapes pointing to damage source)
 * 2. Screen vignette effect when taking damage (red-black corners)
 */
public class DamageIndicatorOverlay implements IGuiOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(DamageIndicatorOverlay.class);

    // Static singleton instance - initialized immediately
    private static final DamageIndicatorOverlay INSTANCE = new DamageIndicatorOverlay();

    // List of active damage indicators
    private final List<DamageIndicator> activeIndicators = new ArrayList<>();

    // Vignette effect (damage flash)
    private float vignetteIntensity = 0.0f;
    private float vignetteTargetIntensity = 0.0f;
    private long lastVignetteTime = 0;
    private final Random random = new Random();

    // Random vignette offsets for irregular shape (damage flash)
    private final float[] vignetteOffsets = new float[16];

    // Health-based persistent vignette (low health warning)
    private float healthVignetteIntensity = 0.0f;
    private static final float HEALTH_VIGNETTE_THRESHOLD = 0.7f; // Start showing at 70% health


    // Last known health for detecting health recovery
    private float lastHealth = 20.0f;
    private boolean isRecovering = false;

    // Time tracking for proper delta time calculations
    private long lastRenderTime = 0;

    // Debug flag - set to true to force show vignette for testing
    private static final boolean DEBUG_FORCE_VIGNETTE = false;

    // Configuration
    private static final float INDICATOR_DURATION = 2.0f; // seconds
    private static final float FADE_DURATION = 0.5f; // seconds for fade out
    private static final float ARC_ANGLE = 20.0f; // 25 * 0.8 = 20 degrees (reduced by 20%)
    private static final float VIGNETTE_FADE_SPEED = 2.0f;
    private static final float VIGNETTE_FAST_FADE_SPEED = 8.0f; // When recovering

    /** Inner radius for directional indicators — read from CrosshairConfig. */
    private static float getIndicatorInnerRadius() {
        try {
            return com.lootmatrix.customui.config.CrosshairConfig.INSTANCE.indicatorInnerRadius.get().floatValue();
        } catch (Exception e) {
            return 60.0f;
        }
    }

    /** Outer radius for directional indicators — read from CrosshairConfig. */
    private static float getIndicatorOuterRadius() {
        try {
            return com.lootmatrix.customui.config.CrosshairConfig.INSTANCE.indicatorOuterRadius.get().floatValue();
        } catch (Exception e) {
            return 100.0f;
        }
    }

    public static DamageIndicatorOverlay getInstance() {
        return INSTANCE;
    }

    private DamageIndicatorOverlay() {
        LOGGER.info("[DamageIndicatorOverlay] Instance created");
        regenerateVignetteOffsets();
    }

    /**
     * Called when the player takes damage from a specific source location
     * @param sourcePos The world position of the damage source, or null if unknown
     * @param amount The damage amount
     */
    public void addDamageIndicator(Vec3 sourcePos, float amount) {
        // Only add directional indicator when source position is known
        if (sourcePos != null) {
            long currentTime = System.currentTimeMillis();
            activeIndicators.add(new DamageIndicator(sourcePos, currentTime));
        }

        // Trigger vignette effect regardless of source
        triggerVignette(amount);
    }

    /**
     * Triggers the vignette effect based on damage amount
     */
    public void triggerVignette(float damageAmount) {
        // Increase vignette intensity based on damage
        float addedIntensity = Mth.clamp(damageAmount / 10.0f, 0.1f, 0.5f);
        vignetteTargetIntensity = Mth.clamp(vignetteTargetIntensity + addedIntensity, 0.0f, 1.0f);

        // Immediately set current intensity for instant feedback
        vignetteIntensity = vignetteTargetIntensity;

        lastVignetteTime = System.currentTimeMillis();
        isRecovering = false;

        // Regenerate random offsets for irregular shape
        regenerateVignetteOffsets();
    }

    private void regenerateVignetteOffsets() {
        for (int i = 0; i < vignetteOffsets.length; i++) {
            vignetteOffsets[i] = 0.6f + random.nextFloat() * 0.8f; // 0.6 to 1.4
        }
    }

    // Game mode fade state for vignette
    private float gameModeAlpha = 1.0f;  // 1.0 in survival/adventure, fades to 0 in creative/spectator
    private static final float GAME_MODE_FADE_SPEED = 3.0f;  // Speed of fade when switching modes

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (BackgroundGuard.shouldSkip()) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.options.hideGui) {
            return;
        }

        // Calculate delta time in seconds
        long currentTime = System.currentTimeMillis();
        float deltaTime;
        if (lastRenderTime == 0) {
            deltaTime = 0.016f; // Default to ~60fps
        } else {
            deltaTime = (currentTime - lastRenderTime) / 1000.0f;
            // Clamp to prevent huge jumps
            deltaTime = Math.min(deltaTime, 0.1f);
        }
        lastRenderTime = currentTime;

        // Update game mode alpha - fade out in creative/spectator, fade in for survival/adventure
        boolean shouldShowVignette = true;
        if (mc.gameMode != null) {
            net.minecraft.world.level.GameType gameMode = mc.gameMode.getPlayerMode();
            if (gameMode == net.minecraft.world.level.GameType.CREATIVE ||
                gameMode == net.minecraft.world.level.GameType.SPECTATOR) {
                shouldShowVignette = false;
            }
        }

        // Smoothly fade game mode alpha
        float targetAlpha = shouldShowVignette ? 1.0f : 0.0f;
        float fadeSpeed = GAME_MODE_FADE_SPEED * deltaTime;
        if (gameModeAlpha < targetAlpha) {
            gameModeAlpha = Math.min(targetAlpha, gameModeAlpha + fadeSpeed);
        } else if (gameModeAlpha > targetAlpha) {
            gameModeAlpha = Math.max(targetAlpha, gameModeAlpha - fadeSpeed);
        }

        // Skip further rendering if fully faded out and no indicators
        if (gameModeAlpha <= 0.01f && activeIndicators.isEmpty()) {
            return;
        }

        // Check for health recovery
        float currentHealth = player.getHealth();
        if (currentHealth > lastHealth && lastHealth > 0) {
            isRecovering = true;
        }
        lastHealth = currentHealth;

        // Calculate health percentage
        float healthPercent = currentHealth / player.getMaxHealth();

        // Update and render health-based persistent vignette (low health warning)
        updateHealthVignette(healthPercent, deltaTime, shouldShowVignette);

        // Debug: force show vignette for testing
        if (DEBUG_FORCE_VIGNETTE) {
            // Force intensity to 0.5 for testing purposes
            float testIntensity = Math.max(healthVignetteIntensity, 0.5f);
            float savedIntensity = healthVignetteIntensity;
            healthVignetteIntensity = testIntensity;
            renderHealthVignette(guiGraphics, screenWidth, screenHeight);
            healthVignetteIntensity = savedIntensity;
        } else if (healthVignetteIntensity > 0.01f) {
            renderHealthVignette(guiGraphics, screenWidth, screenHeight);
        }

        // Update damage flash vignette
        updateVignette(deltaTime);

        // Render damage flash vignette (apply game mode alpha)
        if (vignetteIntensity > 0.01f && gameModeAlpha > 0.01f) {
            float healthBasedVignetteSize = 1.0f + (1.0f - healthPercent) * 0.5f;
            float healthBasedIntensityMultiplier = (1.0f + (1.0f - healthPercent) * 0.5f) * gameModeAlpha;
            renderVignette(guiGraphics, screenWidth, screenHeight, healthBasedVignetteSize, healthBasedIntensityMultiplier);
        }

        // Render damage indicators (pass partialTick for smooth rotation interpolation)
        renderDamageIndicators(guiGraphics, screenWidth, screenHeight, player, partialTick);
    }

    /**
     * Update health-based vignette intensity based on current health percentage.
     * Vignette appears when health is below 70% and disappears when health recovers to 70%.
     * Also fades out when in creative/spectator mode regardless of health.
     */
    private void updateHealthVignette(float healthPercent, float deltaTime, boolean shouldShow) {
        // Calculate target intensity based on health
        float targetIntensity;
        if (!shouldShow) {
            // In creative/spectator mode, fade out regardless of health
            targetIntensity = 0.0f;
        } else if (healthPercent >= HEALTH_VIGNETTE_THRESHOLD) {
            // Health is at or above 70%, no vignette
            targetIntensity = 0.0f;
        } else {
            // Health is below 70%, calculate intensity
            // At 70% health: intensity = 0, at 0% health: intensity = 1
            targetIntensity = (HEALTH_VIGNETTE_THRESHOLD - healthPercent) / HEALTH_VIGNETTE_THRESHOLD;
            targetIntensity = Mth.clamp(targetIntensity, 0.0f, 1.0f);
        }

        // Smoothly interpolate towards target
        // Fade out faster when switching to creative/spectator
        float lerpSpeed = shouldShow ?
            (targetIntensity > healthVignetteIntensity ? 5.0f : 3.0f) :
            GAME_MODE_FADE_SPEED;
        float lerpFactor = Math.min(1.0f, deltaTime * lerpSpeed);
        healthVignetteIntensity = healthVignetteIntensity + (targetIntensity - healthVignetteIntensity) * lerpFactor;
    }

    /**
     * Render health-based screen edge vignette effect.
     * Creates a dark vignette around the screen edges that intensifies as health decreases.
     * Only appears when health is below 70%.
     *
     * Uses GPU vertex color interpolation for smooth gradients with minimal draw calls.
     * Each edge is rendered as a single quad with different alpha at edge vs inner vertices,
     * allowing the GPU to smoothly interpolate the gradient.
     */
    private void renderHealthVignette(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        float intensity = healthVignetteIntensity;
        if (intensity < 0.01f) return;

        // Dark color for vignette (very dark red/black)
        float red = 0.05f;   // Almost black with slight red tint
        float green = 0f;
        float blue = 0f;

        // Calculate vignette thickness based on intensity
        // Larger base size: 12% at 70% health, up to 30% at 0% health (2.5x multiplier)
        float sizeMultiplier = 1.0f + intensity * 1.5f; // 1.0 to 2.5
        float maxThickness = Math.min(screenWidth, screenHeight) * 0.12f * sizeMultiplier;

        if (maxThickness <= 0) return;

        // Maximum alpha at screen edge based on intensity
        float edgeAlpha = intensity * 0.9f;

        // Setup batched rendering with BufferBuilder for better performance
        guiGraphics.pose().pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Render all four edges using GPU interpolated gradients
        // Each edge is a single quad with alpha gradient from edge (opaque) to inner (transparent)

        // Top edge: y=0 is opaque, y=maxThickness is transparent
        buffer.vertex(matrix, 0, 0, 0).color(red, green, blue, edgeAlpha).endVertex();
        buffer.vertex(matrix, 0, maxThickness, 0).color(red, green, blue, 0f).endVertex();
        buffer.vertex(matrix, screenWidth, maxThickness, 0).color(red, green, blue, 0f).endVertex();
        buffer.vertex(matrix, screenWidth, 0, 0).color(red, green, blue, edgeAlpha).endVertex();

        // Bottom edge: y=screenHeight is opaque, y=screenHeight-maxThickness is transparent
        buffer.vertex(matrix, 0, screenHeight - maxThickness, 0).color(red, green, blue, 0f).endVertex();
        buffer.vertex(matrix, 0, screenHeight, 0).color(red, green, blue, edgeAlpha).endVertex();
        buffer.vertex(matrix, screenWidth, screenHeight, 0).color(red, green, blue, edgeAlpha).endVertex();
        buffer.vertex(matrix, screenWidth, screenHeight - maxThickness, 0).color(red, green, blue, 0f).endVertex();

        // Left edge: x=0 is opaque, x=maxThickness is transparent
        buffer.vertex(matrix, 0, 0, 0).color(red, green, blue, edgeAlpha).endVertex();
        buffer.vertex(matrix, maxThickness, 0, 0).color(red, green, blue, 0f).endVertex();
        buffer.vertex(matrix, maxThickness, screenHeight, 0).color(red, green, blue, 0f).endVertex();
        buffer.vertex(matrix, 0, screenHeight, 0).color(red, green, blue, edgeAlpha).endVertex();

        // Right edge: x=screenWidth is opaque, x=screenWidth-maxThickness is transparent
        buffer.vertex(matrix, screenWidth - maxThickness, 0, 0).color(red, green, blue, 0f).endVertex();
        buffer.vertex(matrix, screenWidth, 0, 0).color(red, green, blue, edgeAlpha).endVertex();
        buffer.vertex(matrix, screenWidth, screenHeight, 0).color(red, green, blue, edgeAlpha).endVertex();
        buffer.vertex(matrix, screenWidth - maxThickness, screenHeight, 0).color(red, green, blue, 0f).endVertex();

        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.disableBlend();
        guiGraphics.pose().popPose();
    }


    private void updateVignette(float deltaTime) {
        float fadeSpeed = isRecovering ? VIGNETTE_FAST_FADE_SPEED : VIGNETTE_FADE_SPEED;

        // Fade vignette towards target
        if (vignetteIntensity < vignetteTargetIntensity) {
            vignetteIntensity = Math.min(vignetteIntensity + deltaTime * fadeSpeed * 2, vignetteTargetIntensity);
        } else if (vignetteIntensity > vignetteTargetIntensity) {
            vignetteIntensity = Math.max(vignetteIntensity - deltaTime * fadeSpeed, vignetteTargetIntensity);
        }

        // Decay target intensity over time
        long timeSinceVignette = System.currentTimeMillis() - lastVignetteTime;
        if (timeSinceVignette > 500) { // Start fading after 500ms
            vignetteTargetIntensity = Math.max(0, vignetteTargetIntensity - deltaTime * 0.3f);
        }
    }

    /**
     * Render damage flash vignette at screen corners.
     * Uses irregular ellipse shapes for organic blood splash effect.
     */
    private void renderVignette(GuiGraphics guiGraphics, int screenWidth, int screenHeight, float healthBasedSize, float intensityMultiplier) {
        // Calculate intensity
        float intensity = Mth.clamp(vignetteIntensity * 0.8f * intensityMultiplier, 0.0f, 1.0f);
        if (intensity < 0.01f) return;

        // Brighter red color for damage flash
        float red = 0.45f;
        float green = 0.02f;
        float blue = 0.02f;

        // Setup rendering (don't modify depth test to avoid "Depth formats do not match")
        guiGraphics.pose().pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        // Base radius for damage flash
        float baseRadius = Math.min(screenWidth, screenHeight) * 0.18f * healthBasedSize;

        // Render damage flash ellipses at corners
        renderDamageFlashEllipse(buffer, matrix, -baseRadius * 0.2f, -baseRadius * 0.2f,
                baseRadius * 1.3f, baseRadius * 1.1f, intensity, red, green, blue, 0);
        renderDamageFlashEllipse(buffer, matrix, screenWidth + baseRadius * 0.2f, -baseRadius * 0.2f,
                baseRadius * 1.1f, baseRadius * 1.3f, intensity, red, green, blue, 4);
        renderDamageFlashEllipse(buffer, matrix, -baseRadius * 0.2f, screenHeight + baseRadius * 0.2f,
                baseRadius * 1.2f, baseRadius * 1.2f, intensity, red, green, blue, 8);
        renderDamageFlashEllipse(buffer, matrix, screenWidth + baseRadius * 0.2f, screenHeight + baseRadius * 0.2f,
                baseRadius * 1.1f, baseRadius * 1.1f, intensity, red, green, blue, 12);

        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.disableBlend();
        guiGraphics.pose().popPose();
    }

    /**
     * Render a damage flash ellipse with irregular edges.
     */
    private void renderDamageFlashEllipse(BufferBuilder buffer, Matrix4f matrix,
                                          float centerX, float centerY, float radiusX, float radiusY,
                                          float intensity, float red, float green, float blue,
                                          int offsetStart) {
        int segments = 20;
        float centerAlpha = intensity * 0.75f;

        for (int i = 0; i < segments; i++) {
            float angle1 = (float)(i * 2 * Math.PI / segments);
            float angle2 = (float)((i + 1) * 2 * Math.PI / segments);

            // Get irregular radius multipliers
            int idx1 = (offsetStart + i) % vignetteOffsets.length;
            int idx2 = (offsetStart + i + 1) % vignetteOffsets.length;
            float irregularity1 = 0.75f + vignetteOffsets[idx1] * 0.4f;
            float irregularity2 = 0.75f + vignetteOffsets[idx2] * 0.4f;

            // Calculate outer edge points with irregularity
            float x1 = centerX + (float)Math.cos(angle1) * radiusX * irregularity1;
            float y1 = centerY + (float)Math.sin(angle1) * radiusY * irregularity1;
            float x2 = centerX + (float)Math.cos(angle2) * radiusX * irregularity2;
            float y2 = centerY + (float)Math.sin(angle2) * radiusY * irregularity2;

            // Edge alpha
            float edgeAlpha1 = intensity * 0.1f * irregularity1;
            float edgeAlpha2 = intensity * 0.1f * irregularity2;

            // Triangle: center -> edge1 -> edge2
            buffer.vertex(matrix, centerX, centerY, 0)
                    .color(red, green, blue, centerAlpha)
                    .endVertex();
            buffer.vertex(matrix, x1, y1, 0)
                    .color(red * 0.9f, green, blue, edgeAlpha1)
                    .endVertex();
            buffer.vertex(matrix, x2, y2, 0)
                    .color(red * 0.9f, green, blue, edgeAlpha2)
                    .endVertex();
        }
    }

    private void renderDamageIndicators(GuiGraphics guiGraphics, int screenWidth, int screenHeight, Player player, float partialTick) {
        long currentTime = System.currentTimeMillis();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;

        // Update and render indicators
        Iterator<DamageIndicator> iterator = activeIndicators.iterator();
        while (iterator.hasNext()) {
            DamageIndicator indicator = iterator.next();
            float elapsedSeconds = (currentTime - indicator.startTime) / 1000.0f;

            // Remove expired indicators
            if (elapsedSeconds > INDICATOR_DURATION) {
                iterator.remove();
                continue;
            }

            // Calculate alpha with fade out
            float alpha = 1.0f;
            float fadeStart = INDICATOR_DURATION - FADE_DURATION;
            if (elapsedSeconds > fadeStart) {
                alpha = 1.0f - (elapsedSeconds - fadeStart) / FADE_DURATION;
            }
            // Ensure minimum visibility
            alpha = Mth.clamp(alpha, 0.1f, 1.0f);

            // Calculate angle to damage source using interpolated player rotation for smooth movement
            float angle = calculateAngleToSource(player, indicator.sourcePos, partialTick);

            renderDirectionalIndicator(guiGraphics, centerX, centerY, angle, alpha);
        }
    }

    private float calculateAngleToSource(Player player, Vec3 sourcePos, float partialTick) {
        // Use interpolated eye position for smooth movement
        Vec3 playerPos = player.getEyePosition(partialTick);

        // Calculate direction from player to damage source
        double dx = sourcePos.x - playerPos.x;
        double dz = sourcePos.z - playerPos.z;

        // Calculate the world angle to the source (in degrees, 0 = south, 90 = west, etc.)
        double worldAngleToSource = Math.toDegrees(Math.atan2(-dx, dz));

        // Get interpolated player yaw for smooth rotation tracking
        float playerYaw = player.getViewYRot(partialTick);

        // Calculate relative angle (how far the source is from where player is looking)
        double relativeAngle = worldAngleToSource - playerYaw;

        // Normalize to -180 to 180
        while (relativeAngle > 180) relativeAngle -= 360;
        while (relativeAngle < -180) relativeAngle += 360;

        return (float) relativeAngle;
    }

    private void renderDirectionalIndicator(GuiGraphics guiGraphics, float centerX, float centerY,
                                             float angle, float alpha) {
        guiGraphics.pose().pushPose();

        // Setup rendering (don't modify depth test to avoid "Depth formats do not match")
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();

        // Use QUADS mode for more reliable rendering
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Convert angle to radians for screen coordinates
        // Screen: 0 degrees = up (top of screen), clockwise positive
        // Input angle: positive = source is to the right of where player looks
        float angleRad = (float) Math.toRadians(angle);
        float halfArc = (float) Math.toRadians(ARC_ANGLE / 2);

        int segments = 8; // Reduced for better performance
        float segmentAngle = (halfArc * 2) / segments;

        // Alpha gradient: inner is more transparent, outer is more opaque
        // Apply easing to prevent flicker at low alpha values
        float easedAlpha = AlphaFadeHelper.smoothAlpha(alpha);
        float innerAlpha = easedAlpha * 0.15f;
        float outerAlpha = easedAlpha * 0.85f;

        for (int i = 0; i < segments; i++) {
            float a1 = angleRad - halfArc + segmentAngle * i;
            float a2 = angleRad - halfArc + segmentAngle * (i + 1);

            // Inner edge (more transparent)
            float innerR = getIndicatorInnerRadius();
            float innerX1 = centerX + (float) Math.sin(a1) * innerR;
            float innerY1 = centerY - (float) Math.cos(a1) * innerR;
            float innerX2 = centerX + (float) Math.sin(a2) * innerR;
            float innerY2 = centerY - (float) Math.cos(a2) * innerR;

            // Outer edge (more opaque)
            float outerR = getIndicatorOuterRadius();
            float outerX1 = centerX + (float) Math.sin(a1) * outerR;
            float outerY1 = centerY - (float) Math.cos(a1) * outerR;
            float outerX2 = centerX + (float) Math.sin(a2) * outerR;
            float outerY2 = centerY - (float) Math.cos(a2) * outerR;

            // Quad: inner1 -> inner2 -> outer2 -> outer1
            buffer.vertex(matrix, innerX1, innerY1, 0)
                    .color(1.0f, 0.2f, 0.2f, innerAlpha)
                    .endVertex();
            buffer.vertex(matrix, innerX2, innerY2, 0)
                    .color(1.0f, 0.2f, 0.2f, innerAlpha)
                    .endVertex();
            buffer.vertex(matrix, outerX2, outerY2, 0)
                    .color(0.9f, 0.1f, 0.1f, outerAlpha)
                    .endVertex();
            buffer.vertex(matrix, outerX1, outerY1, 0)
                    .color(0.9f, 0.1f, 0.1f, outerAlpha)
                    .endVertex();
        }

        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.disableBlend();

        guiGraphics.pose().popPose();
    }

    /**
     * Inner class to represent a damage indicator
     */
    private static class DamageIndicator {
        final Vec3 sourcePos;
        final long startTime;

        DamageIndicator(Vec3 sourcePos, long startTime) {
            this.sourcePos = sourcePos;
            this.startTime = startTime;
        }
    }
}

