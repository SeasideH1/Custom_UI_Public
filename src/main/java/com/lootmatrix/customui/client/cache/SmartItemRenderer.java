package com.lootmatrix.customui.client.cache;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Simple item renderer for hotbar slots.
 * Handles brightness adjustment for selected/unselected items.
 */
@OnlyIn(Dist.CLIENT)
public class SmartItemRenderer {

    private static SmartItemRenderer instance;

    /** Last selected slot for tracking changes */
    private int lastSelectedSlot = -1;

    private SmartItemRenderer() {}

    public static SmartItemRenderer getInstance() {
        if (instance == null) {
            instance = new SmartItemRenderer();
        }
        return instance;
    }

    /**
     * Begin a new frame. Call this at the start of hotbar rendering.
     */
    public void beginFrame(int selectedSlot) {
        lastSelectedSlot = selectedSlot;
    }

    /**
     * Render an item with proper brightness based on selection state.
     */
    public void renderItem(GuiGraphics guiGraphics, ItemStack stack, int x, int y, int size,
                           int slotIndex, boolean isSelected) {
        if (stack.isEmpty()) return;

        // Apply brightness for unselected items
        if (!isSelected) {
            RenderSystem.setShaderColor(0.5f, 0.5f, 0.5f, 1.0f);
        }

        if (size != 16) {
            guiGraphics.pose().pushPose();
            float scale = size / 16.0f;
            guiGraphics.pose().translate(x, y, 0);
            guiGraphics.pose().scale(scale, scale, 1.0f);
            guiGraphics.renderItem(stack, 0, 0);
            guiGraphics.pose().popPose();
        } else {
            guiGraphics.renderItem(stack, x, y);
        }

        if (!isSelected) {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }
}

