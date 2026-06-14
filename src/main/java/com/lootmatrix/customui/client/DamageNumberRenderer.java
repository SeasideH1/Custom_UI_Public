package com.lootmatrix.customui.client;

import com.lootmatrix.customui.config.DamageNumberConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Client-side damage number renderer.
 * Renders damage numbers on the left side of screen center.
 * Features:
 * - Multiple damage numbers displayed in a vertical list
 * - Damage accumulation for same entity (moves to bottom when accumulated)
 * - Color change on kill (white -> medium red)
 * - Right-aligned numbers with fade-out animation
 * - Configurable appearance and position
 * - Precise damage tracking using float (only rounded for display)
 */
public class DamageNumberRenderer implements IGuiOverlay {

    // Singleton instance
    private static final DamageNumberRenderer INSTANCE = new DamageNumberRenderer();

    // Active damage entries
    private final List<DamageEntry> damageEntries = new ArrayList<>();

    // Timing
    private long lastUpdateTimeNanos = -1;

    /**
     * Get the singleton instance.
     */
    public static DamageNumberRenderer getInstance() {
        return INSTANCE;
    }

    /**
     * Add damage to be displayed.
     * If an entry for the same entity exists and is within accumulation window, damage is added to it.
     * When damage is accumulated, the entry is moved to the bottom of the list.
     * Otherwise, a new entry is created.
     *
     * @param entityUUID UUID of the damaged entity
     * @param damage     Amount of damage dealt (precise float value)
     * @param isKill     Whether this damage killed the entity
     */
    public void addDamage(UUID entityUUID, float damage, boolean isKill) {
        // Check if config is enabled
        if (!DamageNumberConfig.INSTANCE.enabled.get()) {
            return;
        }

        // Look for existing entry for this entity
        DamageEntry existingEntry = null;
        int existingIndex = -1;
        for (int i = 0; i < damageEntries.size(); i++) {
            DamageEntry entry = damageEntries.get(i);
            if (entry.entityUUID.equals(entityUUID) && !entry.isKill) {
                // Found existing entry that's not marked as kill
                existingEntry = entry;
                existingIndex = i;
                break;
            }
        }

        if (existingEntry != null) {
            // Accumulate damage to existing entry
            existingEntry.addDamage(damage);
            if (isKill) {
                existingEntry.markAsKill();
            }

            // Move entry to bottom of list (so it appears at the end)
            if (existingIndex < damageEntries.size() - 1) {
                damageEntries.remove(existingIndex);
                damageEntries.add(existingEntry);
            }
        } else {
            // Create new entry
            DamageEntry newEntry = new DamageEntry(entityUUID, damage, isKill);
            damageEntries.add(newEntry);
        }
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (BackgroundGuard.shouldSkip()) return;
        Minecraft mc = Minecraft.getInstance();

        // Check if config is enabled
        if (!DamageNumberConfig.INSTANCE.enabled.get()) {
            return;
        }

        if (mc.player == null || mc.options.hideGui) {
            return;
        }

        // Calculate delta time
        long currentTimeNanos = System.nanoTime();
        float deltaTime;
        if (lastUpdateTimeNanos < 0) {
            deltaTime = 0.016f; // ~60fps default
        } else {
            deltaTime = (currentTimeNanos - lastUpdateTimeNanos) / 1_000_000_000.0f;
        }
        lastUpdateTimeNanos = currentTimeNanos;

        // Clamp delta time
        deltaTime = Math.max(0.001f, Math.min(deltaTime, 0.1f));

        // Update and remove expired entries
        updateEntries(deltaTime);

        // Nothing to render
        if (damageEntries.isEmpty()) {
            return;
        }

        // Get config values
        float horizontalOffset = DamageNumberConfig.INSTANCE.horizontalOffset.get().floatValue();
        float verticalOffset = DamageNumberConfig.INSTANCE.verticalOffset.get().floatValue();
        float spacing = DamageNumberConfig.INSTANCE.spacing.get().floatValue();
        float scale = DamageNumberConfig.INSTANCE.scale.get().floatValue();
        boolean dropShadow = DamageNumberConfig.INSTANCE.dropShadow.get();
        float stayDuration = DamageNumberConfig.INSTANCE.stayDuration.get().floatValue();
        float fadeDuration = DamageNumberConfig.INSTANCE.fadeDuration.get().floatValue();

        // Calculate base position (left of screen center)
        float baseX = screenWidth / 2.0f + horizontalOffset;
        float baseY = screenHeight / 2.0f + verticalOffset;

        // Apply sway offset for subtle motion effect (only in adventure mode)
        UISwayHelper swayHelper = UISwayHelper.getInstance();
        float swayOffsetX = swayHelper.getOffsetXAdventureOnly();
        float swayOffsetY = swayHelper.getOffsetYAdventureOnly();

        // Enable blend for alpha transparency
        // OPTIMIZATION: Removed guiGraphics.flush() here - it was breaking batching
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // Apply sway transformation
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(swayOffsetX, swayOffsetY, 0);

        // Render each damage entry
        float currentY = baseY;
        for (DamageEntry entry : damageEntries) {
            float alpha = calculateAlpha(entry.timer, stayDuration, fadeDuration);
            renderDamageNumber(guiGraphics, mc.font, bufferSource, entry, baseX, currentY, scale, dropShadow, alpha);
            currentY += spacing * scale;
        }

        // Restore matrix
        guiGraphics.pose().popPose();

        bufferSource.endBatch();
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Calculate alpha value based on timer, stay duration and fade duration.
     * Alpha is 1.0 during stay duration, then fades to 0.0 over fade duration.
     *
     * @param timer Current timer value
     * @param stayDuration Duration to stay fully visible (in seconds)
     * @param fadeDuration Duration of fade-out animation (in seconds)
     * @return Alpha value between 0.0 and 1.0
     */
    private float calculateAlpha(float timer, float stayDuration, float fadeDuration) {
        if (timer <= stayDuration) {
            // During stay period, fully opaque
            return 1.0f;
        } else {
            // During fade period, linear interpolation from 1.0 to 0.0
            float fadeProgress = (timer - stayDuration) / fadeDuration;
            return Math.max(0.0f, 1.0f - fadeProgress);
        }
    }

    /**
     * Update all damage entries and remove expired ones.
     */
    private void updateEntries(float deltaTime) {
        Iterator<DamageEntry> iterator = damageEntries.iterator();
        while (iterator.hasNext()) {
            DamageEntry entry = iterator.next();
            entry.update(deltaTime);

            // Remove if expired
            if (entry.isExpired()) {
                iterator.remove();
            }
        }
    }

    /**
     * Render a single damage number entry with alpha transparency.
     * Uses Font.drawInBatch with POLYGON_OFFSET mode and FormattedCharSequence to bypass
     * Modern UI's text rendering interception and caching mechanism.
     */
    private void renderDamageNumber(GuiGraphics guiGraphics, Font font, MultiBufferSource.BufferSource bufferSource,
                                     DamageEntry entry,
                                     float x, float y, float scale, boolean dropShadow, float alpha) {
        // Skip rendering if alpha is too low (prevents flickering at very low alpha values)
        if (alpha < 0.02f) {
            return;
        }

        // Get display damage (rounded only for display, internal tracking uses float)
        String text = entry.getDisplayDamage(font);

        // Get color based on kill status
        int baseColor = entry.isKill ?
                DamageNumberConfig.INSTANCE.killColor.get() :
                DamageNumberConfig.INSTANCE.normalColor.get();

        // Match the shared overlay fade pipeline so damage numbers ease the same way
        // as kill messages, indicators, and other HUD overlays.
        float smoothAlpha = AlphaFadeHelper.smoothAlpha(alpha);
        int alphaInt = AlphaFadeHelper.clampAlphaInt((int) (smoothAlpha * 255.0f));
        int color = (alphaInt << 24) | (baseColor & 0x00FFFFFF);

        // Calculate text width for right alignment
        float textWidth = entry.getDisplayWidth(font) * scale;
        float textX = x - textWidth;
        float textY = y - (font.lineHeight * scale) / 2.0f;

        // Render with scaling using direct buffer rendering
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(textX, textY, 0);
        poseStack.scale(scale, scale, 1.0f);

        // Get the transformation matrix
        Matrix4f matrix = poseStack.last().pose();

        // Create a new FormattedCharSequence each frame to avoid Modern UI caching
        // This ensures no animation state is preserved between frames
        FormattedCharSequence charSequence = entry.getDisplaySequence(font);

        // Draw text directly to buffer with NORMAL mode
        font.drawInBatch(
                charSequence,
                0, 0,
                color,
                dropShadow,
                matrix,
                bufferSource,
                Font.DisplayMode.NORMAL,  // Use NORMAL mode to avoid depth buffer issues
                0,  // background color (0 = no background)
                15728880  // packed light (full bright)
        );

        poseStack.popPose();
    }

    /**
     * Clear all damage entries.
     */
    public void clear() {
        damageEntries.clear();
    }

    /**
     * Inner class representing a single damage entry.
     * Uses float for precise damage tracking to avoid rounding errors during accumulation.
     */
    private static class DamageEntry {
        final UUID entityUUID;
        float totalDamage;     // Precise damage value (float)
        boolean isKill;
        float timer;           // Time since last damage
        private String cachedDisplayDamage;
        private FormattedCharSequence cachedDisplaySequence;
        private int cachedDisplayWidth = -1;
        private int cachedDecimalPlaces = Integer.MIN_VALUE;
        private int cachedRoundingMode = Integer.MIN_VALUE;
        private float cachedDamageSnapshot = Float.NaN;

        DamageEntry(UUID entityUUID, float damage, boolean isKill) {
            this.entityUUID = entityUUID;
            this.totalDamage = damage;
            this.isKill = isKill;
            this.timer = 0;
        }

        /**
         * Add damage to this entry and reset the timer.
         * Damage is accumulated as float for precision.
         */
        void addDamage(float damage) {
            this.totalDamage += damage;
            // Reset timer to keep displaying and restart fade animation
            this.timer = 0;
            invalidateCachedDisplay();
        }

        /**
         * Mark this entry as a kill.
         */
        void markAsKill() {
            this.isKill = true;
            // Reset timer to give player time to see the kill
            this.timer = 0;
        }

        /**
         * Update the entry's timer.
         */
        void update(float deltaTime) {
            timer += deltaTime;
        }

        /**
         * Check if this entry has expired and should be removed.
         * Entry expires when timer exceeds stayDuration + fadeDuration.
         */
        boolean isExpired() {
            float stayDuration = DamageNumberConfig.INSTANCE.stayDuration.get().floatValue();
            float fadeDuration = DamageNumberConfig.INSTANCE.fadeDuration.get().floatValue();
            return timer >= (stayDuration + fadeDuration);
        }

        /**
         * Get the damage value to display as a formatted string.
         * Internal tracking uses float for precision to avoid accumulated rounding errors.
         * Only rounds once at display time based on config settings.
         *
         * Example with decimalPlaces=0, roundingMode=round:
         *   3.4 + 3.4 + 3.4 = 10.2 → displays as "10" (not "12" if each was rounded separately)
         *
         * Example with decimalPlaces=1:
         *   3.4 + 3.4 + 3.4 = 10.2 → displays as "10.2" (exact value)
         */
        String getDisplayDamage(Font font) {
            refreshCachedDisplay(font);
            return cachedDisplayDamage;
        }

        int getDisplayWidth(Font font) {
            refreshCachedDisplay(font);
            return cachedDisplayWidth;
        }

        FormattedCharSequence getDisplaySequence(Font font) {
            refreshCachedDisplay(font);
            return cachedDisplaySequence;
        }

        private void refreshCachedDisplay(Font font) {
            int decimalPlaces = DamageNumberConfig.INSTANCE.decimalPlaces.get();
            int roundingMode = DamageNumberConfig.INSTANCE.roundingMode.get();
            if (cachedDisplayDamage != null
                    && cachedDecimalPlaces == decimalPlaces
                    && cachedRoundingMode == roundingMode
                    && Float.compare(cachedDamageSnapshot, totalDamage) == 0) {
                return;
            }

            String displayDamage;
            if (decimalPlaces > 0) {
                // Show with decimal precision
                String format = "%." + decimalPlaces + "f";
                displayDamage = String.format(format, totalDamage);
            } else {
                // Round to integer based on rounding mode
                int displayValue;
                switch (roundingMode) {
                    case 1: // ceil
                        displayValue = (int) Math.ceil(totalDamage);
                        break;
                    case 2: // floor
                        displayValue = (int) Math.floor(totalDamage);
                        break;
                    default: // round (0 or any other value)
                        displayValue = Math.round(totalDamage);
                        break;
                }
                displayDamage = String.valueOf(displayValue);
            }

            cachedDisplayDamage = displayDamage;
            cachedDisplaySequence = FormattedCharSequence.forward(displayDamage, Style.EMPTY);
            cachedDisplayWidth = font.width(displayDamage);
            cachedDecimalPlaces = decimalPlaces;
            cachedRoundingMode = roundingMode;
            cachedDamageSnapshot = totalDamage;
        }

        private void invalidateCachedDisplay() {
            cachedDisplayDamage = null;
            cachedDisplaySequence = null;
            cachedDisplayWidth = -1;
            cachedDecimalPlaces = Integer.MIN_VALUE;
            cachedRoundingMode = Integer.MIN_VALUE;
            cachedDamageSnapshot = Float.NaN;
        }
    }
}

