package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.atmosphere.AtmosphereEngine;
import com.lootmatrix.customui.atmosphere.CustomSkyRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to replace vanilla sky and cloud rendering when the atmosphere system is active.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererAtmosphereMixin {

    /**
     * Cancel vanilla sky rendering and replace with custom atmosphere sky.
     * renderSky(PoseStack, Matrix4f, float, Camera, boolean, Runnable)
     */
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void customui$overrideSky(PoseStack poseStack, Matrix4f projectionMatrix,
                                      float partialTick, Camera camera,
                                      boolean isFoggy, Runnable setupFog,
                                      CallbackInfo ci) {
        AtmosphereEngine engine = AtmosphereEngine.getInstance();
        if (!engine.shouldOverrideSky()) return;

        // Call setupFog first (vanilla expectation), then render our custom sky
        CustomSkyRenderer.render(poseStack, projectionMatrix, partialTick, engine, setupFog);
        ci.cancel();
    }

    /**
     * Cancel vanilla cloud rendering when atmosphere says clouds should be hidden.
     * renderClouds(PoseStack, Matrix4f, float, double, double, double)
     */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void customui$overrideClouds(PoseStack poseStack, Matrix4f projectionMatrix,
                                         float partialTick, double camX, double camY, double camZ,
                                         CallbackInfo ci) {
        AtmosphereEngine engine = AtmosphereEngine.getInstance();
        if (engine.shouldHideClouds()) {
            ci.cancel();
        }
    }
}
