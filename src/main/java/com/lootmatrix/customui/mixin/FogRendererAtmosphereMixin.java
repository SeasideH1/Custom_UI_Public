package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.atmosphere.AtmosphereEngine;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Applies atmosphere fog directly to shader parameters after vanilla/Forge fog setup.
 * This preserves the normal render-distance-based fog pipeline and avoids coupling
 * atmosphere preset switches to chunk visibility/loading heuristics.
 */
@Mixin(FogRenderer.class)
public class FogRendererAtmosphereMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(FogRendererAtmosphereMixin.class);
    private static final boolean DEBUG_FOG = Boolean.getBoolean("customui.debug.atmosphereFog");
    private static final int MAX_DEBUG_STATE_CACHE = 256;
    private static final Set<FogSetupLogState> customui$seenSetupStates = new LinkedHashSet<>();

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void customui$applyAtmosphereFog(Camera camera, FogRenderer.FogMode mode,
                                                    float renderDistance, boolean worldFog,
                                                    float partialTick, CallbackInfo ci) {
        AtmosphereEngine engine = AtmosphereEngine.getInstance();
        AtmosphereEngine.CachedFogConfig fogConfig = engine.getCachedFogConfig();
        float blend = engine.getBlendFactor(partialTick);

        customui$debugSetup(mode, worldFog, fogConfig != null, blend);

        if (fogConfig == null) return;

        // Only apply to terrain fog, not sky fog
        if (mode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }

        float vanillaNear = RenderSystem.getShaderFogStart();
        float vanillaFar = RenderSystem.getShaderFogEnd();

        float nearDist = lerp(vanillaNear, fogConfig.nearDistance(), blend);
        float farDist = Math.max(nearDist + 1.0f, lerp(vanillaFar, fogConfig.farDistance(), blend));

        RenderSystem.setShaderFogStart(nearDist);
        RenderSystem.setShaderFogEnd(farDist);
        RenderSystem.setShaderFogShape(fogConfig.shape());
    }

    private static float lerp(float vanilla, float target, float blend) {
        return vanilla + (target - vanilla) * blend;
    }

    private static void customui$debugSetup(FogRenderer.FogMode mode, boolean worldFog,
                                            boolean shouldOverrideFog, float blend) {
        if (!DEBUG_FOG) {
            return;
        }

        FogSetupLogState state = new FogSetupLogState(
                mode,
                worldFog,
                shouldOverrideFog,
                Math.round(blend * 1000.0f)
        );
        if (!customui$seenSetupStates.add(state)) {
            return;
        }
        if (customui$seenSetupStates.size() > MAX_DEBUG_STATE_CACHE) {
            customui$seenSetupStates.clear();
            customui$seenSetupStates.add(state);
        }

        LOGGER.info("[AtmosphereFog] setupFog state changed - mode: {}, worldFog: {}, shouldOverride: {}, blend: {}",
                mode, worldFog, shouldOverrideFog, blend);
    }

    private record FogSetupLogState(FogRenderer.FogMode mode, boolean worldFog,
                                    boolean shouldOverrideFog, int blendMilli) {
    }
}
