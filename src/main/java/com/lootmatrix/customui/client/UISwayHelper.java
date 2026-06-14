package com.lootmatrix.customui.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/**
 * Helper class to calculate UI sway offset based on camera/view rotation.
 * Provides a subtle motion effect for custom UI elements.
 */
public class UISwayHelper {

    // Singleton instance
    private static final UISwayHelper INSTANCE = new UISwayHelper();

    // Previous frame rotation values for calculating deltas
    private float prevYaw = 0f;
    private float prevPitch = 0f;

    // Current smoothed offset values
    private float currentOffsetX = 0f;
    private float currentOffsetY = 0f;

    // Sway configuration
    private static final float SWAY_SENSITIVITY = 0.15f;    // How much rotation affects sway
    private static final float SWAY_SMOOTHING = 8.0f;       // How fast the sway returns to center (higher = faster)
    private static final float MAX_SWAY_OFFSET = 8.0f;      // Maximum sway offset in pixels

    // Last update time for frame-rate independence
    private long lastUpdateTimeNanos = -1;

    // Same-frame guard to avoid recomputing sway for every overlay in the same render pass
    private int lastUpdatePlayerTick = Integer.MIN_VALUE;
    private float lastUpdatePartialTick = Float.NaN;

    // Cached adventure mode state (updated once per update() call)
    private boolean cachedIsAdventureMode = false;
    private long lastFrameWindowId = Long.MIN_VALUE;

    /**
     * Get the singleton instance.
     */
    public static UISwayHelper getInstance() {
        return INSTANCE;
    }

    /**
     * Update the sway values based on current camera rotation.
     * Should be called once per frame before rendering any UI that uses sway.
     */
    public void update() {
        update(Float.NaN);
    }

    /**
     * Update the sway values based on current camera rotation for the current partial tick.
     * Repeated calls within the same frame are ignored.
     */
    public void update(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        long windowId = mc.getWindow() != null ? mc.getWindow().getWindow() : Long.MIN_VALUE;
        Player player = mc.player;
        if (player == null) {
            currentOffsetX = 0f;
            currentOffsetY = 0f;
            cachedIsAdventureMode = false;
            lastUpdatePlayerTick = Integer.MIN_VALUE;
            lastUpdatePartialTick = Float.NaN;
            lastFrameWindowId = windowId;
            return;
        }

        if (windowId == lastFrameWindowId
                && !Float.isNaN(partialTick)
                && player.tickCount == lastUpdatePlayerTick
                && Math.abs(partialTick - lastUpdatePartialTick) < 0.0001f) {
            return;
        }
        lastFrameWindowId = windowId;
        lastUpdatePlayerTick = player.tickCount;
        lastUpdatePartialTick = partialTick;

        // Cache adventure mode state once per update
        cachedIsAdventureMode = mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.ADVENTURE;

        // Calculate delta time for frame-rate independence
        long currentTimeNanos = System.nanoTime();
        float deltaTime;
        if (lastUpdateTimeNanos < 0) {
            deltaTime = 0.016f; // ~60fps default
        } else {
            deltaTime = (currentTimeNanos - lastUpdateTimeNanos) / 1_000_000_000.0f;
        }
        lastUpdateTimeNanos = currentTimeNanos;

        // Clamp delta time - if clamped, it means we were backgrounded
        boolean wasPaused = deltaTime > 0.1f;
        deltaTime = Math.max(0.001f, Math.min(deltaTime, 0.1f));

        // Get current rotation
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        // If resuming from background, reset prev rotation to avoid sway spike
        if (wasPaused) {
            prevYaw = currentYaw;
            prevPitch = currentPitch;
            currentOffsetX = 0f;
            currentOffsetY = 0f;
        }

        // Calculate rotation deltas
        float deltaYaw = currentYaw - prevYaw;
        float deltaPitch = currentPitch - prevPitch;

        // Handle yaw wrapping (-180 to 180)
        if (deltaYaw > 180) deltaYaw -= 360;
        if (deltaYaw < -180) deltaYaw += 360;

        // Calculate target offset based on rotation deltas
        // Yaw affects horizontal sway, pitch affects vertical sway
        float targetOffsetX = -deltaYaw * SWAY_SENSITIVITY;
        float targetOffsetY = -deltaPitch * SWAY_SENSITIVITY;

        // Clamp target offsets
        targetOffsetX = Math.max(-MAX_SWAY_OFFSET, Math.min(MAX_SWAY_OFFSET, targetOffsetX));
        targetOffsetY = Math.max(-MAX_SWAY_OFFSET, Math.min(MAX_SWAY_OFFSET, targetOffsetY));

        // Smoothly interpolate current offset towards target (with return to center)
        // When there's new rotation, offset moves towards the delta
        // When there's no rotation, offset returns to 0
        float lerpFactor = Math.min(1.0f, SWAY_SMOOTHING * deltaTime);

        // Add new delta and lerp back to center
        currentOffsetX = currentOffsetX + targetOffsetX;
        currentOffsetY = currentOffsetY + targetOffsetY;

        // Clamp total offset
        currentOffsetX = Math.max(-MAX_SWAY_OFFSET, Math.min(MAX_SWAY_OFFSET, currentOffsetX));
        currentOffsetY = Math.max(-MAX_SWAY_OFFSET, Math.min(MAX_SWAY_OFFSET, currentOffsetY));

        // Smoothly return to center
        currentOffsetX *= (1.0f - lerpFactor);
        currentOffsetY *= (1.0f - lerpFactor);

        // Update previous values
        prevYaw = currentYaw;
        prevPitch = currentPitch;
    }

    /**
     * Get the current horizontal sway offset.
     * Positive = right, Negative = left
     */
    public float getOffsetX() {
        return currentOffsetX;
    }

    /**
     * Get the current vertical sway offset.
     * Positive = down, Negative = up
     */
    public float getOffsetY() {
        return currentOffsetY;
    }

    /**
     * Get the horizontal sway offset only if in Adventure mode.
     * Returns 0 for non-adventure modes.
     * Uses cached state from last update() call.
     */
    public float getOffsetXAdventureOnly() {
        return cachedIsAdventureMode ? currentOffsetX : 0f;
    }

    /**
     * Get the vertical sway offset only if in Adventure mode.
     * Returns 0 for non-adventure modes.
     * Uses cached state from last update() call.
     */
    public float getOffsetYAdventureOnly() {
        return cachedIsAdventureMode ? currentOffsetY : 0f;
    }

    /**
     * Reset the sway state (e.g., when changing dimensions or respawning).
     */
    public void reset() {
        currentOffsetX = 0f;
        currentOffsetY = 0f;
        prevYaw = 0f;
        prevPitch = 0f;
        lastUpdateTimeNanos = -1;
        cachedIsAdventureMode = false;
        lastUpdatePlayerTick = Integer.MIN_VALUE;
        lastUpdatePartialTick = Float.NaN;
        lastFrameWindowId = Long.MIN_VALUE;
    }
}

