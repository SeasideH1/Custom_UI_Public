package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.loading.CustomLoadingOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Wraps every vanilla/Forge loading overlay in the CustomUI themed overlay.
 * The original overlay keeps driving mod loading, resource reload and the
 * early-window lifecycle; only the visuals are replaced.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftLoadingOverlayMixin {

    @ModifyVariable(method = "setOverlay", at = @At("HEAD"), argsOnly = true)
    private Overlay customui$themeLoadingOverlay(Overlay overlay) {
        return CustomLoadingOverlay.wrap(overlay);
    }
}
