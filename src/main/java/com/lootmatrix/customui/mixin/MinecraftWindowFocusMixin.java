package com.lootmatrix.customui.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin – window-focus keep-alive is now handled via
 * WindowFocusHandler (Forge event), so this class is intentionally empty.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MinecraftWindowFocusMixin {
    // Handled by WindowFocusHandler event subscriber
}

