package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.client.cache.SmartItemRenderer;
import com.lootmatrix.customui.config.HotbarConfig;
import com.lootmatrix.customui.config.PerformanceConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Custom Adventure mode hotbar renderer.
 * Renders a vertical hotbar in the bottom-right corner with:
 * - Item count / ammo display (left)
 * - Item icon (center) - darkened for selected, bright for unselected
 * - Key binding hint (right)
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class AdventureHotbarRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdventureHotbarRenderer.class);

    // Cached tag keys for hidden items
    private static List<TagKey<Item>> cachedHiddenTags = null;
    private static int cachedHiddenTagConfigSignature = Integer.MIN_VALUE;

    // Text scale factor (80% of original)
    private static final float TEXT_SCALE = 0.8f;

    // Extra vertical offset - adjusted to align info display bottom with health bar bottom
    // Health bar bottom is at screenHeight - 22
    // Info display bottom needs to align with that
    private static final int EXTRA_VERTICAL_OFFSET = 41; // Adjusted from 48 to align with health bar

    // Extra left offset to move the entire UI left
    private static final int EXTRA_LEFT_OFFSET = 20;

    // Item info display constants
    private static final int INFO_DISPLAY_TOP_MARGIN = 4;      // Gap between hotbar and info display
    private static final int INFO_DISPLAY_HEIGHT = 16;          // Height of info display
    private static final float INFO_TEXT_SCALE = 0.8f;          // Text scale for info display
    private static final int INFO_BG_COLOR = 0x99333333;        // Background color
    private static final int INFO_TEXT_COLOR = 0xFFFFFFFF;      // White text
    private static final int INFO_GRAY_COLOR = 0xFFAAAAAA;      // Gray for reserve ammo
    private static final int INFO_RED_COLOR = 0xFFFF5555;       // Red for low ammo

    private static final int RELOAD_BAR_COLOR = 0x88FFD200;      // Yellow, semi-transparent
    private static final int RELOAD_GLOW_COLOR = 0xFFFFE200;     // Bright yellow glow
    private static final long RELOAD_GLOW_DURATION_MS = 420;

    private static final long[] reloadGlowStartMs = new long[9];
    private static final float[] displayedProgress = new float[9]; // Smoothed progress for display
    private static final long[] lastProgressUpdateMs = new long[9];
    private static final boolean[] wasReloading = new boolean[9];  // Track reload state edge
    private static final boolean[] flashTriggered = new boolean[9]; // Prevent double flash

    // ==================== Performance Optimization Cache ====================
    private static final List<SlotInfo> cachedVisibleSlots = new ArrayList<>(9);
    private static final boolean[] renderedSlotsScratch = new boolean[9];
    private static final String[] cachedHotkeyDisplayNames = new String[9];
    private static final float[] cachedHotkeyTextWidths = new float[9];
    private static final float[] cachedHotkeyBgWidths = new float[9];
    private static final String HOTKEY_ELLIPSIS = "...";
    private static int lastVisibleSlotSignature = Integer.MIN_VALUE;
    private static long hiddenTagCacheGeneration = 0L;
    private static float cachedHotkeyScale = Float.NaN;
    private static float cachedHotkeyPadding = Float.NaN;
    private static int cachedHotkeyFontLineHeight = -1;

    // GC optimization: per-slot count text cache — eliminates String.valueOf per frame
    private static final String[] cachedCountTexts = new String[9];
    private static final int[] cachedCountValues = new int[9];
    private static final boolean[] cachedCountIsGun = new boolean[9];
    static {
        Arrays.fill(cachedCountValues, Integer.MIN_VALUE);
        Arrays.fill(cachedCountTexts, "");
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderHotbarPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
            return;
        }
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        // Only activate in Adventure mode
        if (mc.gameMode.getPlayerMode() != GameType.ADVENTURE) {
            return;
        }

        // Check if enabled in config
        if (!HotbarConfig.INSTANCE.enabled.get()) {
            return;
        }

        // Handle vehicle riding in Adventure mode
        if (mc.player.isPassenger()) {
            // Check if riding a Superbwarfare vehicle
            if (VehicleHelper.isRidingSuperbwarfareVehicle(mc.player)) {
                // If the player's seat has a weapon slot, hide all hotbars
                if (VehicleHelper.hasWeaponSlotForPlayer(mc.player)) {
                    event.setCanceled(true);  // Hide vanilla hotbar
                    return;  // Don't render custom hotbar - let SBW handle weapon display
                }
            }
            // All other vehicles (including SBW without weapon slot) - show custom hotbar
            event.setCanceled(true);
            renderAdventureHotbar(event.getGuiGraphics(), mc.player, event.getPartialTick());
            return;
        }

        // Cancel vanilla hotbar rendering
        event.setCanceled(true);

        // Render our custom vertical hotbar
        renderAdventureHotbar(event.getGuiGraphics(), mc.player, event.getPartialTick());
    }

    /**
     * Render the custom adventure mode hotbar.
     * Optimized for performance with:
     * - Slot caching per frame
     * - Batch rendering with deferred flush
     * - Reduced shader state changes
     * - Smart item rendering (skip unchanged items)
     * - Batched text rendering to minimize flush calls
     */
    private static void renderAdventureHotbar(GuiGraphics guiGraphics, Player player, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Get selected slot
        int selectedSlot = player.getInventory().selected;

        // Initialize smart item renderer for this frame
        SmartItemRenderer.getInstance().beginFrame(selectedSlot);

        // Update and get sway offset
        float swayOffsetX = 0.0f;
        float swayOffsetY = 0.0f;
        if (PerformanceConfig.INSTANCE.enableUISway.get()) {
            UISwayHelper swayHelper = UISwayHelper.getInstance();
            swayHelper.update(partialTick);
            swayOffsetX = swayHelper.getOffsetX();
            swayOffsetY = swayHelper.getOffsetY();
        }
        boolean applySway = swayOffsetX != 0.0f || swayOffsetY != 0.0f;

        // Get config values (cached by Forge config system)
        int rightPadding = HotbarConfig.INSTANCE.rightPadding.get();
        int bottomPadding = HotbarConfig.INSTANCE.bottomPadding.get();
        int slotSpacing = HotbarConfig.INSTANCE.slotSpacing.get();
        int slotWidth = HotbarConfig.INSTANCE.slotWidth.get();
        int slotHeight = HotbarConfig.INSTANCE.slotHeight.get();

        int visibleSlotSignature = computeVisibleSlotSignature(player);
        if (visibleSlotSignature != lastVisibleSlotSignature) {
            collectVisibleSlots(player, cachedVisibleSlots);
            lastVisibleSlotSignature = visibleSlotSignature;
        }

        List<SlotInfo> visibleSlots = cachedVisibleSlots;

        if (visibleSlots.isEmpty()) {
            return;
        }

        // Calculate starting position (bottom-right, rendering upward)
        int baseX = screenWidth - slotWidth - rightPadding - EXTRA_LEFT_OFFSET;

        // Apply sway offset using matrix translation
        if (applySway) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(swayOffsetX, swayOffsetY, 0);
        }

        // Enable blend once for all backgrounds
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Render slots from slot 0 at top to slot 8 at bottom
        int currentY = screenHeight - bottomPadding - EXTRA_VERTICAL_OFFSET - (visibleSlots.size() * (slotHeight + slotSpacing));

        // Track which slots are being rendered (for flash persistence)
        boolean[] renderedSlots = renderedSlotsScratch;
        Arrays.fill(renderedSlots, false);

        // ==================== OPTIMIZED BATCH RENDERING ====================
        // Phase 1: Render all slot backgrounds (uses fill, minimal state changes)
        for (int i = 0; i < visibleSlots.size(); i++) {
            SlotInfo slot = visibleSlots.get(i);
            renderedSlots[slot.slotIndex] = true;
        }
        renderAllSlotBackgrounds(guiGraphics, visibleSlots, baseX, currentY, slotWidth, slotHeight, slotSpacing, selectedSlot);

        // Phase 2: Render reload overlays (progress bars, glows)
        for (int i = 0; i < visibleSlots.size(); i++) {
            SlotInfo slot = visibleSlots.get(i);
            int slotY = currentY + i * (slotHeight + slotSpacing);
            // Use live inventory stack — slot.stack may lag behind TACZ ammo sync
            ItemStack liveStack = player.getInventory().getItem(slot.slotIndex);
            renderReloadOverlay(guiGraphics, baseX, slotY, slotWidth, slotHeight, slot.slotIndex, liveStack);
        }

        // Phase 3: Render hotkey backgrounds ONLY (no text yet) - batch all quads together
        renderAllHotkeyBackgrounds(guiGraphics, visibleSlots, baseX, currentY, slotWidth, slotHeight, slotSpacing, selectedSlot, mc.font);

        // Phase 4: Render all items in one batch (SmartItemRenderer handles brightness internally)
        // Items are rendered together to maximize GPU batching
        renderAllItemsBatched(guiGraphics, visibleSlots, baseX, currentY, slotWidth, slotHeight, slotSpacing, selectedSlot);

        // Phase 5: Render ALL text elements together (count, hotkeys, info display)
        // This is the key optimization - all text in one batch, one flush at the end
        renderAllTextBatched(guiGraphics, visibleSlots, player, baseX, currentY, slotWidth, slotHeight, slotSpacing, selectedSlot, mc.font);

        // Render any active flashes for slots that weren't rendered
        renderPersistentFlashes(guiGraphics, player, baseX, screenHeight, bottomPadding, slotWidth, slotHeight, slotSpacing, visibleSlots.size(), renderedSlots);

        // Restore matrix after sway offset
        if (applySway) {
            guiGraphics.pose().popPose();
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Render all hotkey backgrounds in a single batch (no text).
     */
    private static void renderAllHotkeyBackgrounds(GuiGraphics guiGraphics, List<SlotInfo> visibleSlots,
                                                    int baseX, int currentY, int slotWidth, int slotHeight,
                                                    int slotSpacing, int selectedSlot, Font font) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = guiGraphics.pose().last().pose();

        float scale = (float) HotbarConfig.INSTANCE.hotkeyScale.get().doubleValue();
        float padding = (float) HotbarConfig.INSTANCE.hotkeyPadding.get().doubleValue();
        float bgHeight = font.lineHeight * scale + padding * 2 - 1;

        for (int i = 0; i < visibleSlots.size(); i++) {
            SlotInfo slot = visibleSlots.get(i);
            boolean isSelected = slot.slotIndex == selectedSlot;
            int slotY = currentY + i * (slotHeight + slotSpacing);

            int bgColor = isSelected ?
                    HotbarConfig.INSTANCE.hotkeySelectedBgColor.get() :
                    HotbarConfig.INSTANCE.hotkeyBgColor.get();

            ensureHotkeyLayout(slot.slotIndex, font, scale, padding, bgHeight);
            float bgWidth = cachedHotkeyBgWidths[slot.slotIndex];

            float hintX = baseX + slotWidth - bgWidth - 4;
            float hintY = slotY + (slotHeight - 1 - bgHeight) / 2.0f;

            float bgR = ((bgColor >> 16) & 0xFF) / 255.0f;
            float bgG = ((bgColor >> 8) & 0xFF) / 255.0f;
            float bgB = (bgColor & 0xFF) / 255.0f;
            float bgA = ((bgColor >> 24) & 0xFF) / 255.0f;

            bufferBuilder.vertex(matrix, hintX, hintY + bgHeight, 0).color(bgR, bgG, bgB, bgA).endVertex();
            bufferBuilder.vertex(matrix, hintX + bgWidth, hintY + bgHeight, 0).color(bgR, bgG, bgB, bgA).endVertex();
            bufferBuilder.vertex(matrix, hintX + bgWidth, hintY, 0).color(bgR, bgG, bgB, bgA).endVertex();
            bufferBuilder.vertex(matrix, hintX, hintY, 0).color(bgR, bgG, bgB, bgA).endVertex();
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    /**
     * Render all items in a batched manner.
     */
    private static void renderAllItemsBatched(GuiGraphics guiGraphics, List<SlotInfo> visibleSlots,
                                               int baseX, int currentY, int slotWidth, int slotHeight,
                                               int slotSpacing, int selectedSlot) {
        for (int i = 0; i < visibleSlots.size(); i++) {
            SlotInfo slot = visibleSlots.get(i);
            boolean isSelected = slot.slotIndex == selectedSlot;
            int slotY = currentY + i * (slotHeight + slotSpacing);
            renderSlotItem(guiGraphics, baseX, slotY, slotWidth, slotHeight, slot.stack, slot.slotIndex, isSelected);
        }
    }

    /**
     * Render ALL text elements in a single batch to minimize flush calls.
     * This includes: item counts, hotkey labels, and info display text.
     */
    private static void renderAllTextBatched(GuiGraphics guiGraphics, List<SlotInfo> visibleSlots, Player player,
                                              int baseX, int currentY, int slotWidth, int slotHeight,
                                              int slotSpacing, int selectedSlot, Font font) {
        int padding = 2;
        int numberAreaWidth = 28;

        float hotkeyScale = (float) HotbarConfig.INSTANCE.hotkeyScale.get().doubleValue();
        float hotkeyPadding = (float) HotbarConfig.INSTANCE.hotkeyPadding.get().doubleValue();
        float bgHeight = font.lineHeight * hotkeyScale + hotkeyPadding * 2 - 1;

        // OPTIMIZATION: Batch all text rendering together without intermediate flushes
        // This significantly reduces draw calls and improves GPU batching
        
        // Render all slot text (counts and hotkeys) in one pass
        for (int i = 0; i < visibleSlots.size(); i++) {
            SlotInfo slot = visibleSlots.get(i);
            boolean isSelected = slot.slotIndex == selectedSlot;
            int slotY = currentY + i * (slotHeight + slotSpacing);

            int textColor = isSelected ?
                    HotbarConfig.INSTANCE.selectedTextColor.get() :
                    HotbarConfig.INSTANCE.normalTextColor.get();

            // Render item count (only if non-empty)
            // Always read the live stack from inventory — slot.stack may be a stale
            // reference after TACZ server sync replaces the ItemStack object.
            ItemStack liveStack = player.getInventory().getItem(slot.slotIndex);
            String countText = getCountTextForSlot(liveStack, slot.slotIndex);
            if (!countText.isEmpty()) {
                boolean isGun = GunAmmoHelper.isGun(liveStack);
                float countAlpha = isGun ? 1.0f : 0.5f;
                renderItemCountNoBatch(guiGraphics, baseX + padding, slotY, numberAreaWidth, slotHeight, textColor, font, countText, countAlpha);
            }

            // Render hotkey text
            int hotkeyTxtColor = isSelected ?
                    HotbarConfig.INSTANCE.hotkeySelectedTextColor.get() :
                    HotbarConfig.INSTANCE.hotkeyTextColor.get();

            ensureHotkeyLayout(slot.slotIndex, font, hotkeyScale, hotkeyPadding, bgHeight);
            String keyName = cachedHotkeyDisplayNames[slot.slotIndex];
            float textWidth = cachedHotkeyTextWidths[slot.slotIndex];
            float bgWidth = cachedHotkeyBgWidths[slot.slotIndex];

            float hintX = baseX + slotWidth - bgWidth - 4;
            float hintY = slotY + (slotHeight - 1 - bgHeight) / 2.0f;

            float textHeight = font.lineHeight * hotkeyScale;
            float textOffsetX = (bgWidth - textWidth) / 2.0f;
            float textOffsetY = (bgHeight - textHeight) / 2.0f + 0.5f;

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(hintX + textOffsetX, hintY + textOffsetY, 0);
            guiGraphics.pose().scale(hotkeyScale, hotkeyScale, 1.0f);
            guiGraphics.drawString(font, keyName, 0, 0, hotkeyTxtColor, false);
            guiGraphics.pose().popPose();
        }

        // Render item info display below the hotbar (only if enabled in config)
        if (HotbarConfig.INSTANCE.showItemInfo.get()) {
            int infoY = currentY + visibleSlots.size() * (slotHeight + slotSpacing) + INFO_DISPLAY_TOP_MARGIN;
            ItemStack selectedStack = player.getInventory().getItem(selectedSlot);
            if (!selectedStack.isEmpty()) {
                if (GunAmmoHelper.isTaczGun(selectedStack)) {
                    renderTaczGunInfoNoBatch(guiGraphics, selectedStack, player, baseX, infoY, slotWidth, INFO_DISPLAY_HEIGHT, font);
                } else {
                    renderItemNameNoBatch(guiGraphics, selectedStack, baseX, infoY, slotWidth, INFO_DISPLAY_HEIGHT, font);
                }
            }
        }

        // CRITICAL: Single flush at the end of ALL text rendering
        // This is the key optimization - all text batched together
        guiGraphics.flush();
    }

    /**
     * Get the display name for a hotkey slot.
     */
    private static String getHotkeyDisplayName(int slotIndex) {
        Minecraft mc = Minecraft.getInstance();
        String keyName;
        if (slotIndex < mc.options.keyHotbarSlots.length) {
            String fullKeyName = mc.options.keyHotbarSlots[slotIndex].getTranslatedKeyMessage().getString();
            keyName = getCustomKeyMapping(fullKeyName);
            if (keyName == null) {
                keyName = extractEnglishPart(fullKeyName);
                if (keyName.isEmpty()) {
                    keyName = String.valueOf(slotIndex + 1);
                }
            }
        } else {
            keyName = String.valueOf(slotIndex + 1);
        }
        return keyName;
    }

    private static void ensureHotkeyLayout(int slotIndex, Font font, float scale, float padding, float bgHeight) {
        if (cachedHotkeyScale != scale || cachedHotkeyPadding != padding || cachedHotkeyFontLineHeight != font.lineHeight) {
            Arrays.fill(cachedHotkeyDisplayNames, null);
            cachedHotkeyScale = scale;
            cachedHotkeyPadding = padding;
            cachedHotkeyFontLineHeight = font.lineHeight;
        }

        String displayName = getHotkeyDisplayName(slotIndex);
        if (!displayName.equals(cachedHotkeyDisplayNames[slotIndex])) {
            TruncatedText hotkeyLayout = truncateScaledText(font, displayName, scale, 24.0f - padding * 2, HOTKEY_ELLIPSIS);
            cachedHotkeyDisplayNames[slotIndex] = hotkeyLayout.text();
            cachedHotkeyTextWidths[slotIndex] = hotkeyLayout.width();
            cachedHotkeyBgWidths[slotIndex] = Math.min(24.0f, Math.max(bgHeight, cachedHotkeyTextWidths[slotIndex] + padding * 2));
        }
    }

    /**
     * Render item count without triggering a flush (for batched rendering).
     */
    private static void renderItemCountNoBatch(GuiGraphics guiGraphics, int x, int y, int areaWidth, int height,
                                                int textColor, Font font, String countText, float alpha) {
        if (countText.isEmpty()) return;

        int originalAlpha = (textColor >> 24) & 0xFF;
        int newAlpha = Math.round(originalAlpha * alpha);
        int colorWithAlpha = (newAlpha << 24) | (textColor & 0x00FFFFFF);

        float scaledTextWidth = font.width(countText) * TEXT_SCALE;
        float scaledTextHeight = font.lineHeight * TEXT_SCALE;

        float textX = x + (areaWidth - scaledTextWidth) / 2.0f;
        float textY = y + (height - scaledTextHeight) / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(textX, textY, 0);
        guiGraphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0f);
        guiGraphics.drawString(font, countText, 0, 0, colorWithAlpha, false);
        guiGraphics.pose().popPose();
    }

    /**
     * Render TACZ gun info without triggering a flush (for batched rendering).
     */
    private static void renderTaczGunInfoNoBatch(GuiGraphics guiGraphics, ItemStack gunStack, Player player,
                                                  int x, int y, int width, int height, Font font) {
        String fireMode = GunAmmoHelper.getTaczFireMode(gunStack);
        int currentAmmo = GunAmmoHelper.getTaczCurrentAmmo(gunStack);
        int reserveAmmo = GunAmmoHelper.getTaczReserveAmmo(gunStack, player);
        int magazineCapacity = GunAmmoHelper.getTaczMagazineCapacity(gunStack);

        String currentAmmoText = formatZeroPaddedInt(Math.min(currentAmmo, 999), 3);
        String reserveAmmoText = formatZeroPaddedInt(Math.min(reserveAmmo, 9999), 4);
        if (reserveAmmo >= 9999) {
            reserveAmmoText = "∞";
        }

        int fireModeColor = 0xFFFFCC00;
        boolean isLowAmmo;
        if (magazineCapacity > 0) {
            isLowAmmo = currentAmmo < (magazineCapacity * 0.4f) && currentAmmo < 10;
        } else {
            isLowAmmo = currentAmmo <= 2;
        }
        int currentAmmoColor = isLowAmmo ? INFO_RED_COLOR : INFO_TEXT_COLOR;

        float scale = INFO_TEXT_SCALE;
        float largeScale = 1.2f;
        float smallScale = 0.7f;

        float separatorWidth = font.width("|") * scale;
        float reserveWidth = font.width(reserveAmmoText) * smallScale;
        float currentWidth = font.width(currentAmmoText) * largeScale;
        float fireModeWidth = font.width(fireMode) * scale;

        float rightEdge = x + width - 4;
        float centerY = y + height / 2.0f;

        // Separator
        float separatorX = rightEdge - separatorWidth;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(separatorX, centerY - (font.lineHeight * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, "|", 0, 0, INFO_TEXT_COLOR, false);
        guiGraphics.pose().popPose();

        // Reserve ammo
        float reserveX = separatorX - reserveWidth - 4;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(reserveX, centerY - (font.lineHeight * smallScale) / 2, 0);
        guiGraphics.pose().scale(smallScale, smallScale, 1.0f);
        guiGraphics.drawString(font, reserveAmmoText, 0, 0, INFO_GRAY_COLOR, false);
        guiGraphics.pose().popPose();

        // Current ammo
        float currentX = reserveX - currentWidth - 4;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(currentX, centerY - (font.lineHeight * largeScale) / 2, 0);
        guiGraphics.pose().scale(largeScale, largeScale, 1.0f);
        guiGraphics.drawString(font, currentAmmoText, 0, 0, currentAmmoColor, false);
        guiGraphics.pose().popPose();

        // Fire mode
        float fireModeX = currentX - fireModeWidth - 4;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(fireModeX, centerY - (font.lineHeight * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, fireMode, 0, 0, fireModeColor, false);
        guiGraphics.pose().popPose();
    }

    /**
     * Render item name without triggering a flush (for batched rendering).
     */
    private static void renderItemNameNoBatch(GuiGraphics guiGraphics, ItemStack stack,
                                               int x, int y, int width, int height, Font font) {
        String itemName = stack.getHoverName().getString();

        float maxWidth = width - 8;
        float scale = INFO_TEXT_SCALE;
        TruncatedText layout = truncateScaledText(font, itemName, scale, maxWidth, "...");

        float textX = x + width - 4 - layout.width();
        float textY = y + (height - font.lineHeight * scale) / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(textX, textY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, layout.text(), 0, 0, INFO_TEXT_COLOR, false);
        guiGraphics.pose().popPose();
    }

    /**
     * Render just the item icon for a slot.
     */
    private static void renderSlotItem(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                        ItemStack stack, int slotIndex, boolean isSelected) {
        if (stack.isEmpty()) return;

        int iconSize = HotbarConfig.INSTANCE.iconSize.get();
        int padding = 2;
        int numberAreaWidth = 28;
        int hotkeyAreaWidth = 18;

        int iconAreaStart = x + padding + numberAreaWidth;
        int iconAreaEnd = x + width - hotkeyAreaWidth - padding;
        int iconAreaWidth = iconAreaEnd - iconAreaStart;

        int renderSize;
        int iconX, iconY;

        // For TACZ guns, use special sizing
        if (GunAmmoHelper.isTaczGun(stack)) {
            renderSize = Math.min(iconAreaWidth, height - 4);
            iconX = iconAreaStart + (iconAreaWidth - renderSize) / 2;
            iconY = y + (height - renderSize) / 2;
        } else {
            renderSize = iconSize;
            iconX = iconAreaStart + (iconAreaWidth - renderSize) / 2;
            iconY = y + (height - renderSize) / 2;
        }

        // Render item with SmartItemRenderer
        SmartItemRenderer.getInstance().renderItem(guiGraphics, stack, iconX, iconY, renderSize, slotIndex, isSelected);
    }

    /**
     * Render just the text elements for a slot (count, hotkey).
     */
    private static void renderSlotText(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                        ItemStack stack, int slotIndex, boolean isSelected, Font font) {
        int textColor = isSelected ?
                HotbarConfig.INSTANCE.selectedTextColor.get() :
                HotbarConfig.INSTANCE.normalTextColor.get();

        int padding = 2;
        int numberAreaWidth = 28;
        int hotkeyAreaWidth = 18;

        // Get count text
        String countText = getCountText(stack);
        boolean isGun = GunAmmoHelper.isGun(stack);
        float countAlpha = isGun ? 1.0f : 0.5f;

        // Render item count on the left
        renderItemCount(guiGraphics, x + padding, y, numberAreaWidth, height, textColor, font, countText, countAlpha, isGun);

        // Render hotkey hint on the right
        renderHotkeyHint(guiGraphics, x + width, y, height - 1, slotIndex, isSelected, font);
    }

    /**
     * Render flash effects for slots that have active glow but weren't rendered in the main loop.
     * This ensures flash persists even when switching away from the reloading gun.
     */
    private static void renderPersistentFlashes(GuiGraphics guiGraphics, Player player, int baseX,
                                                  int screenHeight, int bottomPadding,
                                                  int slotWidth, int slotHeight, int slotSpacing,
                                                  int visibleSlotsCount, boolean[] renderedSlots) {
        long now = System.currentTimeMillis();
        BufferBuilder bufferBuilder = null;
        Matrix4f matrix = null;

        for (int slotIndex = 0; slotIndex < 9; slotIndex++) {
            // Skip if already rendered or no active glow
            if (renderedSlots[slotIndex]) continue;
            long glowStart = reloadGlowStartMs[slotIndex];
            if (glowStart <= 0) continue;

            long elapsed = now - glowStart;
            if (elapsed >= RELOAD_GLOW_DURATION_MS) {
                reloadGlowStartMs[slotIndex] = 0;
                continue;
            }

            // Calculate position for this slot (even if not visible)
            // Find where this slot would be rendered based on its index
            int slotY = screenHeight - bottomPadding - EXTRA_VERTICAL_OFFSET - (visibleSlotsCount * (slotHeight + slotSpacing));

            // Estimate position based on slot index within visible range
            // This is approximate - flash will appear at the expected position
            int estimatedVisibleIndex = 0;
            Inventory inventory = player.getInventory();
            for (int i = 0; i < slotIndex; i++) {
                if (!inventory.getItem(i).isEmpty()) {
                    estimatedVisibleIndex++;
                }
            }
            slotY += estimatedVisibleIndex * (slotHeight + slotSpacing);

            // Render glow at this position
            float t = 1f - (elapsed / (float) RELOAD_GLOW_DURATION_MS);
            float intensity = t * t;
            int glowColor = applyAlpha(RELOAD_GLOW_COLOR, intensity);
            if (bufferBuilder == null) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                bufferBuilder = Tesselator.getInstance().getBuilder();
                bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                matrix = guiGraphics.pose().last().pose();
            }
            addColoredQuad(bufferBuilder, matrix, baseX, slotY, baseX + slotWidth, slotY + slotHeight, glowColor);
        }

        if (bufferBuilder != null) {
            BufferUploader.drawWithShader(bufferBuilder.end());
            RenderSystem.disableBlend();
        }
    }

    /**
     * Collect all visible slots (non-empty and not filtered by tags).
     */
    private static void collectVisibleSlots(Player player, List<SlotInfo> slots) {
        slots.clear();
        Inventory inventory = player.getInventory();
        List<TagKey<Item>> hiddenTags = getHiddenTags();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);

            // Skip empty slots
            if (stack.isEmpty()) {
                continue;
            }

            // Skip items with hidden tags
            if (hasAnyTag(stack, hiddenTags)) {
                continue;
            }

            slots.add(new SlotInfo(i, stack));
        }

    }

    private static int computeVisibleSlotSignature(Player player) {
        Inventory inventory = player.getInventory();
        List<TagKey<Item>> hiddenTags = getHiddenTags();
        int signature = Long.hashCode(hiddenTagCacheGeneration);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            int stackSignature = 1;
            if (!stack.isEmpty()) {
                stackSignature = 31 * stackSignature + Item.getId(stack.getItem());
                stackSignature = 31 * stackSignature + stack.getCount();
                stackSignature = 31 * stackSignature + stack.getDamageValue();
                // Include NBT hashCode so that ItemStack replacement (e.g. TACZ ammo
                // sync from server) is detected even when count/damage stay the same.
                CompoundTag tag = stack.getTag();
                stackSignature = 31 * stackSignature + (tag != null ? tag.hashCode() : 0);
                stackSignature = 31 * stackSignature + (hasAnyTag(stack, hiddenTags) ? 1 : 0);
            }
            signature = 31 * signature + stackSignature;
        }

        return signature;
    }

    /**
     * Get the list of hidden tags from config.
     */
    private static List<TagKey<Item>> getHiddenTags() {
        List<? extends String> tagStrings = HotbarConfig.INSTANCE.hiddenItemTags.get();
        int configSignature = computeStringListSignature(tagStrings);
        if (cachedHiddenTags == null || configSignature != cachedHiddenTagConfigSignature) {
            cachedHiddenTags = new ArrayList<>();
            for (String tagString : tagStrings) {
                try {
                    ResourceLocation tagId = ResourceLocation.tryParse(tagString);
                    if (tagId != null) {
                        cachedHiddenTags.add(TagKey.create(Registries.ITEM, tagId));
                    } else {
                        LOGGER.warn("Invalid tag ID: {}", tagString);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Invalid tag ID: {}", tagString);
                }
            }
            cachedHiddenTagConfigSignature = configSignature;
            hiddenTagCacheGeneration++;
        }
        return cachedHiddenTags;
    }

    /**
     * Check if an item has any of the specified tags.
     */
    private static boolean hasAnyTag(ItemStack stack, List<TagKey<Item>> tags) {
        for (TagKey<Item> tag : tags) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the count text for an item.
     */
    private static String getCountText(ItemStack stack) {
        return getCountTextInternal(stack);
    }

    /**
     * Get the count text for an item in a specific slot, using per-slot caching.
     */
    private static String getCountTextForSlot(ItemStack stack, int slotIndex) {
        boolean isGun = GunAmmoHelper.isGun(stack);
        if (isGun) {
            // Guns: ammo changes frequently, always re-query (TACZ API handles its own caching)
            String ammoText = GunAmmoHelper.getTotalAmmoString(stack);
            if (ammoText.isEmpty()) return "∞";
            return ammoText;
        } else {
            // Normal items: cache by count value
            int count = stack.getCount();
            if (count == cachedCountValues[slotIndex] && !cachedCountIsGun[slotIndex]) {
                return cachedCountTexts[slotIndex];
            }
            cachedCountValues[slotIndex] = count;
            cachedCountIsGun[slotIndex] = false;
            cachedCountTexts[slotIndex] = String.valueOf(count);
            return cachedCountTexts[slotIndex];
        }
    }

    private static String getCountTextInternal(ItemStack stack) {
        if (GunAmmoHelper.isGun(stack)) {
            String ammoText = GunAmmoHelper.getTotalAmmoString(stack);
            if (ammoText.isEmpty()) return "∞";
            return ammoText;
        } else {
            return String.valueOf(stack.getCount());
        }
    }

    private static void renderAllSlotBackgrounds(GuiGraphics guiGraphics, List<SlotInfo> visibleSlots,
                                                 int baseX, int currentY, int slotWidth, int slotHeight,
                                                 int slotSpacing, int selectedSlot) {
        if (visibleSlots.isEmpty()) {
            return;
        }

        // Frosted-glass backdrop spanning the whole column (one draw call),
        // tinted with the original slot background color so the look blends
        // dark-gray over the blur instead of going fully transparent;
        // per-slot dark fills are kept only as the non-glass fallback.
        int columnBottom = currentY + visibleSlots.size() * (slotHeight + slotSpacing) - slotSpacing;
        boolean glass = com.lootmatrix.customui.client.glass.GlassPanelRenderer.drawHudPanel(
                guiGraphics, baseX - 2f, currentY - 2f,
                slotWidth + 4f, (columnBottom - currentY) + 4f,
                3f, 0x99333333, 0x2EFFFFFF, 1f);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f matrix = guiGraphics.pose().last().pose();

        for (int i = 0; i < visibleSlots.size(); i++) {
            SlotInfo slot = visibleSlots.get(i);
            boolean selected = slot.slotIndex == selectedSlot;
            if (glass && !selected) {
                continue; // glass backdrop already covers unselected slots
            }
            int bgColor = selected
                    ? HotbarConfig.INSTANCE.selectedBgColor.get()
                    : 0x99333333;
            int slotY = currentY + i * (slotHeight + slotSpacing);
            addColoredQuad(bufferBuilder, matrix, baseX, slotY, baseX + slotWidth, slotY + slotHeight, bgColor);
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    private static void renderReloadOverlay(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                            int slotIndex, ItemStack stack) {
        if (slotIndex < 0 || slotIndex >= 9) return;

        long now = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        int currentSelectedSlot = mc.player != null ? mc.player.getInventory().selected : -1;

        // Check if flash glow is active
        long glowStart = reloadGlowStartMs[slotIndex];
        boolean glowActive = glowStart > 0 && (now - glowStart) < RELOAD_GLOW_DURATION_MS;

        // ── Not a TACZ gun: reset progress, keep already-triggered glow ──
        if (!GunAmmoHelper.isTaczGun(stack)) {
            displayedProgress[slotIndex] = 0f;
            wasReloading[slotIndex] = false;
            flashTriggered[slotIndex] = false;
            if (glowActive) {
                renderGlow(guiGraphics, x, y, width, height, slotIndex, now);
            }
            return;
        }

        // ── Already-triggered glow: render and return ──
        if (glowActive) {
            renderGlow(guiGraphics, x, y, width, height, slotIndex, now);
            return;
        }

        // ── Query TACZ reload state ──
        //  reloadingSlot: which inventory slot GunAmmoHelper thinks is reloading
        //                 -1 when no reload is active at all (cancelled or finished)
        //  progress:      0..1 during FEEDING phase, -1 otherwise
        int helperReloadingSlot = GunAmmoHelper.getReloadingSlot();
        float progress = GunAmmoHelper.getReloadProgress(stack, slotIndex);

        // ── Determine if THIS slot is the one actively reloading ──
        // The slot owns the reload only when GunAmmoHelper agrees it is the reloading slot.
        boolean thisSlotIsReloading = (helperReloadingSlot == slotIndex);

        // ── Guard: if a DIFFERENT slot is actively reloading, cancel any stale state here ──
        if (helperReloadingSlot >= 0 && helperReloadingSlot != slotIndex) {
            // Another slot owns the reload — kill any bar/flash state on this slot immediately
            displayedProgress[slotIndex] = 0f;
            wasReloading[slotIndex] = false;
            flashTriggered[slotIndex] = false;
            return;
        }

        // ── Guard: reload was cancelled (reloadingSlot == -1) while bar was in progress ──
        // This is the KEY fix: when the player switches slot mid-reload TACZ cancels the
        // reload entirely (NOT_RELOADING), so reloadingSlot becomes -1 and progress is -1.
        // If this slot still has leftover bar state, the old code would animate to 100% and
        // flash — that's wrong. We must detect the cancellation and kill the bar silently.
        if (helperReloadingSlot == -1 && wasReloading[slotIndex] && !flashTriggered[slotIndex]) {
            // The player who was selected when the reload started is no longer on this slot,
            // OR the reload simply ended without entering FINISHING on the correct slot.
            // Check: is the currently selected slot still this one? If not, the reload was
            // cancelled by switching away.
            if (currentSelectedSlot != slotIndex) {
                // Cancelled — silently clear
                displayedProgress[slotIndex] = 0f;
                wasReloading[slotIndex] = false;
                flashTriggered[slotIndex] = false;
                return;
            }
            // If the player IS still on this slot and reloadingSlot==-1 and progress==-1,
            // we are in the FINISHING phase (feed time) right after FEEDING ended.  That
            // path is handled below (continue-to-100% block).
        }

        // ── FEEDING phase: animate progress bar (progress 0..1) ──
        if (progress >= 0f && thisSlotIsReloading) {

            // Delta time for smooth interpolation
            long lastUpdate = lastProgressUpdateMs[slotIndex];
            float deltaTime = (lastUpdate > 0) ? (now - lastUpdate) / 1000f : 0.016f;
            if (deltaTime > 0.2f) deltaTime = 0.016f;
            lastProgressUpdateMs[slotIndex] = now;

            float target = Mth.clamp(progress, 0f, 1f);
            float current = displayedProgress[slotIndex];

            // Smooth forward interpolation only
            if (target > current) {
                float gap = target - current;
                float expStep = gap * (1f - (float) Math.exp(-15.0 * deltaTime));
                float minStep = 0.6f * deltaTime;
                current = Math.min(target, current + Math.max(expStep, minStep));
            } else if (!wasReloading[slotIndex] && target < 0.05f) {
                // New reload cycle — reset
                current = target;
                flashTriggered[slotIndex] = false;
            }

            displayedProgress[slotIndex] = current;

            // Flash trigger: progress bar reached 100%
            if (current >= 0.999f && !flashTriggered[slotIndex]) {
                displayedProgress[slotIndex] = 1f;
                guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x44000000);
                guiGraphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, RELOAD_BAR_COLOR);

                flashTriggered[slotIndex] = true;
                reloadGlowStartMs[slotIndex] = now;
                displayedProgress[slotIndex] = 0f;
                renderGlow(guiGraphics, x, y, width, height, slotIndex, now);
                wasReloading[slotIndex] = true;
                return;
            }

            // Draw progress bar
            if (current > 0f) {
                guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x44000000);
                int barRight = x + 2 + Math.round((width - 4) * current);
                guiGraphics.fill(x + 2, y + 2, barRight, y + height - 2, RELOAD_BAR_COLOR);
            }

            wasReloading[slotIndex] = true;
            return;
        }

        // ── progress < 0  &&  this slot WAS reloading  &&  bar not yet flashed ──
        // This covers the FINISHING phase: FEEDING ended, bar should animate to 100% then flash.
        // Only do this if the player is STILL on this slot (not switched away).
        if (wasReloading[slotIndex] && displayedProgress[slotIndex] > 0f && !flashTriggered[slotIndex]
                && currentSelectedSlot == slotIndex) {
            // Delta time
            long lastUpdate = lastProgressUpdateMs[slotIndex];
            float deltaTime = (lastUpdate > 0) ? (now - lastUpdate) / 1000f : 0.016f;
            if (deltaTime > 0.2f) deltaTime = 0.016f;
            lastProgressUpdateMs[slotIndex] = now;

            float current = displayedProgress[slotIndex];
            float gap = 1f - current;
            float step = Math.max(gap * 0.3f, 2.0f * deltaTime);
            current = Math.min(1f, current + step);
            displayedProgress[slotIndex] = current;

            // Flash when reached 100%
            if (current >= 0.999f) {
                displayedProgress[slotIndex] = 1f;
                guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x44000000);
                guiGraphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, RELOAD_BAR_COLOR);

                flashTriggered[slotIndex] = true;
                reloadGlowStartMs[slotIndex] = now;
                displayedProgress[slotIndex] = 0f;
                renderGlow(guiGraphics, x, y, width, height, slotIndex, now);
                wasReloading[slotIndex] = false;
                return;
            }

            // Draw bar (still animating to 100%)
            guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x44000000);
            int barRight = x + 2 + Math.round((width - 4) * current);
            guiGraphics.fill(x + 2, y + 2, barRight, y + height - 2, RELOAD_BAR_COLOR);
            return;
        }

        // ── Fallthrough: no active reload, no pending bar — clean up ──
        GunAmmoHelper.consumeReloadCompletedForSlot(slotIndex);
        displayedProgress[slotIndex] = 0f;
        wasReloading[slotIndex] = false;
    }

    private static void renderGlow(GuiGraphics guiGraphics, int x, int y, int width, int height,
                                    int slotIndex, long now) {
        long glowStart = reloadGlowStartMs[slotIndex];
        if (glowStart > 0) {
            long elapsed = now - glowStart;
            if (elapsed < RELOAD_GLOW_DURATION_MS) {
                float t = 1f - (elapsed / (float) RELOAD_GLOW_DURATION_MS);
                float intensity = t * t;
                int glowColor = applyAlpha(RELOAD_GLOW_COLOR, intensity);
                guiGraphics.fill(x, y, x + width, y + height, glowColor);
            } else {
                reloadGlowStartMs[slotIndex] = 0;
            }
        }
    }

    private static int applyAlpha(int color, float alpha) {
        if (AlphaFadeHelper.shouldSkipRender(alpha)) {
            return color & 0x00FFFFFF;
        }
        int a = AlphaFadeHelper.clampAlphaInt((int) (AlphaFadeHelper.smoothAlpha(alpha) * 255f));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static void addColoredQuad(BufferBuilder bufferBuilder, Matrix4f matrix,
                                       float left, float top, float right, float bottom, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        bufferBuilder.vertex(matrix, left, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, bottom, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, right, top, 0).color(r, g, b, a).endVertex();
        bufferBuilder.vertex(matrix, left, top, 0).color(r, g, b, a).endVertex();
    }

    /**
     * Render the hotkey hint on the right side of the slot and return its width.
     * Height is always fixed based on slot height. Width expands for longer text but is capped.
     * Text is truncated if too long to prevent overlap with item icon.
     */
    private static int renderHotkeyHint(GuiGraphics guiGraphics, int rightEdge, int slotY, int slotHeight,
                                        int slotIndex, boolean isSelected, Font font) {
        Minecraft mc = Minecraft.getInstance();

        // Get keybind for this slot (1-9)
        String keyName;
        String fullKeyName = "";
        if (slotIndex < mc.options.keyHotbarSlots.length) {
            fullKeyName = mc.options.keyHotbarSlots[slotIndex].getTranslatedKeyMessage().getString();
            // First check custom key mappings
            keyName = getCustomKeyMapping(fullKeyName);
            if (keyName == null) {
                // No custom mapping, use extracted English part
                keyName = extractEnglishPart(fullKeyName);
                if (keyName.isEmpty()) {
                    keyName = String.valueOf(slotIndex + 1);
                }
            }
        } else {
            keyName = String.valueOf(slotIndex + 1);
        }

        // Get colors
        int bgColor = isSelected ?
                HotbarConfig.INSTANCE.hotkeySelectedBgColor.get() :
                HotbarConfig.INSTANCE.hotkeyBgColor.get();
        int txtColor = isSelected ?
                HotbarConfig.INSTANCE.hotkeySelectedTextColor.get() :
                HotbarConfig.INSTANCE.hotkeyTextColor.get();

        float scale = (float) HotbarConfig.INSTANCE.hotkeyScale.get().doubleValue();
        float padding = (float) HotbarConfig.INSTANCE.hotkeyPadding.get().doubleValue();

        // Fixed height based on font height (square-ish)
        float bgHeight = font.lineHeight * scale + padding * 2 - 1;

        // Maximum width to prevent overlap with item icon
        float maxBgWidth = 24.0f;

        // Truncate key name if too long
        String displayKeyName = keyName;
        float textWidth = font.width(displayKeyName) * scale;
        if (textWidth + padding * 2 > maxBgWidth) {
            // Truncate text to fit
            while (displayKeyName.length() > 1 && font.width(displayKeyName) * scale + padding * 2 > maxBgWidth) {
                displayKeyName = displayKeyName.substring(0, displayKeyName.length() - 1);
            }
            textWidth = font.width(displayKeyName) * scale;
        }

        // Width expands for text but is capped
        float bgWidth = Math.min(maxBgWidth, Math.max(bgHeight, textWidth + padding * 2));

        // Position (right-aligned, vertically centered) - at the right edge of the slot
        float hintX = rightEdge - bgWidth - 4;
        float hintY = slotY + (slotHeight - bgHeight) / 2.0f;

        // Render background
        Matrix4f matrix = guiGraphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float bgR = ((bgColor >> 16) & 0xFF) / 255.0f;
        float bgG = ((bgColor >> 8) & 0xFF) / 255.0f;
        float bgB = (bgColor & 0xFF) / 255.0f;
        float bgA = ((bgColor >> 24) & 0xFF) / 255.0f;

        bufferBuilder.vertex(matrix, hintX, hintY + bgHeight, 0).color(bgR, bgG, bgB, bgA).endVertex();
        bufferBuilder.vertex(matrix, hintX + bgWidth, hintY + bgHeight, 0).color(bgR, bgG, bgB, bgA).endVertex();
        bufferBuilder.vertex(matrix, hintX + bgWidth, hintY, 0).color(bgR, bgG, bgB, bgA).endVertex();
        bufferBuilder.vertex(matrix, hintX, hintY, 0).color(bgR, bgG, bgB, bgA).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();

        // Render text centered in background (moved down to fix alignment)
        float textHeight = font.lineHeight * scale;
        float textOffsetX = (bgWidth - textWidth) / 2.0f;
        float textOffsetY = (bgHeight - textHeight) / 2.0f + 0.5f;  // +1 pixel down

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(hintX + textOffsetX, hintY + textOffsetY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, displayKeyName, 0, 0, txtColor, false);
        guiGraphics.pose().popPose();

        return (int) (bgWidth + 2);
    }


    /**
     * Render the item count or ammo information.
     * Text is scaled to 80% and has no shadow. Positioned on the left side.
     * @param alpha Transparency multiplier (1.0 = full opacity, 0.5 = 50% opacity)
     * @param isGunAmmo If true, this is gun ammo (currently unused, kept for future use)
     */
    private static void renderItemCount(GuiGraphics guiGraphics, int x, int y, int areaWidth, int height,
                                        int textColor, Font font, String countText, float alpha, boolean isGunAmmo) {
        if (countText.isEmpty()) return;

        // Apply alpha to text color
        int originalAlpha = (textColor >> 24) & 0xFF;
        int newAlpha = Math.round(originalAlpha * alpha);
        int colorWithAlpha = (newAlpha << 24) | (textColor & 0x00FFFFFF);

        // Calculate scaled dimensions
        float scaledTextWidth = font.width(countText) * TEXT_SCALE;
        float scaledTextHeight = font.lineHeight * TEXT_SCALE;

        // Center the text within the number area
        float textX = x + (areaWidth - scaledTextWidth) / 2.0f;
        float textY = y + (height - scaledTextHeight) / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(textX, textY, 0);
        guiGraphics.pose().scale(TEXT_SCALE, TEXT_SCALE, 1.0f);
        guiGraphics.drawString(font, countText, 0, 0, colorWithAlpha, false);
        guiGraphics.pose().popPose();
    }

    /**
     * Cached key character mappings from config.
     */
    private static java.util.Map<String, String> cachedKeyMappings = null;
    private static int cachedKeyMappingSignature = Integer.MIN_VALUE;

    /**
     * Get custom display character for a key name from config.
     * Returns null if no custom mapping exists for this key.
     */
    private static String getCustomKeyMapping(String fullKeyName) {
        if (fullKeyName == null || fullKeyName.isEmpty()) {
            return null;
        }

        List<? extends String> mappings = HotbarConfig.INSTANCE.hotkeyCharMappings.get();
        int mappingSignature = computeStringListSignature(mappings);
        if (cachedKeyMappings == null || mappingSignature != cachedKeyMappingSignature) {
            cachedKeyMappings = new java.util.HashMap<>();
            for (String mapping : mappings) {
                int eqIndex = mapping.indexOf('=');
                if (eqIndex > 0 && eqIndex < mapping.length() - 1) {
                    String key = mapping.substring(0, eqIndex).trim();
                    String value = mapping.substring(eqIndex + 1).trim();
                    cachedKeyMappings.put(key, value);
                }
            }
            cachedKeyMappingSignature = mappingSignature;
        }

        // Check for exact match
        String mapped = cachedKeyMappings.get(fullKeyName);
        if (mapped != null) {
            return mapped;
        }

        // Check for partial match (key name contains the mapping key)
        for (java.util.Map.Entry<String, String> entry : cachedKeyMappings.entrySet()) {
            if (fullKeyName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static int computeStringListSignature(List<? extends String> values) {
        int signature = 1;
        for (String value : values) {
            signature = 31 * signature + (value != null ? value.hashCode() : 0);
        }
        return signature;
    }

    /**
     * Extract English/alphanumeric part from key name.
     * Extracts the number or short alphanumeric string from a key name.
     * For example: "Key 5" -> "5", "Numpad 1" -> "1"
     */
    private static String extractEnglishPart(String keyName) {
        if (keyName == null || keyName.isEmpty()) {
            return "";
        }


        StringBuilder result = new StringBuilder();
        for (char c : keyName.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                result.append(c);
            }
        }

        // If result is too long, just use the last character (usually the number)
        if (result.length() > 3) {
            for (int i = result.length() - 1; i >= 0; i--) {
                if (Character.isDigit(result.charAt(i))) {
                    return String.valueOf(result.charAt(i));
                }
            }
        }

        return result.toString();
    }

    private static String formatZeroPaddedInt(int value, int width) {
        String raw = Integer.toString(Math.max(0, value));
        if (raw.length() >= width) {
            return raw;
        }

        StringBuilder builder = new StringBuilder(width);
        for (int i = raw.length(); i < width; i++) {
            builder.append('0');
        }
        builder.append(raw);
        return builder.toString();
    }

    /**
     * Render the item info display below the hotbar.
     * For TACZ guns: shows [Fire Mode] [Current Ammo] [Reserve Ammo] ["|"]
     * For other items: shows item name
     */
    private static void renderItemInfoDisplay(GuiGraphics guiGraphics, Player player, int x, int y, int width, int height, Font font) {
        ItemStack selectedStack = player.getInventory().getItem(player.getInventory().selected);

        if (selectedStack.isEmpty()) {
            return;
        }

        // Add margin at the top
        y += INFO_DISPLAY_TOP_MARGIN;

        // Check if it's a TACZ gun
        if (GunAmmoHelper.isTaczGun(selectedStack)) {
            renderTaczGunInfo(guiGraphics, selectedStack, player, x, y, width, height, font);
        } else {
            // For non-TACZ items, show item name
            renderItemName(guiGraphics, selectedStack, x, y, width, height, font);
        }
    }

    /**
     * Render TACZ gun info: [Fire Mode] [Current Ammo (large)] [Reserve Ammo (small)] ["|"]
     */
    private static void renderTaczGunInfo(GuiGraphics guiGraphics, ItemStack gunStack, Player player,
                                           int x, int y, int width, int height, Font font) {
        // Get gun data
        String fireMode = GunAmmoHelper.getTaczFireMode(gunStack);
        int currentAmmo = GunAmmoHelper.getTaczCurrentAmmo(gunStack);
        int reserveAmmo = GunAmmoHelper.getTaczReserveAmmo(gunStack, player);
        int magazineCapacity = GunAmmoHelper.getTaczMagazineCapacity(gunStack);

        // Format strings
        String currentAmmoText = formatZeroPaddedInt(Math.min(currentAmmo, 999), 3);
        String reserveAmmoText = formatZeroPaddedInt(Math.min(reserveAmmo, 9999), 4);
        if (reserveAmmo >= 9999) {
            reserveAmmoText = "∞";
        }

        // Colors - red when below 25% of magazine capacity AND below 10 (matching TACZ exactly)
        // TACZ logic: ammoCount < (maxAmmoCount * 0.25) && ammoCount < 10
        // When magazine capacity is unknown (-1), use conservative fallback to avoid false positives
        int fireModeColor = 0xFFFFCC00;  // Yellow/gold
        boolean isLowAmmo;
        if (magazineCapacity > 0) {
            // Normal case: use both conditions (matching TACZ logic)
            isLowAmmo = currentAmmo < (magazineCapacity * 0.4f) && currentAmmo < 10;
        } else {
            // Fallback when capacity unknown: only trigger on very low ammo (0-2)
            // This is conservative to avoid false positives for large magazine guns
            isLowAmmo = currentAmmo <= 2;
        }
        int currentAmmoColor = isLowAmmo ? INFO_RED_COLOR : INFO_TEXT_COLOR;

        // Calculate positions - right aligned
        float scale = INFO_TEXT_SCALE;
        float largeScale = 1.2f;  // Larger scale for current ammo
        float smallScale = 0.7f;  // Smaller scale for reserve ammo

        // Calculate widths
        float separatorWidth = font.width("|") * scale;
        float reserveWidth = font.width(reserveAmmoText) * smallScale;
        float currentWidth = font.width(currentAmmoText) * largeScale;
        float fireModeWidth = font.width(fireMode) * scale;

        // Start from right edge
        float rightEdge = x + width - 4;
        float centerY = y + height / 2.0f;

        // 1. Render separator "|" (rightmost)
        float separatorX = rightEdge - separatorWidth;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(separatorX, centerY - (font.lineHeight * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, "|", 0, 0, INFO_TEXT_COLOR, false);
        guiGraphics.pose().popPose();

        // 2. Render reserve ammo (small, gray)
        float reserveX = separatorX - reserveWidth - 4;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(reserveX, centerY - (font.lineHeight * smallScale) / 2, 0);
        guiGraphics.pose().scale(smallScale, smallScale, 1.0f);
        guiGraphics.drawString(font, reserveAmmoText, 0, 0, INFO_GRAY_COLOR, false);
        guiGraphics.pose().popPose();

        // 3. Render current ammo (large, white/red)
        float currentX = reserveX - currentWidth - 4;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(currentX, centerY - (font.lineHeight * largeScale) / 2, 0);
        guiGraphics.pose().scale(largeScale, largeScale, 1.0f);
        guiGraphics.drawString(font, currentAmmoText, 0, 0, currentAmmoColor, false);
        guiGraphics.pose().popPose();

        // 4. Render fire mode (yellow)
        float fireModeX = currentX - fireModeWidth - 4;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(fireModeX, centerY - (font.lineHeight * scale) / 2, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, fireMode, 0, 0, fireModeColor, false);
        guiGraphics.pose().popPose();
    }

    /**
     * Render item name for non-TACZ items.
     */
    private static void renderItemName(GuiGraphics guiGraphics, ItemStack stack,
                                        int x, int y, int width, int height, Font font) {
        String itemName = stack.getHoverName().getString();

        // Truncate if too long
        float maxWidth = width - 8;
        float scale = INFO_TEXT_SCALE;
        TruncatedText layout = truncateScaledText(font, itemName, scale, maxWidth, "...");

        // Right align
        float textX = x + width - 4 - layout.width();
        float textY = y + (height - font.lineHeight * scale) / 2.0f;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(textX, textY, 0);
        guiGraphics.pose().scale(scale, scale, 1.0f);
        guiGraphics.drawString(font, layout.text(), 0, 0, INFO_TEXT_COLOR, false);
        guiGraphics.pose().popPose();
    }

    private static TruncatedText truncateScaledText(Font font, String text, float scale, float maxWidth, String ellipsis) {
        float textWidth = font.width(text) * scale;
        if (textWidth <= maxWidth) {
            return new TruncatedText(text, textWidth);
        }

        float ellipsisWidth = font.width(ellipsis) * scale;
        if (ellipsisWidth >= maxWidth) {
            return new TruncatedText(ellipsis, ellipsisWidth);
        }

        int allowedBaseWidth = Math.max(0, Mth.floor((maxWidth - ellipsisWidth) / scale));
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.width(text.substring(0, mid)) <= allowedBaseWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        String truncated = text.substring(0, low) + ellipsis;
        return new TruncatedText(truncated, font.width(truncated) * scale);
    }

    private record TruncatedText(String text, float width) {}

    /**
     * Info class for visible slots.
     */
    private static class SlotInfo {
        final int slotIndex;
        final ItemStack stack;

        SlotInfo(int slotIndex, ItemStack stack) {
            this.slotIndex = slotIndex;
            this.stack = stack;
        }
    }
}
