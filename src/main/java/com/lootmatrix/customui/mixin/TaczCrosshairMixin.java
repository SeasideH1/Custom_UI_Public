package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.cinematic.CinematicCameraEngine;
import com.lootmatrix.customui.config.CrosshairConfig;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into TACZ's RenderCrosshairEvent to replace the crosshair and hit marker rendering.
 * Our custom + shaped crosshair and vertical bar hit feedback are drawn by CrosshairOverlayRenderer.
 */
@Mixin(value = com.tacz.guns.client.event.RenderCrosshairEvent.class, remap = false)
public abstract class TaczCrosshairMixin {

    /** Cancel TACZ's crosshair rendering so we can draw our own. */
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private static void customui$cancelCrosshair(GuiGraphics graphics, Window window, CallbackInfo ci) {
        if (CinematicCameraEngine.getInstance().isPlaying() || CrosshairConfig.INSTANCE.enabled.get()) {
            ci.cancel();
        }
    }

    /** Cancel TACZ's hit marker rendering so we can draw our own. */
    @Inject(method = "renderHitMarker", at = @At("HEAD"), cancellable = true)
    private static void customui$cancelHitMarker(GuiGraphics graphics, Window window, CallbackInfo ci) {
        if (CinematicCameraEngine.getInstance().isPlaying() || CrosshairConfig.INSTANCE.enabled.get()) {
            ci.cancel();
        }
    }
}
