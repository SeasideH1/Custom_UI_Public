package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.KillIconConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Battlefield 5 style kill icon overlay.
 *
 * Animation: Large -> Small, Transparent -> Opaque
 * For headshot kills: orange ripple effect starts after icon becomes fully opaque
 * Multiple icons stack horizontally, centered on screen.
 *
 * TACZ Integration:
 * - Uses TACZ's EntityKillByGunEvent when available
 * - event.isHeadShot() for headshot detection
 * - Kill indicators are driven by server damage packets
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class KillIconOverlay implements IGuiOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(KillIconOverlay.class);
    private static final KillIconOverlay INSTANCE = new KillIconOverlay();

    // Icon textures - must match actual file names in textures/gui/icons/
    private static final ResourceLocation KILL_ICON = ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/icons/kill.png");
    private static final ResourceLocation ULTIMATE_ICON = ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/gui/icons/kill_headshot.png");

    // ==================== Animation Configuration ====================
    // All configuration values are now loaded from KillIconConfig
    // See config/KillIconConfig.java for default values

    // ==================== Config Accessors ====================
    private static float getAppearDuration() {
        return KillIconConfig.INSTANCE.appearDuration.get().floatValue();
    }
    private static float getHoldDuration() {
        return KillIconConfig.INSTANCE.holdDuration.get().floatValue();
    }
    private static float getFadeDuration() {
        return KillIconConfig.INSTANCE.fadeDuration.get().floatValue();
    }
    private static float getTotalDuration() {
        return getAppearDuration() + getHoldDuration() + getFadeDuration();
    }
    private static float getInitialSize() {
        return KillIconConfig.INSTANCE.initialSize.get().floatValue();
    }
    private static float getFinalSize() {
        return KillIconConfig.INSTANCE.finalSize.get().floatValue();
    }
    private static float getIconSpacing() {
        return KillIconConfig.INSTANCE.iconSpacing.get().floatValue();
    }
    private static int getMaxVisibleIcons() {
        return KillIconConfig.INSTANCE.maxVisibleIcons.get();
    }
    private static float getRippleDuration() {
        return KillIconConfig.INSTANCE.rippleDuration.get().floatValue();
    }
    private static float getRippleStartRadius() {
        return KillIconConfig.INSTANCE.rippleStartRadius.get().floatValue();
    }
    private static float getRippleEndRadius() {
        return KillIconConfig.INSTANCE.rippleEndRadius.get().floatValue();
    }
    private static int getRippleColor() {
        return KillIconConfig.INSTANCE.rippleColor.get();
    }
    private static float getVerticalOffset() {
        return KillIconConfig.INSTANCE.verticalOffset.get().floatValue();
    }
    private static float getHorizontalOffset() {
        return KillIconConfig.INSTANCE.horizontalOffset.get().floatValue();
    }
    private static float getSwayMultiplier() {
        return KillIconConfig.INSTANCE.swayMultiplier.get().floatValue();
    }

    // Active indicators
    private static final List<KillIndicator> activeIndicators = new ArrayList<>();
    private static final Map<Long, KillIndicator> activeIndicatorsByKillEvent = new HashMap<>();

    // GC optimization: reusable list for rendering outside synchronized block
    private static final List<KillIndicator> indicatorsToRenderCache = new ArrayList<>();

    // Frame timing
    private static long lastRenderTime = -1;

    // Track last headshot/critical hit for determining kill icon type (for DamageNumberClientHandler integration)
    private static boolean lastHitWasHeadshotOrCritical = false;
    private static long lastHeadshotTime = 0;
    private static final long HEADSHOT_VALIDITY_MS = 500; // Headshot flag valid for 500ms

    public static KillIconOverlay getInstance() {
        return INSTANCE;
    }

    /**
     * Mark that the last hit was a headshot (from TACZ) or critical hit.
     * This is called when a headshot/critical damage event is received.
     */
    public static void markLastHitAsHeadshot() {
        lastHitWasHeadshotOrCritical = true;
        lastHeadshotTime = System.currentTimeMillis();
        // LOGGER.debug("[KillIconOverlay] Marked last hit as headshot/critical");
    }

    /**
     * Check if last hit was a headshot/critical and still valid.
     */
    private static boolean wasLastHitHeadshot() {
        if (!lastHitWasHeadshotOrCritical) return false;
        // Check if headshot flag is still valid
        if (System.currentTimeMillis() - lastHeadshotTime > HEADSHOT_VALIDITY_MS) {
            lastHitWasHeadshotOrCritical = false;
            return false;
        }
        return true;
    }

    /**
     * Add a kill indicator. Legacy entry point for non-network callers.
     */
    public static void addKillIndicator(boolean isHeadshot) {
        addKillIndicatorInternal(isHeadshot, 0L, false, true);
    }

    public static void addKillIndicatorForKillEvent(boolean isHeadshot, long killEventId) {
        addKillIndicatorInternal(isHeadshot, killEventId, killEventId > 0L, true);
    }

    private static void addKillIndicatorInternal(boolean isHeadshot, long killEventId, boolean dedupeByKillEvent,
                                                 boolean checkConfig) {
        // Check if kill icons are enabled
        if (checkConfig && !KillIconConfig.INSTANCE.enabled.get()) {
            return;
        }

        synchronized (activeIndicators) {
            if (dedupeByKillEvent) {
                KillIndicator existing = activeIndicatorsByKillEvent.get(killEventId);
                if (existing != null) {
                    if (isHeadshot) {
                        existing.isHeadshot = true;
                    }
                    return;
                }
            }

            int maxVisibleIcons = checkConfig ? getMaxVisibleIcons() : Integer.MAX_VALUE;
            while (activeIndicators.size() >= maxVisibleIcons) {
                KillIndicator removed = activeIndicators.remove(0);
                if (removed.killEventId > 0L) {
                    activeIndicatorsByKillEvent.remove(removed.killEventId);
                }
            }
            KillIndicator indicator = new KillIndicator(isHeadshot, dedupeByKillEvent ? killEventId : 0L);
            activeIndicators.add(indicator);
            if (dedupeByKillEvent) {
                activeIndicatorsByKillEvent.put(killEventId, indicator);
            }
            // LOGGER.debug("[KillIconOverlay] Added kill. Headshot: {}, Total: {}",
            //     isHeadshot, activeIndicators.size());
        }
    }

    /**
     * Add a kill indicator when a kill is detected (for DamageNumberClientHandler integration).
     * Checks if the last hit was a headshot/critical to determine icon type.
     */
    public static void addKillIndicator() {
        boolean isHeadshot = wasLastHitHeadshot();
        addKillIndicator(isHeadshot);
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (BackgroundGuard.shouldSkip()) return;

        synchronized (activeIndicators) {
            if (activeIndicators.isEmpty()) {
                lastRenderTime = -1;
                return;
            }
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Delta time (frame-independent)
        long currentTime = System.currentTimeMillis();
        float deltaTime;
        if (lastRenderTime < 0) {
            deltaTime = 0.016f;
        } else {
            deltaTime = (currentTime - lastRenderTime) / 1000.0f;
            deltaTime = Math.min(deltaTime, 0.1f);
        }
        lastRenderTime = currentTime;

        float centerX = screenWidth / 2.0f + getHorizontalOffset();
        float baseY = screenHeight / 2.0f + getVerticalOffset();

        // Sway effect (multiplier from config) - only in adventure mode
        float swayMultiplier = getSwayMultiplier();
        UISwayHelper swayHelper = UISwayHelper.getInstance();
        float swayX = swayHelper.getOffsetXAdventureOnly() * swayMultiplier;
        float swayY = swayHelper.getOffsetYAdventureOnly() * swayMultiplier;
        boolean applySway = swayX != 0f || swayY != 0f;

        if (applySway) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(swayX, swayY, 0);
        }

        synchronized (activeIndicators) {
            // Update all
            for (KillIndicator indicator : activeIndicators) {
                indicator.update(deltaTime);
            }

            // Remove completed
            activeIndicators.removeIf(ind -> {
                if (ind.progress < 1.0f) {
                    return false;
                }
                if (ind.killEventId > 0L) {
                    activeIndicatorsByKillEvent.remove(ind.killEventId);
                }
                return true;
            });

            // Calculate horizontal centering (using config values)
            int count = activeIndicators.size();
            float finalSize = getFinalSize();
            float iconSpacing = getIconSpacing();
            float totalWidth = count * finalSize + (count - 1) * (iconSpacing - finalSize);
            float startX = centerX - totalWidth / 2.0f + finalSize / 2.0f;
            // GC optimization: reuse static list instead of new ArrayList per frame
            indicatorsToRenderCache.clear();

            // Render horizontally
            int index = 0;
            for (KillIndicator indicator : activeIndicators) {
                float targetX = startX + index * iconSpacing;
                indicator.updateTargetPos(targetX, baseY, deltaTime);
                indicatorsToRenderCache.add(indicator);
                index++;
            }
        }

        for (KillIndicator indicator : indicatorsToRenderCache) {
            renderKillIndicator(guiGraphics, indicator.currentX, indicator.currentY, indicator);
        }

        if (applySway) {
            guiGraphics.pose().popPose();
        }
    }

    /**
     * Render single indicator.
     * Animation: 40px+100%transparent -> 16px+0%transparent -> ripple(headshot) -> fade out(no size change)
     */
    private static void renderKillIndicator(GuiGraphics guiGraphics, float centerX, float centerY,
                                             KillIndicator indicator) {
        float progress = indicator.progress;
        float totalDuration = getTotalDuration();
        float time = progress * totalDuration;

        // Get config values
        float appearDuration = getAppearDuration();
        float holdDuration = getHoldDuration();
        float fadeDuration = getFadeDuration();
        float initialSize = getInitialSize();
        float finalSize = getFinalSize();
        float rippleDuration = getRippleDuration();

        float currentSize;
        float alpha;

        if (time < appearDuration) {
            // Appear: large transparent -> small opaque with smooth easing
            float p = time / appearDuration;

            // Use ease-out-quart for both size and alpha (smooth, no bounce)
            float eased = easeOutQuart(p);

            // Size: initialSize -> finalSize
            currentSize = initialSize + (finalSize - initialSize) * eased;

            // Alpha: 0 -> 1 with smooth curve
            alpha = eased;

        } else if (time < appearDuration + holdDuration) {
            // Hold: stay at finalSize and fully opaque
            currentSize = finalSize;
            alpha = 1.0f;

        } else {
            // Fade out: keep size at finalSize, smooth alpha fade
            float p = (time - appearDuration - holdDuration) / fadeDuration;
            // Use ease-in-out-cubic for smoother fade
            float eased = easeInOutCubic(p);
            currentSize = finalSize;
            alpha = 1.0f - eased;
        }

        alpha = Math.max(0, Math.min(1, alpha));
        if (alpha <= 0.01f) return;

        ResourceLocation texture = indicator.isHeadshot ? ULTIMATE_ICON : KILL_ICON;
        renderIcon(guiGraphics, centerX, centerY, currentSize, alpha, texture, indicator.isHeadshot);

        // Ripple for headshot (starts after appear animation completes)
        if (indicator.isHeadshot && time >= appearDuration) {
            float rippleTime = time - appearDuration;
            if (rippleTime < rippleDuration) {
                renderRippleEffect(guiGraphics, centerX, centerY, rippleTime / rippleDuration);
            }
        }
    }

    // ==================== Easing Functions ====================
    // All easing functions are frame-rate independent (input is normalized 0-1 progress)

    // Ease out quart: fast start, very smooth end (no bounce)
    private static float easeOutQuart(float t) {
        return 1 - (float)Math.pow(1 - t, 4);
    }

    // Ease in quad: slow start, fast end
    private static float easeInQuad(float t) {
        return t * t;
    }

    // Ease in-out cubic: smooth start and end
    private static float easeInOutCubic(float t) {
        return t < 0.5f
            ? 4 * t * t * t
            : 1 - (float)Math.pow(-2 * t + 2, 3) / 2;
    }

    private static void renderIcon(GuiGraphics guiGraphics, float centerX, float centerY,
                                    float size, float alpha, ResourceLocation texture, boolean isHeadshot) {
        // Use Math.round for consistent pixel positioning to avoid flickering
        float halfSize = size / 2.0f;
        int x = Math.round(centerX - halfSize);
        int y = Math.round(centerY - halfSize);
        int iconSize = Math.max(1, Math.round(size));

        try {
            // Use blend for transparency without modifying depth test state
            // (avoiding "Depth formats do not match" error)
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
            guiGraphics.blit(texture, x, y, 0, 0, iconSize, iconSize, iconSize, iconSize);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.disableBlend();
        } catch (Exception e) {
            renderGeometricIcon(guiGraphics, centerX, centerY, size, alpha, isHeadshot);
        }
    }

    private static void renderGeometricIcon(GuiGraphics guiGraphics, float centerX, float centerY,
                                             float size, float alpha, boolean isHeadshot) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        float r = 1.0f;  // Red is always 1.0
        float g = isHeadshot ? 0.5f : 1.0f;
        float b = isHeadshot ? 0.0f : 1.0f;

        float halfSize = size / 2.0f;
        float lineWidth = Math.max(1.5f, size / 10.0f);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Cross shape
        buffer.vertex(matrix, centerX - halfSize, centerY - lineWidth/2, 0).color(r, g, b, alpha).endVertex();
        buffer.vertex(matrix, centerX - halfSize, centerY + lineWidth/2, 0).color(r, g, b, alpha).endVertex();
        buffer.vertex(matrix, centerX + halfSize, centerY + lineWidth/2, 0).color(r, g, b, alpha).endVertex();
        buffer.vertex(matrix, centerX + halfSize, centerY - lineWidth/2, 0).color(r, g, b, alpha).endVertex();

        buffer.vertex(matrix, centerX - lineWidth/2, centerY - halfSize, 0).color(r, g, b, alpha).endVertex();
        buffer.vertex(matrix, centerX - lineWidth/2, centerY + halfSize, 0).color(r, g, b, alpha).endVertex();
        buffer.vertex(matrix, centerX + lineWidth/2, centerY + halfSize, 0).color(r, g, b, alpha).endVertex();
        buffer.vertex(matrix, centerX + lineWidth/2, centerY - halfSize, 0).color(r, g, b, alpha).endVertex();

        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }

    private static void renderRippleEffect(GuiGraphics guiGraphics, float centerX, float centerY, float progress) {
        // Use ease-out-quart for smoother expansion
        float eased = easeOutQuart(progress);
        float startRadius = getRippleStartRadius();
        float endRadius = getRippleEndRadius();
        float radius = startRadius + (endRadius - startRadius) * eased;

        // Smoother alpha curve: quick fade in, slow fade out
        float alpha;
        if (progress < 0.1f) {
            // Quick fade in at start
            alpha = (progress / 0.1f) * 0.9f;
        } else {
            // Smooth fade out using ease-in curve
            float fadeProgress = (progress - 0.1f) / 0.9f;
            alpha = 0.9f * (1.0f - easeInQuad(fadeProgress));
        }

        int rippleColor = getRippleColor();
        float r = ((rippleColor >> 16) & 0xFF) / 255.0f;
        float g = ((rippleColor >> 8) & 0xFF) / 255.0f;
        float b = (rippleColor & 0xFF) / 255.0f;

        // Render with higher segment count for smoother circle
        renderRing(guiGraphics, centerX, centerY, radius, 3.0f, r, g, b, alpha);
    }

    private static void renderRing(GuiGraphics guiGraphics, float centerX, float centerY,
                                    float radius, float thickness, float r, float g, float b, float alpha) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // Higher segment count for smoother circle
        int segments = 48;
        float inner = Math.max(0, radius - thickness / 2.0f);
        float outer = radius + thickness / 2.0f;

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i <= segments; i++) {
            float angle = (float)(i * 2 * Math.PI / segments);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            buffer.vertex(matrix, centerX + outer * cos, centerY + outer * sin, 0).color(r, g, b, alpha).endVertex();
            buffer.vertex(matrix, centerX + inner * cos, centerY + inner * sin, 0).color(r, g, b, alpha).endVertex();
        }

        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.disableBlend();
    }

    public static void triggerKillIndicator(boolean isHeadshot) {
        addKillIndicator(isHeadshot);
    }

    static void clearIndicatorsForTest() {
        synchronized (activeIndicators) {
            activeIndicators.clear();
            activeIndicatorsByKillEvent.clear();
            lastHitWasHeadshotOrCritical = false;
            lastHeadshotTime = 0L;
        }
    }

    static void addKillIndicatorForKillEventForTest(boolean isHeadshot, long killEventId) {
        addKillIndicatorInternal(isHeadshot, killEventId, killEventId > 0L, false);
    }

    static int indicatorCountForTest() {
        synchronized (activeIndicators) {
            return activeIndicators.size();
        }
    }

    static boolean isIndicatorHeadshotForTest(long killEventId) {
        synchronized (activeIndicators) {
            KillIndicator indicator = activeIndicatorsByKillEvent.get(killEventId);
            return indicator != null && indicator.isHeadshot;
        }
    }

    private static class KillIndicator {
        boolean isHeadshot;
        final long killEventId;
        float progress;
        float currentX, currentY;
        float targetX, targetY;
        boolean positionInitialized;

        KillIndicator(boolean isHeadshot, long killEventId) {
            this.isHeadshot = isHeadshot;
            this.killEventId = killEventId;
            this.progress = 0;
            this.positionInitialized = false;
        }

        void update(float deltaTime) {
            progress += deltaTime / getTotalDuration();
            progress = Math.min(1.0f, progress);
        }

        void updateTargetPos(float newTargetX, float newTargetY, float deltaTime) {
            if (!positionInitialized) {
                // First frame: snap to position
                currentX = newTargetX;
                currentY = newTargetY;
                targetX = newTargetX;
                targetY = newTargetY;
                positionInitialized = true;
            } else {
                targetX = newTargetX;
                targetY = newTargetY;

                // Frame-rate independent exponential easing
                // Using exponential decay: current = current + (target - current) * (1 - e^(-speed * dt))
                // This ensures same visual result regardless of frame rate
                float speed = 12.0f;  // Higher = faster movement
                float factor = Math.min(1.0f, speed * deltaTime);

                currentX += (targetX - currentX) * factor;
                currentY += (targetY - currentY) * factor;

                // Snap to target if very close (prevents endless micro-adjustments)
                if (Math.abs(targetX - currentX) < 0.1f) {
                    currentX = targetX;
                }
                if (Math.abs(targetY - currentY) < 0.1f) {
                    currentY = targetY;
                }
            }
        }
    }
}

