package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.CrosshairConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.lang.reflect.Method;

/**
 * Custom crosshair overlay that replaces TACZ's crosshair with a + shaped bar crosshair
 * and renders hit/kill feedback as animated × pattern bars.
 *
 * Hit feedback animation (× pattern with 4 bars at diagonals):
 * - Normal hit:    4 white bars at the 4 diagonal corners
 * - Headshot hit:  4 × 2 = 8 white bars (doubled at each corner)
 * - Kill:          4 red bars
 * - Headshot kill: 8 red bars
 * - On each trigger: scale from 110% → 100%, rotate offset decays back to base angle
 *
 * ADS fade: crosshair fades out when aiming down sights with TACZ weapons
 * Vanilla charge: gap shrinks and opacity → 70% when charging bow/crossbow/trident
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class CrosshairOverlayRenderer {

    private static final float HIT_FEEDBACK_DISTANCE_SCALE = 0.8f;
    private static final long KILL_FEEDBACK_PRIORITY_WINDOW_MS = 200L;

    // GC optimization: static constant for hit feedback diagonal corners
    private static final float INV_SQRT2 = 0.7071f;
    private static final float[][] HIT_FEEDBACK_CORNERS = {
            {  INV_SQRT2, -INV_SQRT2 },
            { -INV_SQRT2, -INV_SQRT2 },
            { -INV_SQRT2,  INV_SQRT2 },
            {  INV_SQRT2,  INV_SQRT2 },
    };

    // ==================== Hit Feedback State ====================
    private static long lastHitTime = 0;
    private static boolean lastHitWasHeadshot = false;
    private static boolean lastHitWasKill = false;
    private static boolean lastHitWasHeadshotKill = false;

    /** Alternates between +1 (clockwise) and -1 (counter-clockwise) for each new hit. */
    private static int rotationDirection = 1;

    /**
     * One attack can trigger both onHit (hurt event) and onKill (kill event)
     * within the same tick, flipping the direction twice — i.e. never visually
     * alternating. Flips inside this window are treated as the same attack.
     */
    private static final long DIRECTION_FLIP_DEBOUNCE_MS = 60L;
    private static long lastDirectionFlipTime = -1L;

    /** Current rotation offset from base angle (degrees). Decays to 0. */
    private static float currentRotationOffset = 0f;

    /** Current scale. Decays from scaleStart to scaleEnd. */
    private static float currentScale = 1f;

    /** Current alpha for the hit feedback (fades after delay). */
    private static float feedbackAlpha = 0f;

    /** Kill expansion distance (grows over time when kill triggers). */
    private static float killExpandDistance = 0f;
    private static long lastKillTriggerTime = -1L;

    /** Timestamp tracking for frame delta. */
    private static long lastFrameTime = -1;

    // ==================== TACZ Reflection Cache ====================
    private static boolean taczReflectionInitialized = false;
    private static Class<?> taczGunOperatorClass = null;
    private static Method taczFromLivingEntityMethod = null;
    private static Method taczGetAimingProgressMethod = null;

    // ==================== Public API ====================

    /**
     * Called when a hit is detected (normal or headshot, not kill).
     */
    public static void onHit(boolean isHeadshot) {
        CrosshairConfig cfg = CrosshairConfig.INSTANCE;
        long now = System.currentTimeMillis();
        if (isKillFeedbackLocked(now)) {
            return;
        }

        lastHitTime = now;
        lastHitWasHeadshot = isHeadshot;
        lastHitWasKill = false;
        lastHitWasHeadshotKill = false;

        // Alternate rotation direction (debounced: one flip per attack)
        flipRotationDirection(now);
        currentRotationOffset = (float) (cfg.hitBarRotationAmount.get() * rotationDirection);
        currentScale = cfg.hitBarScaleStart.get().floatValue();
        feedbackAlpha = 1.0f;
    }

    /**
     * Called when a kill is detected.
     */
    public static void onKill(boolean isHeadshot) {
        CrosshairConfig cfg = CrosshairConfig.INSTANCE;
        long now = System.currentTimeMillis();
        lastHitTime = now;
        lastHitWasKill = true;
        lastHitWasHeadshot = isHeadshot;
        lastHitWasHeadshotKill = isHeadshot;
        lastKillTriggerTime = now;

        // Same rotation trigger (debounced: the hurt event of this kill already flipped)
        flipRotationDirection(now);
        currentRotationOffset = (float) (cfg.hitBarRotationAmount.get() * rotationDirection);
        currentScale = cfg.hitBarScaleStart.get().floatValue();
        feedbackAlpha = 1.0f;
        killExpandDistance = 0f;
    }

    private static void flipRotationDirection(long now) {
        if (lastDirectionFlipTime >= 0L && now - lastDirectionFlipTime < DIRECTION_FLIP_DEBOUNCE_MS) {
            return; // same attack (hurt + kill double fire): keep this direction
        }
        rotationDirection = -rotationDirection;
        lastDirectionFlipTime = now;
    }

    private static boolean isKillFeedbackLocked(long now) {
        return lastHitWasKill
                && lastKillTriggerTime >= 0L
                && now - lastKillTriggerTime <= KILL_FEEDBACK_PRIORITY_WINDOW_MS;
    }

    // ==================== Rendering ====================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (mc.player.isSpectator()) {
            event.setCanceled(true);
            return;
        }
        if (!CrosshairConfig.INSTANCE.enabled.get()) return;
        if (BackgroundGuard.shouldSkip()) return;

        // Cancel the event to prevent vanilla crosshair rendering and TACZ's handler
        event.setCanceled(true);

        GuiGraphics gfx = event.getGuiGraphics();
        float partialTick = event.getPartialTick();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Calculate delta time
        long now = System.currentTimeMillis();
        float dt;
        if (lastFrameTime < 0) {
            dt = 0.016f;
        } else {
            dt = (now - lastFrameTime) / 1000f;
            dt = Math.min(dt, 0.1f);
        }
        lastFrameTime = now;

        // Update camera bob correction
        if (CrosshairConfig.INSTANCE.cameraCorrectionEnabled.get()) {
            CameraBobHelper.update(partialTick);
        }

        float centerX = screenW / 2f;
        float centerY = screenH / 2f;

        // ---- Compute TACZ ADS (aim down sight) progress early ----
        float aimProgress = 0f;
        if (CrosshairConfig.INSTANCE.adsFadeEnabled.get()) {
            aimProgress = getTaczAimProgress(mc.player);
        }

        // When ADS, keep center at exact screen center (scope aim point)
        // No bob correction when ADS — the scope reticle IS the aim point
        if (CrosshairConfig.INSTANCE.cameraCorrectionEnabled.get()) {
            float strength = CrosshairConfig.INSTANCE.cameraCorrectionStrength.get().floatValue();
            float correctionScale = strength * (1f - aimProgress);
            centerX += CameraBobHelper.getCorrectionX() * correctionScale;
            centerY += CameraBobHelper.getCorrectionY() * correctionScale;
        }

        // When ADS, smoothly interpolate feedback center to screen center
        float feedbackCenterX = Mth.lerp(aimProgress, centerX, screenW / 2f);
        float feedbackCenterY = Mth.lerp(aimProgress, centerY, screenH / 2f);

        // ---- Compute effective alpha and gap modifiers ----
        float alphaMultiplier = 1.0f;
        float gapMultiplier = 1.0f;
        float opacityOverride = -1f; // -1 = no override

        // TACZ ADS fade: crosshair fades to invisible when aiming
        if (aimProgress > 0f) {
            alphaMultiplier *= (1.0f - aimProgress);
        }

        // Vanilla item charge: gap shrinks + opacity changes to chargeOpacity
        if (CrosshairConfig.INSTANCE.chargeShrinkEnabled.get()) {
            float charge = getVanillaChargeProgress(mc.player, partialTick);
            if (charge > 0f) {
                float minGap = CrosshairConfig.INSTANCE.chargeMinGapMultiplier.get().floatValue();
                gapMultiplier = Mth.lerp(charge, 1.0f, minGap);
                float chargeOp = CrosshairConfig.INSTANCE.chargeOpacity.get().floatValue();
                float baseOp = CrosshairConfig.INSTANCE.crosshairOpacity.get().floatValue();
                opacityOverride = Mth.lerp(charge, baseOp, chargeOp);
            }
        }

        // Render crosshair bars (+ shape)
        if (alphaMultiplier > 0.01f) {
            renderCrosshairBars(gfx, centerX, centerY, alphaMultiplier, gapMultiplier, opacityOverride);
        }

        // Update and render hit feedback (uses ADS-aware center position)
        updateFeedback(dt);
        if (feedbackAlpha > 0.01f) {
            renderHitFeedback(gfx, feedbackCenterX, feedbackCenterY);
        }
    }

    // ==================== TACZ ADS Detection ====================

    /**
     * Get TACZ aiming progress via reflection (0.0 = not aiming, 1.0 = fully aimed).
     * Returns 0 if TACZ is not loaded or player is not holding a TACZ gun.
     */
    private static float getTaczAimProgress(Player player) {
        if (player == null) return 0f;

        try {
            initTaczAimReflection();
            if (taczGunOperatorClass == null || taczFromLivingEntityMethod == null
                    || taczGetAimingProgressMethod == null) {
                return 0f;
            }

            // IGunOperator operator = IGunOperator.fromLivingEntity(player);
            Object operator = taczFromLivingEntityMethod.invoke(null, player);
            if (operator == null) return 0f;

            // float progress = operator.getSynAimingProgress();
            Object result = taczGetAimingProgressMethod.invoke(operator);
            if (result instanceof Number) {
                return ((Number) result).floatValue();
            }
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    private static void initTaczAimReflection() {
        if (taczReflectionInitialized) return;
        taczReflectionInitialized = true;

        try {
            taczGunOperatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            for (Method m : taczGunOperatorClass.getMethods()) {
                if (m.getName().equals("fromLivingEntity") && m.getParameterCount() == 1) {
                    taczFromLivingEntityMethod = m;
                }
                if (m.getName().equals("getSynAimingProgress") && m.getParameterCount() == 0) {
                    taczGetAimingProgressMethod = m;
                }
            }
        } catch (Throwable ignored) {
            taczGunOperatorClass = null;
            taczFromLivingEntityMethod = null;
            taczGetAimingProgressMethod = null;
        }
    }

    // ==================== Vanilla Charge Detection ====================

    /**
     * Get the vanilla item charge progress (0.0 = not charging, 1.0 = fully charged).
     * Applies to items with BOW, CROSSBOW, or SPEAR use animation.
     */
    private static float getVanillaChargeProgress(Player player, float partialTick) {
        if (player == null || !player.isUsingItem()) return 0f;

        UseAnim anim = player.getUseItem().getUseAnimation();
        if (anim != UseAnim.BOW && anim != UseAnim.CROSSBOW && anim != UseAnim.SPEAR) {
            return 0f;
        }

        int ticksUsing = player.getTicksUsingItem();
        // Bow-style charge: 0 to 1 over ~20 ticks
        float charge = (ticksUsing + partialTick) / 20f;
        return Math.min(1f, charge);
    }

    // ==================== Crosshair Bars Rendering ====================

    /**
     * Renders a + shaped crosshair with 4 bars, with rounded corners.
     */
    private static void renderCrosshairBars(GuiGraphics gfx, float cx, float cy,
                                             float alphaMultiplier, float gapMultiplier,
                                             float opacityOverride) {
        CrosshairConfig cfg = CrosshairConfig.INSTANCE;

        float barLen = cfg.crosshairBarLength.get().floatValue();
        float barW = cfg.crosshairBarWidth.get().floatValue();
        float gap = cfg.crosshairGap.get().floatValue() * gapMultiplier;
        float alpha = (opacityOverride >= 0 ? opacityOverride : cfg.crosshairOpacity.get().floatValue())
                * alphaMultiplier;
        // Static crosshair uses sharp rectangles with no corner rounding
        float cornerR = 0f;

        if (alpha <= 0.01f) return;

        gfx.pose().pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = gfx.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float r = 1f, g = 1f, b = 1f;

        // 4 bars: top, bottom, left, right
        drawRoundedBar(buf, matrix, cx - barW / 2, cy - gap - barLen, barW, barLen, cornerR, r, g, b, alpha);
        drawRoundedBar(buf, matrix, cx - barW / 2, cy + gap, barW, barLen, cornerR, r, g, b, alpha);
        drawRoundedBar(buf, matrix, cx - gap - barLen, cy - barW / 2, barLen, barW, cornerR, r, g, b, alpha);
        drawRoundedBar(buf, matrix, cx + gap, cy - barW / 2, barLen, barW, cornerR, r, g, b, alpha);

        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
        gfx.pose().popPose();
    }

    /**
     * Draws a filled rectangle with simulated rounded corners via alpha gradients.
     */
    private static void drawRoundedBar(BufferBuilder buf, Matrix4f mat,
                                        float x, float y, float w, float h, float cr,
                                        float r, float g, float b, float a) {
        if (cr <= 0.1f) {
            buf.vertex(mat, x, y, 0).color(r, g, b, a).endVertex();
            buf.vertex(mat, x, y + h, 0).color(r, g, b, a).endVertex();
            buf.vertex(mat, x + w, y + h, 0).color(r, g, b, a).endVertex();
            buf.vertex(mat, x + w, y, 0).color(r, g, b, a).endVertex();
            return;
        }

        cr = Math.min(cr, Math.min(w, h) / 2f);

        // Horizontal strip (full width, reduced height)
        buf.vertex(mat, x, y + cr, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x, y + h - cr, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w, y + h - cr, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w, y + cr, 0).color(r, g, b, a).endVertex();

        // Top strip (reduced width)
        buf.vertex(mat, x + cr, y, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + cr, y + cr, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w - cr, y + cr, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w - cr, y, 0).color(r, g, b, a).endVertex();

        // Bottom strip (reduced width)
        buf.vertex(mat, x + cr, y + h - cr, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + cr, y + h, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w - cr, y + h, 0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x + w - cr, y + h - cr, 0).color(r, g, b, a).endVertex();

        // 4 corner quads with alpha gradient
        drawCornerQuad(buf, mat, x, y, cr, r, g, b, a, 0);
        drawCornerQuad(buf, mat, x + w - cr, y, cr, r, g, b, a, 1);
        drawCornerQuad(buf, mat, x, y + h - cr, cr, r, g, b, a, 2);
        drawCornerQuad(buf, mat, x + w - cr, y + h - cr, cr, r, g, b, a, 3);
    }

    /**
     * Draw a corner quad that fades alpha at the outer corner to simulate rounding.
     */
    private static void drawCornerQuad(BufferBuilder buf, Matrix4f mat,
                                        float x, float y, float cr,
                                        float r, float g, float b, float a,
                                        int corner) {
        float outerA = a * 0.3f;

        switch (corner) {
            case 0: // Top-left
                buf.vertex(mat, x, y, 0).color(r, g, b, outerA).endVertex();
                buf.vertex(mat, x, y + cr, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x + cr, y + cr, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x + cr, y, 0).color(r, g, b, a).endVertex();
                break;
            case 1: // Top-right
                buf.vertex(mat, x, y, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x, y + cr, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x + cr, y + cr, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x + cr, y, 0).color(r, g, b, outerA).endVertex();
                break;
            case 2: // Bottom-left
                buf.vertex(mat, x, y, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x, y + cr, 0).color(r, g, b, outerA).endVertex();
                buf.vertex(mat, x + cr, y + cr, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x + cr, y, 0).color(r, g, b, a).endVertex();
                break;
            case 3: // Bottom-right
                buf.vertex(mat, x, y, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x, y + cr, 0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x + cr, y + cr, 0).color(r, g, b, outerA).endVertex();
                buf.vertex(mat, x + cr, y, 0).color(r, g, b, a).endVertex();
                break;
        }
    }

    // ==================== Hit Feedback Rendering ====================

    private static void updateFeedback(float dt) {
        CrosshairConfig cfg = CrosshairConfig.INSTANCE;

        // Rotation decay: exponential return to 0 (base angle)
        float returnSpeed = cfg.hitBarRotationReturnSpeed.get().floatValue();
        float rotDecay = (float) Math.exp(-returnSpeed * dt);
        currentRotationOffset *= rotDecay;
        if (Math.abs(currentRotationOffset) < 0.1f) currentRotationOffset = 0f;

        // Scale decay: slower exponential lerp toward scaleEnd for visible pop-in effect
        float scaleEnd = cfg.hitBarScaleEnd.get().floatValue();
        float scaleLerp = 1f - (float) Math.exp(-6f * dt);
        currentScale += (scaleEnd - currentScale) * scaleLerp;

        float elapsed = (System.currentTimeMillis() - lastHitTime) / 1000f;

        if (lastHitWasKill) {
            // Kill: expand outward and fade immediately
            float expandSpeed = cfg.hitBarKillExpandSpeed.get().floatValue();
            killExpandDistance += expandSpeed * dt;
            float killFadeDuration = cfg.hitBarKillFadeDuration.get().floatValue();
            feedbackAlpha = Math.max(0f, 1f - elapsed / killFadeDuration);
        } else {
            // Normal hit / headshot: hold then fade
            killExpandDistance = 0f;
            float fadeDelay = cfg.hitBarFadeDelay.get().floatValue();
            float fadeDuration = cfg.hitBarFadeDuration.get().floatValue();
            if (elapsed > fadeDelay) {
                float fadeProgress = (elapsed - fadeDelay) / fadeDuration;
                feedbackAlpha = Math.max(0f, 1f - fadeProgress);
            }
        }
    }

    /**
     * Renders hit feedback as a × pattern with 4 bars at the diagonal corners.
     * Headshot: same layout but bars are longer.
     * Kill: bars expand outward from current position and fade out.
     */
    private static void renderHitFeedback(GuiGraphics gfx, float cx, float cy) {
        CrosshairConfig cfg = CrosshairConfig.INSTANCE;

        float barLen = cfg.hitBarLength.get().floatValue();
        float barW = cfg.hitBarWidth.get().floatValue();
        float distance = cfg.hitBarDistance.get().floatValue() * HIT_FEEDBACK_DISTANCE_SCALE;
        float cornerRadius = cfg.hitBarCornerRadius.get().floatValue();

        boolean isKill = lastHitWasKill;
        boolean isHeadshot = lastHitWasHeadshot;

        // Headshot extension bar dimensions (thinner bar at far side)
        float headshotExtW = isHeadshot ? cfg.hitBarHeadshotExtWidth.get().floatValue() : 0f;
        float headshotExtLen = isHeadshot ? cfg.hitBarHeadshotExtLength.get().floatValue() : 0f;

        // Color: white for hit, red for kill
        float r = 1.0f;
        float g = isKill ? 0.25f : 1.0f;
        float b = isKill ? 0.25f : 1.0f;
        float alpha = feedbackAlpha;

        float rotOff = currentRotationOffset;
        float scale = currentScale;

        float scaledLen = barLen * scale;
        float scaledW = barW * scale;
        float scaledDist = distance * scale;

        // Kill: add expansion to distance
        if (isKill) {
            scaledDist += killExpandDistance;
        }

        float[][] corners = HIT_FEEDBACK_CORNERS;

        float rotRad = (float) Math.toRadians(rotOff);
        float cosR = (float) Math.cos(rotRad);
        float sinR = (float) Math.sin(rotRad);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = gfx.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (float[] corner : corners) {
            float dx = corner[0];
            float dy = corner[1];

            float rdx = dx * cosR - dy * sinR;
            float rdy = dx * sinR + dy * cosR;

            float bx = cx + rdx * scaledDist;
            float by = cy + rdy * scaledDist;

            float angle = -(float) Math.toDegrees(Math.atan2(rdx, rdy));

            addRotatedRoundedBar(buf, matrix, bx, by, scaledLen, scaledW, cornerRadius, angle, r, g, b, alpha);

            // Headshot: draw thinner extension bar connected at the far side (away from center)
            if (isHeadshot && headshotExtLen > 0f) {
                float extLen = headshotExtLen * scale;
                float extW = headshotExtW * scale;
                // Far end of normal bar is at scaledDist + scaledLen/2 from center
                // Extension center is at scaledDist + scaledLen/2 + extLen/2
                float extCenter = scaledDist + scaledLen / 2f + extLen / 2f;
                float extBx = cx + rdx * extCenter;
                float extBy = cy + rdy * extCenter;
                addRotatedRoundedBar(buf, matrix, extBx, extBy, extLen, extW, cornerRadius, angle, r, g, b, alpha);
            }
        }

        BufferUploader.drawWithShader(buf.end());
        RenderSystem.disableBlend();
    }

    private static void addRotatedRoundedBar(BufferBuilder buf, Matrix4f mat, float cx, float cy,
                                             float length, float width, float cornerRadius,
                                             float angleDeg, float r, float g, float b, float a) {
        float angleRad = (float) Math.toRadians(angleDeg);
        float cosA = (float) Math.cos(angleRad);
        float sinA = (float) Math.sin(angleRad);
        float x = -width / 2f;
        float y = -length / 2f;

        if (cornerRadius <= 0.1f) {
            addRotatedQuad(buf, mat, x, y, width, length, r, g, b, a, cosA, sinA, cx, cy);
            return;
        }

        float clampedRadius = Math.min(cornerRadius, Math.min(width, length) / 2f);
        addRotatedQuad(buf, mat, x, y + clampedRadius, width, length - 2f * clampedRadius, r, g, b, a, cosA, sinA, cx, cy);
        addRotatedQuad(buf, mat, x + clampedRadius, y, width - 2f * clampedRadius, clampedRadius, r, g, b, a, cosA, sinA, cx, cy);
        addRotatedQuad(buf, mat, x + clampedRadius, y + length - clampedRadius, width - 2f * clampedRadius, clampedRadius, r, g, b, a, cosA, sinA, cx, cy);
        addRotatedCornerQuad(buf, mat, x, y, clampedRadius, r, g, b, a, 0, cosA, sinA, cx, cy);
        addRotatedCornerQuad(buf, mat, x + width - clampedRadius, y, clampedRadius, r, g, b, a, 1, cosA, sinA, cx, cy);
        addRotatedCornerQuad(buf, mat, x, y + length - clampedRadius, clampedRadius, r, g, b, a, 2, cosA, sinA, cx, cy);
        addRotatedCornerQuad(buf, mat, x + width - clampedRadius, y + length - clampedRadius, clampedRadius, r, g, b, a, 3, cosA, sinA, cx, cy);
    }

    private static void addRotatedQuad(BufferBuilder buf, Matrix4f mat,
                                       float x, float y, float w, float h,
                                       float r, float g, float b, float a,
                                       float cosA, float sinA, float cx, float cy) {
        addRotatedVertex(buf, mat, x, y, r, g, b, a, cosA, sinA, cx, cy);
        addRotatedVertex(buf, mat, x, y + h, r, g, b, a, cosA, sinA, cx, cy);
        addRotatedVertex(buf, mat, x + w, y + h, r, g, b, a, cosA, sinA, cx, cy);
        addRotatedVertex(buf, mat, x + w, y, r, g, b, a, cosA, sinA, cx, cy);
    }

    private static void addRotatedCornerQuad(BufferBuilder buf, Matrix4f mat,
                                             float x, float y, float cr,
                                             float r, float g, float b, float a,
                                             int corner,
                                             float cosA, float sinA, float cx, float cy) {
        float outerA = a * 0.3f;

        switch (corner) {
            case 0 -> {
                addRotatedVertex(buf, mat, x, y, r, g, b, outerA, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x, y + cr, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y + cr, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y, r, g, b, a, cosA, sinA, cx, cy);
            }
            case 1 -> {
                addRotatedVertex(buf, mat, x, y, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x, y + cr, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y + cr, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y, r, g, b, outerA, cosA, sinA, cx, cy);
            }
            case 2 -> {
                addRotatedVertex(buf, mat, x, y, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x, y + cr, r, g, b, outerA, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y + cr, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y, r, g, b, a, cosA, sinA, cx, cy);
            }
            case 3 -> {
                addRotatedVertex(buf, mat, x, y, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x, y + cr, r, g, b, a, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y + cr, r, g, b, outerA, cosA, sinA, cx, cy);
                addRotatedVertex(buf, mat, x + cr, y, r, g, b, a, cosA, sinA, cx, cy);
            }
            default -> {
            }
        }
    }

    private static void addRotatedVertex(BufferBuilder buf, Matrix4f mat,
                                         float localX, float localY,
                                         float r, float g, float b, float a,
                                         float cosA, float sinA, float cx, float cy) {
        float worldX = localX * cosA - localY * sinA + cx;
        float worldY = localX * sinA + localY * cosA + cy;
        buf.vertex(mat, worldX, worldY, 0).color(r, g, b, a).endVertex();
    }

}

