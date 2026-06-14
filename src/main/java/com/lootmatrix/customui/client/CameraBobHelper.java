package com.lootmatrix.customui.client;

import com.lootmatrix.customui.config.CrosshairConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Computes the screen-space offset caused by vanilla view bobbing and hurt camera shake,
 * so the crosshair / hit-feedback can be rendered at the TRUE screen-center even when
 * the camera is being displaced.
 *
 * Replicates the math from GameRenderer.bobView() and GameRenderer.bobHurt().
 *
 * KEY: View-space Y is UP, but GUI-space Y is DOWN. The Y component must be
 * negated when converting from view displacement to GUI pixel offset.
 *
 * bobView() applies: translate(xTrans, yTrans, 0) + Z-roll + X-roll
 * After translate, the world center that WAS at screen center now appears at:
 *   GUI offset = (+xTrans * scale, -yTrans * scale)   ← Y negated for GUI
 *
 * TACZ COMPATIBILITY:
 * When holding a TACZ gun, TACZ cancels vanilla bobView for the hand/weapon model
 * (via FirstPersonRenderGunEvent.cancelItemInHandViewBobbing), so the gun model
 * doesn't walk-bob. We must skip walk bob correction to keep the crosshair in sync
 * with the gun rather than the world geometry.
 * For hurt shake, TACZ reduces it to ~5% via GunHurtBobTweak when hit by a gun.
 */
@OnlyIn(Dist.CLIENT)
public final class CameraBobHelper {

    private CameraBobHelper() {}

    // Cached offsets in GUI pixels, updated each frame
    private static float bobOffsetX = 0f;
    private static float bobOffsetY = 0f;
    private static int lastUpdatePlayerTick = Integer.MIN_VALUE;
    private static int lastUpdatePartialTickKey = Integer.MIN_VALUE;
    private static int cachedGuiWidth = -1;
    private static float cachedFov = Float.NaN;
    private static float cachedPixelsPerViewUnit = 0f;
    private static final float REPRESENTATIVE_DEPTH = 6.0f;

    /**
     * Call once per frame to update the bob/hurt offset for the current partialTick.
     */
    public static void update(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) {
            bobOffsetX = 0f;
            bobOffsetY = 0f;
            lastUpdatePlayerTick = Integer.MIN_VALUE;
            lastUpdatePartialTickKey = Integer.MIN_VALUE;
            return;
        }

        int partialTickKey = Math.round(partialTick * 1000.0f);
        if (player.tickCount == lastUpdatePlayerTick && partialTickKey == lastUpdatePartialTickKey) {
            return;
        }
        lastUpdatePlayerTick = player.tickCount;
        lastUpdatePartialTickKey = partialTickKey;

        // Check if holding a TACZ gun — TACZ cancels vanilla bobView for hand rendering
        boolean holdingTaczGun = false;
        if (CrosshairConfig.INSTANCE.taczBobOverride.get()) {
            holdingTaczGun = GunAmmoHelper.isTaczGun(player.getMainHandItem());
        }

        float totalX = 0f;
        float totalY = 0f;

