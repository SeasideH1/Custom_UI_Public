package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.HotbarConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles hotbar slot selection in Adventure mode.
 * - Scroll wheel skips items with hidden tags
 * - Number key selection of tagged items allows temporary use with auto-return
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class HotbarSlotHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(HotbarSlotHandler.class);

    // Cached tag keys
    private static List<TagKey<Item>> cachedHiddenTags = null;
    private static List<TagKey<Item>> cachedTemporaryUseTags = null;
    private static List<TagKey<Item>> cachedSkipScrollTags = null;
    private static int cachedHiddenTagSignature = Integer.MIN_VALUE;
    private static int cachedTemporaryUseTagSignature = Integer.MIN_VALUE;
    private static int cachedSkipScrollTagSignature = Integer.MIN_VALUE;

    // Temporary selection state
    private static boolean isTemporarySelection = false;
    private static int originalSlot = -1;
    private static int temporarySlot = -1;
    private static boolean waitingForRightClick = false;
    private static boolean rightClickUsed = false;
    private static int ticksSinceTemporarySelection = 0;
    private static final int MAX_TEMPORARY_TICKS = 100; // 5 seconds max

    /**
     * Handle mouse scroll to only switch between slots that have items.
     * Skips empty slots, hidden items, and skip-scroll tagged items.
     *
     * When riding a Superbwarfare vehicle with weapon slots, let Superbwarfare handle scrolling.
     * For all other vehicles, we handle scrolling normally.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!shouldHandleInput()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // If player is riding a Superbwarfare vehicle with weapon slot, let Superbwarfare handle scroll
        if (player.isPassenger() && VehicleHelper.isRidingSuperbwarfareVehicle(player)) {
            if (VehicleHelper.hasWeaponSlotForPlayer(player)) {
                // Don't intercept scroll - let Superbwarfare handle weapon switching
                // LOGGER.debug("Player in Superbwarfare vehicle with weapon slot, letting SBW handle scroll");
                return;
            }
        }
        // For all other cases (not in vehicle, or vehicle without weapon slot), we handle scrolling

        double scrollDelta = event.getScrollDelta();
        if (scrollDelta == 0) return;

        Inventory inventory = player.getInventory();
        int currentSlot = inventory.selected;
        // Normal scroll direction: scroll up = previous slot, scroll down = next slot
        int direction = scrollDelta > 0 ? -1 : 1;

        // Find the next valid slot (only slots with items, skipping empty/hidden)
        int nextSlot = findNextValidSlot(inventory, currentSlot, direction);

        // Always cancel the original event to prevent vanilla behavior
        event.setCanceled(true);

        if (nextSlot != currentSlot) {
            // Found a different valid slot, switch to it
            inventory.selected = nextSlot;
            // LOGGER.debug("Scrolled from slot {} to slot {}", currentSlot, nextSlot);
        }
        // If nextSlot == currentSlot, we stay on current slot (no valid target found)
    }

    // Pending slot change to apply after key event
    private static int pendingSlotChange = -1;
    private static boolean hasPendingSlotChange = false;

    /**
     * Handle key press for hotbar slot selection.
     * Note: InputEvent.Key is not cancelable, so we manually override the slot selection.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKeyInput(InputEvent.Key event) {
        if (!shouldHandleInput()) {
            return;
        }

        // Only handle key press, not release
        if (event.getAction() != 1) { // 1 = GLFW_PRESS
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // Check which hotbar key was pressed
        int pressedSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.options.keyHotbarSlots[i].matches(event.getKey(), event.getScanCode())) {
                pressedSlot = i;
                break;
            }
        }

        if (pressedSlot < 0) return;

        Inventory inventory = player.getInventory();
        ItemStack targetStack = inventory.getItem(pressedSlot);
        int currentSlot = inventory.selected;

        // If the target slot is empty, find a valid slot to switch to
        if (targetStack.isEmpty()) {
            // First try to stay on current slot if it has an item
            ItemStack currentStack = inventory.getItem(currentSlot);
            if (!currentStack.isEmpty()) {
                // Stay on current slot - schedule the slot change
                pendingSlotChange = currentSlot;
                hasPendingSlotChange = true;
                // LOGGER.debug("Blocked selection of empty slot: {}, staying on slot: {}", pressedSlot, currentSlot);
            } else {
                // Current slot is also empty, find the first non-empty slot
                int firstValidSlot = findFirstNonEmptySlot(inventory);
                if (firstValidSlot >= 0) {
                    pendingSlotChange = firstValidSlot;
                    hasPendingSlotChange = true;
                    // LOGGER.debug("Blocked selection of empty slot: {}, switching to first valid slot: {}", pressedSlot, firstValidSlot);
                }
            }
            return;
        }

        // Check if the target slot has a temporary use item
        List<TagKey<Item>> temporaryTags = getTemporaryUseTags();
        if (!targetStack.isEmpty() && hasAnyTag(targetStack, temporaryTags)) {
            // Start temporary selection mode - allow the selection but track it

            if (!isTemporarySelection) {
                originalSlot = inventory.selected;
            }
            temporarySlot = pressedSlot;
            isTemporarySelection = true;
            waitingForRightClick = true;
            rightClickUsed = false;
            ticksSinceTemporarySelection = 0;

            // The slot will be selected by the game, we just track it
            // LOGGER.debug("Started temporary selection: original={}, temporary={}", originalSlot, temporarySlot);
            return;
        }

        // Check if the target slot has a hidden item
        List<TagKey<Item>> hiddenTags = getHiddenTags();
        if (!targetStack.isEmpty() && hasAnyTag(targetStack, hiddenTags)) {
            // Don't allow selecting hidden items via number keys - revert to current slot
            pendingSlotChange = currentSlot;
            hasPendingSlotChange = true;
            return;
        }

        // Normal selection - cancel temporary mode if active
        if (isTemporarySelection) {
            cancelTemporarySelection();
        }
    }

    /**
     * Handle mouse button for detecting right-click during temporary selection.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!isTemporarySelection || !waitingForRightClick) return;

        // Right mouse button = 1
        if (event.getButton() == 1 && event.getAction() == 1) { // GLFW_PRESS
            rightClickUsed = true;
            // LOGGER.debug("Right click detected during temporary selection");
        }
    }

    /**
     * Handle client tick to manage temporary selection state and pending slot changes.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        // Apply pending slot change (from blocked empty slot selection)
        if (hasPendingSlotChange && player != null) {
            player.getInventory().selected = pendingSlotChange;
            // LOGGER.debug("Applied pending slot change to: {}", pendingSlotChange);
            hasPendingSlotChange = false;
            pendingSlotChange = -1;
        }

        if (!isTemporarySelection) return;

        if (player == null) {
            cancelTemporarySelection();
            return;
        }

        // Check if still in adventure mode
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.ADVENTURE) {
            cancelTemporarySelection();
            return;
        }

        ticksSinceTemporarySelection++;

        // If right click was used, return to original slot
        if (rightClickUsed && waitingForRightClick) {
            waitingForRightClick = false;
            // Wait a few ticks for the right-click action to complete
        }

        // Return to original slot after right click
        if (rightClickUsed && !waitingForRightClick) {
            // Add a small delay to let the use action complete
            if (ticksSinceTemporarySelection > 3) {
                returnToOriginalSlot(player);
            }
        }

        // Timeout - return to original slot
        if (ticksSinceTemporarySelection > MAX_TEMPORARY_TICKS) {
            // LOGGER.debug("Temporary selection timed out");
            returnToOriginalSlot(player);
        }

        // If player manually changed slot (not through our system), cancel
        if (player.getInventory().selected != temporarySlot) {
            cancelTemporarySelection();
        }
    }

    /**
     * Return to the original slot after temporary selection.
     */
    private static void returnToOriginalSlot(Player player) {
        if (originalSlot >= 0 && originalSlot < 9) {
            player.getInventory().selected = originalSlot;
            // LOGGER.debug("Returned to original slot: {}", originalSlot);
        }
        cancelTemporarySelection();
    }

    /**
     * Cancel temporary selection mode.
     */
    private static void cancelTemporarySelection() {
        isTemporarySelection = false;
        originalSlot = -1;
        temporarySlot = -1;
        waitingForRightClick = false;
        rightClickUsed = false;
        ticksSinceTemporarySelection = 0;
    }

    /**
     * Find the first non-empty slot in the hotbar (slots 0-8).
     * Returns -1 if all slots are empty.
     */
    private static int findFirstNonEmptySlot(Inventory inventory) {
        List<TagKey<Item>> hiddenTags = getHiddenTags();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !hasAnyTag(stack, hiddenTags)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if we should handle input (adventure mode and enabled).
     */
    private static boolean shouldHandleInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }

        // Only active in Adventure mode
        if (mc.gameMode.getPlayerMode() != GameType.ADVENTURE) {
            return false;
        }

        // Check if enabled
        return HotbarConfig.INSTANCE.enabled.get();
    }

    /**
     * Find the next valid slot in the given direction, skipping empty slots, hidden and skip-scroll items.
     * Only switches to slots that have items.
     */
    private static int findNextValidSlot(Inventory inventory, int currentSlot, int direction) {
        List<TagKey<Item>> hiddenTags = getHiddenTags();
        List<TagKey<Item>> skipScrollTags = getSkipScrollTags();

        // Try each slot in the direction
        for (int i = 1; i <= 9; i++) {
            int nextSlot = (currentSlot + direction * i + 9) % 9;
            ItemStack stack = inventory.getItem(nextSlot);

            // Skip empty slots - we only scroll to slots with items
            if (stack.isEmpty()) {
                continue;
            }

            // Skip items with hidden tags
            if (hasAnyTag(stack, hiddenTags)) {
                continue;
            }

            // Skip items with skip-scroll tags
            if (hasAnyTag(stack, skipScrollTags)) {
                continue;
            }

            // This slot is valid (has an item and is not hidden)
            return nextSlot;
        }

        // No valid slot found, stay on current
        return currentSlot;
    }

    /**
     * Check if inventory has any hidden items.
     * (Currently unused but kept for potential future use)
     */
    @SuppressWarnings("unused")
    private static boolean hasHiddenItems(Inventory inventory) {
        List<TagKey<Item>> hiddenTags = getHiddenTags();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && hasAnyTag(stack, hiddenTags)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get hidden tags from config.
     */
    private static List<TagKey<Item>> getHiddenTags() {
        refreshTagCache();
        return cachedHiddenTags;
    }

    /**
     * Get temporary use tags from config.
     */
    private static List<TagKey<Item>> getTemporaryUseTags() {
        refreshTagCache();
        return cachedTemporaryUseTags;
    }

    /**
     * Get skip scroll tags from config.
     */
    private static List<TagKey<Item>> getSkipScrollTags() {
        refreshTagCache();
        return cachedSkipScrollTags;
    }

    /**
     * Refresh the tag cache if needed.
     */
    private static void refreshTagCache() {
        List<? extends String> hiddenTagStrings = HotbarConfig.INSTANCE.hiddenItemTags.get();
        List<? extends String> tempTagStrings = HotbarConfig.INSTANCE.temporaryUseTags.get();
        List<? extends String> skipScrollStrings = HotbarConfig.INSTANCE.skipScrollTags.get();

        int hiddenSignature = computeStringListSignature(hiddenTagStrings);
        int tempSignature = computeStringListSignature(tempTagStrings);
        int skipSignature = computeStringListSignature(skipScrollStrings);

        if (cachedHiddenTags != null
                && hiddenSignature == cachedHiddenTagSignature
                && tempSignature == cachedTemporaryUseTagSignature
                && skipSignature == cachedSkipScrollTagSignature) {
            return;
        }

        cachedHiddenTags = new ArrayList<>();
        cachedTemporaryUseTags = new ArrayList<>();
        cachedSkipScrollTags = new ArrayList<>();

        // Parse hidden tags
        for (String tagString : hiddenTagStrings) {
            try {
                ResourceLocation tagId = new ResourceLocation(tagString);
                cachedHiddenTags.add(TagKey.create(Registries.ITEM, tagId));
            } catch (Exception e) {
                // LOGGER.warn("Invalid hidden tag ID: {}", tagString);
            }
        }

        // Parse temporary use tags
        for (String tagString : tempTagStrings) {
            try {
                ResourceLocation tagId = new ResourceLocation(tagString);
                cachedTemporaryUseTags.add(TagKey.create(Registries.ITEM, tagId));
            } catch (Exception e) {
                // LOGGER.warn("Invalid temporary use tag ID: {}", tagString);
            }
        }

        // Parse skip scroll tags
        for (String tagString : skipScrollStrings) {
            try {
                ResourceLocation tagId = new ResourceLocation(tagString);
                cachedSkipScrollTags.add(TagKey.create(Registries.ITEM, tagId));
            } catch (Exception e) {
                // LOGGER.warn("Invalid skip scroll tag ID: {}", tagString);
            }
        }

        cachedHiddenTagSignature = hiddenSignature;
        cachedTemporaryUseTagSignature = tempSignature;
        cachedSkipScrollTagSignature = skipSignature;
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
     * Check if temporary selection is currently active.
     */
    public static boolean isInTemporarySelection() {
        return isTemporarySelection;
    }

    /**
     * Get the original slot before temporary selection.
     */
    public static int getOriginalSlot() {
        return originalSlot;
    }

    /**
     * Get the temporarily selected slot.
     */
    public static int getTemporarySlot() {
        return temporarySlot;
    }

    private static int computeStringListSignature(List<? extends String> values) {
        int signature = 1;
        for (String value : values) {
            signature = 31 * signature + (value != null ? value.hashCode() : 0);
        }
        return signature;
    }
}

