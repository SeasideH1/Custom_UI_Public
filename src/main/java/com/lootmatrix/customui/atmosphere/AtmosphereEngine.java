package com.lootmatrix.customui.atmosphere;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import javax.annotation.Nullable;

/**
 * Client-side singleton that manages the active atmosphere preset state.
 * Handles smooth transitions (fade-in / fade-out) between presets.
 */
public class AtmosphereEngine {

    private static final AtmosphereEngine INSTANCE = new AtmosphereEngine();

    public static AtmosphereEngine getInstance() {
        return INSTANCE;
    }

    // ---- Active state ----
    @Nullable
    private AtmospherePreset activePreset;
    private float blendFactor;        // 0.0 = vanilla, 1.0 = fully custom
    private boolean fadingIn;         // true = transitioning toward 1.0
    private boolean fadingOut;        // true = transitioning toward 0.0
    private int transitionTick;
    private int transitionTicksTotal;
    private AtmospherePreset.EasingType transitionEasing = AtmospherePreset.EasingType.EASE_IN_OUT;

    // ---- Previous (for crossfade) ----
    private float prevBlendFactor;

    // ---- Cached render state ----
    @Nullable
    private CachedFogConfig cachedFogConfig;
    private boolean cachedHasCustomSky;
    private boolean cachedShouldHideClouds;

    // ==================== Public API ====================

    public void applyPreset(AtmospherePreset preset) {
        this.activePreset = preset;
        refreshRenderStateCache();
        this.prevBlendFactor = this.blendFactor;
        this.fadingIn = true;
        this.fadingOut = false;
        this.transitionTick = 0;
        this.transitionTicksTotal = Math.max(1, preset.fadeInTicks);
        this.transitionEasing = preset.easing;
        if (this.blendFactor <= 0f) {
            this.prevBlendFactor = 0f;
            this.blendFactor = AtmospherePreset.applyEasing(
                    this.transitionEasing,
                    1.0f / this.transitionTicksTotal
            );
        }
    }

    public void clearPreset() {
        if (activePreset == null) return;
        this.prevBlendFactor = this.blendFactor;
        this.fadingIn = false;
        this.fadingOut = true;
        this.transitionTick = 0;
        this.transitionTicksTotal = Math.max(1, activePreset.fadeOutTicks);
    }

    public void forceStop() {
        // Only remove night vision if we were actively applying it
        boolean wasApplyingNightVision = isActive() && activePreset != null 
                && activePreset.ambient != null && activePreset.ambient.nightVision;
        
        this.activePreset = null;
        this.blendFactor = 0f;
        this.prevBlendFactor = 0f;
        this.fadingIn = false;
        this.fadingOut = false;
        refreshRenderStateCache();
        CustomSkyRenderer.cleanup();
        
        if (wasApplyingNightVision) {
            removeNightVision();
        }
    }

    public boolean isActive() {
        return activePreset != null && blendFactor > 0f;
    }

    @Nullable
    public AtmospherePreset getActivePreset() {
        return activePreset;
    }

    public float getBlendFactor() {
        return blendFactor;
    }

    /**
     * Get blend factor interpolated with partial tick for smooth rendering.
     */
    public float getBlendFactor(float partialTick) {
        if (!fadingIn && !fadingOut) return blendFactor;
        return prevBlendFactor + (blendFactor - prevBlendFactor) * partialTick;
    }

    // ==================== Per-section queries ====================

    public boolean shouldOverrideFog() {
        return blendFactor > 0f && cachedFogConfig != null;
    }

    public boolean shouldOverrideSky() {
        return blendFactor > 0f && cachedHasCustomSky;
    }

    public boolean shouldHideClouds() {
        return blendFactor > 0f && cachedShouldHideClouds;
    }

    @Nullable
    public CachedFogConfig getCachedFogConfig() {
        return shouldOverrideFog() ? cachedFogConfig : null;
    }

    // ==================== Tick ====================