        // -------- View Bob (walking) --------
        // TACZ cancels vanilla bobView for hand rendering when holding a gun.
        // The gun model does NOT walk-bob, so crosshair should NOT compensate for walk bob.
        // Skip this section entirely when holding a TACZ gun.
        if (!holdingTaczGun && mc.options.bobView().get()) {
            float bob = player.walkDist - player.walkDistO;
            float bobPhase = -(player.walkDist + bob * partialTick);
            float bobIntensity = Mth.lerp(partialTick, player.oBob, player.bob);

            float sinPhase = Mth.sin(bobPhase * (float) Math.PI);
            float cosPhase = Math.abs(Mth.cos(bobPhase * (float) Math.PI));

            // View-space translation from bobView()
            float xTrans = sinPhase * bobIntensity * 0.5f;
            float yTrans = -cosPhase * bobIntensity;  // always negative (view shifts down)

            // Z-roll also contributes to apparent center shift
            // The roll is: sinPhase * bobIntensity * 3.0 degrees
            // Small-angle approx: roll shifts the center perpendicular to the roll axis
            // Roll around Z moves the view center by roughly (0, rollAngle * halfScreenHeight)
            // but since we're dealing with small angles, we fold this into the Y component
            float rollDeg = sinPhase * bobIntensity * 3.0f;
            float rollRad = rollDeg * (float) (Math.PI / 180.0);

            // Convert view-space displacement to GUI pixels
            // Empirical scale: at the typical crosshair depth (~5-10 blocks),
            // 1 view unit ≈ (guiScaledWidth / (2 * tan(fov/2))) / depth GUI pixels
            // We use a representative depth and FOV-based calculation
            float guiH = mc.getWindow().getGuiScaledHeight();
            float pixelPerViewUnit = getPixelPerViewUnit(mc);

            // GUI offset: X same direction, Y NEGATED (view Y-up → GUI Y-down)
            totalX += xTrans * pixelPerViewUnit;
            totalY += -yTrans * pixelPerViewUnit;  // ← KEY FIX: negate Y for GUI space

            // Roll contribution to X offset (roll tilts the horizon, shifting apparent center)
            // Small roll of θ radians shifts center by approximately θ * halfScreenHeight/2 in X
            // This is a secondary effect, keep it subtle
            totalX += rollRad * guiH * 0.05f;
        }

        // -------- Hurt Shake --------
        // GameRenderer.bobHurt() applies Z-roll and X-roll based on hurtTime/hurtDuration.
        // When holding a TACZ gun and hit by another gun, TACZ reduces hurt bob to ~5%
        // via GunHurtBobTweak (lastTweakMultiplier defaults to 0.05).
        // We scale our correction accordingly.
        float hurtTime = (float) player.hurtTime - partialTick;
        if (hurtTime > 0f && player.hurtDuration > 0) {
            float hurtProgress = hurtTime / (float) player.hurtDuration;
            float wobble = Mth.sin(hurtProgress * hurtProgress * hurtProgress * hurtProgress * (float) Math.PI);

            // Scale factor: when holding TACZ gun, reduce to match TACZ's 5% hurt bob
            float hurtScale = holdingTaczGun
                    ? CrosshairConfig.INSTANCE.taczHurtBobScale.get().floatValue()
                    : 1.0f;

            float shiftMagnitude = wobble * 2.5f * hurtScale;

            totalY += shiftMagnitude * 0.6f;
            // Alternate X direction based on a stable hash of the hurt event
            totalX += shiftMagnitude * 0.2f * ((player.tickCount & 1) == 0 ? 1f : -1f);
        }

        bobOffsetX = totalX;
        bobOffsetY = totalY;
    }

    private static float getPixelPerViewUnit(Minecraft mc) {
        int guiWidth = mc.getWindow().getGuiScaledWidth();
        float fov = mc.options.fov().get();
        if (guiWidth != cachedGuiWidth || Float.compare(fov, cachedFov) != 0) {
            cachedGuiWidth = guiWidth;
            cachedFov = fov;
            float tanHalfFov = (float) Math.tan((fov * 0.5f) * Mth.DEG_TO_RAD);
            if (tanHalfFov <= 0.0001f) {
                cachedPixelsPerViewUnit = 0f;
            } else {
                cachedPixelsPerViewUnit = (guiWidth * 0.5f) / (tanHalfFov * REPRESENTATIVE_DEPTH);
            }
        }
        return cachedPixelsPerViewUnit;
    }

    /** Screen-space X correction in GUI pixels (add to centerX). */
    public static float getCorrectionX() {
        return bobOffsetX;
    }

    /** Screen-space Y correction in GUI pixels (add to centerY). */
    public static float getCorrectionY() {
        return bobOffsetY;
    }
}




