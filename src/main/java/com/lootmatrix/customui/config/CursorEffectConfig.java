package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Configuration for cursor visual effects (trail and ripple).
 */
public class CursorEffectConfig {

    public static final ForgeConfigSpec SPEC;
    public static final CursorEffectConfig INSTANCE;

    static {
        Pair<CursorEffectConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(CursorEffectConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== General Settings ====================
    /**
     * Master switch for all cursor effects
     */
    public final ForgeConfigSpec.BooleanValue enabled;

    // ==================== Trail Effect Settings ====================
    /**
     * Enable/disable mouse trail effect
     */
    public final ForgeConfigSpec.BooleanValue trailEnabled;

    /**
     * Maximum number of trail points
     */
    public final ForgeConfigSpec.IntValue trailMaxPoints;

    /**
     * Trail lifetime in milliseconds
     */
    public final ForgeConfigSpec.IntValue trailLifetimeMs;

    /**
     * Trail head alpha (opacity at cursor position)
     */
    public final ForgeConfigSpec.DoubleValue trailAlphaHead;

    /**
     * Trail head width in pixels
     */
    public final ForgeConfigSpec.DoubleValue trailWidthHead;

    /**
     * Trail tail width in pixels
     */
    public final ForgeConfigSpec.DoubleValue trailWidthTail;
    public final ForgeConfigSpec.DoubleValue trailSmoothing;
    public final ForgeConfigSpec.DoubleValue trailOuterGlowWidthMultiplier;
    public final ForgeConfigSpec.DoubleValue trailMiddleWidthMultiplier;
    public final ForgeConfigSpec.DoubleValue trailCoreWidthMultiplier;

    // ==================== Ripple Effect Settings ====================
    /**
     * Enable/disable mouse click ripple effect
     */
    public final ForgeConfigSpec.BooleanValue rippleEnabled;

    /**
     * Ripple animation duration in milliseconds
     */
    public final ForgeConfigSpec.IntValue rippleDurationMs;

    /**
     * Maximum ripple radius in pixels
     */
    public final ForgeConfigSpec.DoubleValue rippleRadius;

    /**
     * Ripple alpha (opacity)
     */
    public final ForgeConfigSpec.DoubleValue rippleAlpha;

    /**
     * Maximum number of simultaneous ripples
     */
    public final ForgeConfigSpec.IntValue rippleMaxCount;
    public final ForgeConfigSpec.DoubleValue rippleWaveAmplitude;
    public final ForgeConfigSpec.IntValue rippleWaveFrequency;
    public final ForgeConfigSpec.IntValue rippleParticleCount;
    public final ForgeConfigSpec.DoubleValue rippleParticleSpeed;
    public final ForgeConfigSpec.IntValue rippleParticleLifetimeMs;
    public final ForgeConfigSpec.IntValue rippleParticleMaxCount;

    // ==================== Custom Cursor Settings ====================
    public final ForgeConfigSpec.BooleanValue cursorEnabled;
    public final ForgeConfigSpec.BooleanValue cursorHideSystemCursor;
    public final ForgeConfigSpec.ConfigValue<String> cursorScope;
    public final ForgeConfigSpec.DoubleValue cursorDotRadius;
    public final ForgeConfigSpec.DoubleValue cursorGlowRadius;
    public final ForgeConfigSpec.DoubleValue cursorPulseAmplitude;
    public final ForgeConfigSpec.DoubleValue cursorAlpha;

    private CursorEffectConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Cursor Effect Configuration")
                .push("cursor_effects");

        // General
        enabled = builder
                .comment("Master switch for all cursor effects")
                .define("enabled", true);

        // Trail Effect
        builder.comment("Mouse Trail Effect Settings").push("trail");

        trailEnabled = builder
                .comment("Enable mouse trail effect")
                .define("enabled", true);

        trailMaxPoints = builder
                .comment("Maximum number of trail points")
                .defineInRange("maxPoints", 150, 10, 500);

        trailLifetimeMs = builder
                .comment("Trail lifetime in milliseconds")
                .defineInRange("lifetimeMs", 250, 50, 1000);

        trailAlphaHead = builder
                .comment("Trail head alpha (opacity at cursor position)")
                .defineInRange("alphaHead", 0.85, 0.1, 1.0);

        trailWidthHead = builder
                .comment("Trail head width in pixels")
                .defineInRange("widthHead", 4.0, 1.0, 20.0);

        trailWidthTail = builder
                .comment("Trail tail width in pixels")
                .defineInRange("widthTail", 0.5, 0.1, 10.0);

        trailSmoothing = builder
                .comment("Render-time trail smoothing strength (0.0 = off, 1.0 = strongest)")
                .defineInRange("smoothing", 0.42, 0.0, 1.0);

        trailOuterGlowWidthMultiplier = builder
                .comment("Width multiplier for the outer glow trail layer")
                .defineInRange("outerGlowWidthMultiplier", 2.9, 1.0, 8.0);

        trailMiddleWidthMultiplier = builder
                .comment("Width multiplier for the middle trail layer")
                .defineInRange("middleWidthMultiplier", 1.45, 0.5, 5.0);

        trailCoreWidthMultiplier = builder
                .comment("Width multiplier for the bright core trail layer")
                .defineInRange("coreWidthMultiplier", 0.42, 0.1, 2.0);

        builder.pop();

        // Ripple Effect
        builder.comment("Mouse Click Ripple Effect Settings").push("ripple");

        rippleEnabled = builder
                .comment("Enable mouse click ripple effect")
                .define("enabled", true);

        rippleDurationMs = builder
                .comment("Ripple animation duration in milliseconds")
                .defineInRange("durationMs", 520, 100, 2000);

        rippleRadius = builder
                .comment("Maximum ripple radius in pixels")
                .defineInRange("radius", 44.0, 10.0, 200.0);

        rippleAlpha = builder
                .comment("Ripple alpha (opacity)")
                .defineInRange("alpha", 0.8, 0.1, 1.0);

        rippleMaxCount = builder
                .comment("Maximum number of simultaneous ripples")
                .defineInRange("maxCount", 10, 1, 50);

        rippleWaveAmplitude = builder
                .comment("Maximum ripple edge wave amplitude in pixels")
                .defineInRange("waveAmplitude", 4.0, 0.0, 20.0);

        rippleWaveFrequency = builder
                .comment("Number of major waves around the ripple edge")
                .defineInRange("waveFrequency", 6, 1, 16);

        rippleParticleCount = builder
                .comment("Particles emitted from each click ripple")
                .defineInRange("particleCount", 30, 0, 128);

        rippleParticleSpeed = builder
                .comment("Base particle spread distance in pixels")
                .defineInRange("particleSpeed", 46.0, 1.0, 200.0);

        rippleParticleLifetimeMs = builder
                .comment("Base particle lifetime in milliseconds")
                .defineInRange("particleLifetimeMs", 520, 100, 2000);

        rippleParticleMaxCount = builder
                .comment("Maximum simultaneous click particles")
                .defineInRange("particleMaxCount", 180, 0, 500);

        builder.pop();

        builder.comment("Custom Cursor Settings").push("cursor");

        cursorEnabled = builder
                .comment("Enable the custom glowing cursor dot")
                .define("enabled", true);

        cursorHideSystemCursor = builder
                .comment("Hide the operating system cursor while drawing the custom cursor")
                .define("hideSystemCursor", true);

        cursorScope = builder
                .comment("Custom cursor scope: GUI_ONLY or VISIBLE_CURSOR")
                .defineInList("scope", "GUI_ONLY", List.of("GUI_ONLY", "VISIBLE_CURSOR"));

        cursorDotRadius = builder
                .comment("Bright cursor dot radius in pixels")
                .defineInRange("dotRadius", 2.0, 0.5, 12.0);

        cursorGlowRadius = builder
                .comment("Cursor glow radius in pixels")
                .defineInRange("glowRadius", 8.0, 2.0, 40.0);

        cursorPulseAmplitude = builder
                .comment("Cursor pulse radius amplitude in pixels")
                .defineInRange("pulseAmplitude", 0.8, 0.0, 8.0);

        cursorAlpha = builder
                .comment("Custom cursor alpha")
                .defineInRange("alpha", 0.9, 0.1, 1.0);

        builder.pop();

        builder.pop();
    }
}