    public void tick() {
        prevBlendFactor = blendFactor;

        if (fadingIn) {
            transitionTick++;
            float raw = Math.min(1f, (float) transitionTick / transitionTicksTotal);
            blendFactor = AtmospherePreset.applyEasing(transitionEasing, raw);
            if (raw >= 1f) {
                fadingIn = false;
                blendFactor = 1f;
            }
        } else if (fadingOut) {
            transitionTick++;
            float raw = Math.min(1f, (float) transitionTick / transitionTicksTotal);
            blendFactor = 1f - AtmospherePreset.applyEasing(transitionEasing, raw);
            if (raw >= 1f) {
                fadingOut = false;
                blendFactor = 0f;
                // Only remove night vision if we were actively applying it
                boolean wasApplyingNightVision = activePreset != null 
                        && activePreset.ambient != null && activePreset.ambient.nightVision;
                activePreset = null;
                refreshRenderStateCache();
                CustomSkyRenderer.cleanup();
                if (wasApplyingNightVision) {
                    removeNightVision();
                }
            }
        }

        // Apply night vision if configured
        if (isActive() && activePreset.ambient != null && activePreset.ambient.nightVision) {
            applyNightVision();
        }
    }

    // ==================== Night Vision Helpers ====================

    private static final String ATMOSPHERE_NIGHT_VISION_TAG = "customui_atmosphere";

    private void applyNightVision() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Check if we already have our atmosphere night vision effect
            MobEffectInstance current = mc.player.getEffect(MobEffects.NIGHT_VISION);
            boolean hasOurEffect = current != null && current.isAmbient() 
                    && current.getDuration() >= 220 && current.getDuration() <= 300;
            
            if (!hasOurEffect) {
                // Apply night vision with ambient flag and specific duration range
                // Duration 300 ticks = 15 seconds, refreshed every tick
                mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                        300, 0, true, false, false));
            }
        }
    }

    private void removeNightVision() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            MobEffectInstance effect = mc.player.getEffect(MobEffects.NIGHT_VISION);
            // Only remove if it's our ambient effect with the specific duration range
            if (effect != null && effect.isAmbient() 
                    && effect.getDuration() >= 220 && effect.getDuration() <= 300) {
                mc.player.removeEffect(MobEffects.NIGHT_VISION);
            }
        }
    }

    // ==================== Interpolation Helpers ====================

    /**
     * Lerp a float value between vanilla and target, using current blend factor.
     */
    public float lerp(float vanilla, float target, float partialTick) {
        float b = getBlendFactor(partialTick);
        return vanilla + (target - vanilla) * b;
    }

    private void refreshRenderStateCache() {
        if (activePreset == null) {
            cachedFogConfig = null;
            cachedHasCustomSky = false;
            cachedShouldHideClouds = false;
            return;
        }

        cachedFogConfig = activePreset.fog != null ? CachedFogConfig.from(activePreset.fog) : null;
        cachedHasCustomSky = activePreset.sky != null
                && activePreset.sky.type != AtmospherePreset.SkyType.VANILLA;
        cachedShouldHideClouds = activePreset.clouds != null && !activePreset.clouds.visible;
    }

    public record CachedFogConfig(float r, float g, float b,
                                  float nearDistance, float farDistance,
                                  FogShape shape) {
        private static CachedFogConfig from(AtmospherePreset.FogConfig fogConfig) {
            float clampedNear = Math.max(0.0f, fogConfig.nearDistance);
            float clampedFar = Math.max(clampedNear + 1.0f, fogConfig.farDistance);
            FogShape shape = fogConfig.shape == AtmospherePreset.FogShapeType.CYLINDER
                    ? FogShape.CYLINDER
                    : FogShape.SPHERE;
            return new CachedFogConfig(
                    fogConfig.r,
                    fogConfig.g,
                    fogConfig.b,
                    clampedNear,
                    clampedFar,
                    shape
            );
        }
    }
}
