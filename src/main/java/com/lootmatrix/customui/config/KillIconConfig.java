package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for kill icon display.
 * Configures: position offset, animation duration, appearance settings.
 */
public class KillIconConfig {

    public static final ForgeConfigSpec SPEC;
    public static final KillIconConfig INSTANCE;

    static {
        Pair<KillIconConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(KillIconConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== Toggle Configuration ====================
    /**
     * Enable or disable kill icon display
     */
    public final ForgeConfigSpec.BooleanValue enabled;

    // ==================== Position Configuration ====================
    /**
     * Vertical offset from screen center (positive = down, negative = up)
     */
    public final ForgeConfigSpec.DoubleValue verticalOffset;

    /**
     * Horizontal offset from screen center (positive = right, negative = left)
     */
    public final ForgeConfigSpec.DoubleValue horizontalOffset;

    // ==================== Size Configuration ====================
    /**
     * Initial size of kill icon when it first appears (in pixels)
     */
    public final ForgeConfigSpec.DoubleValue initialSize;

    /**
     * Final size of kill icon after appear animation (in pixels)
     */
    public final ForgeConfigSpec.DoubleValue finalSize;

    /**
     * Horizontal spacing between multiple kill icons
     */
    public final ForgeConfigSpec.DoubleValue iconSpacing;

    /**
     * Maximum number of kill icons visible at once
     */
    public final ForgeConfigSpec.IntValue maxVisibleIcons;

    // ==================== Animation Duration Configuration ====================
    /**
     * Duration of the appear animation (large+transparent -> small+opaque) in seconds
     */
    public final ForgeConfigSpec.DoubleValue appearDuration;

    /**
     * Duration the icon stays fully visible before fading (in seconds)
     */
    public final ForgeConfigSpec.DoubleValue holdDuration;

    /**
     * Duration of the fade-out animation (in seconds)
     */
    public final ForgeConfigSpec.DoubleValue fadeDuration;

    // ==================== Headshot Ripple Effect Configuration ====================
    /**
     * Duration of the headshot ripple effect (in seconds)
     */
    public final ForgeConfigSpec.DoubleValue rippleDuration;

    /**
     * Starting radius of the ripple effect
     */
    public final ForgeConfigSpec.DoubleValue rippleStartRadius;

    /**
     * Ending radius of the ripple effect
     */
    public final ForgeConfigSpec.DoubleValue rippleEndRadius;

    /**
     * Ripple effect color (RGB hex, e.g., 0xFF6600 for orange)
     */
    public final ForgeConfigSpec.IntValue rippleColor;

    // ==================== Sway Effect Configuration ====================
    /**
     * Sway effect multiplier (0 = no sway, 1 = full sway)
     */
    public final ForgeConfigSpec.DoubleValue swayMultiplier;

    private KillIconConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Kill Icon Overlay Configuration")
                .push("kill_icon");

        // Toggle
        enabled = builder
                .comment("Enable or disable kill icon display")
                .define("enabled", true);

        // Position settings
        builder.comment("Position Settings").push("position");

        verticalOffset = builder
                .comment("Vertical offset from screen center (positive = down, negative = up)")
                .defineInRange("verticalOffset", 100.0, -500.0, 500.0);

        horizontalOffset = builder
                .comment("Horizontal offset from screen center (positive = right, negative = left)")
                .defineInRange("horizontalOffset", 0.0, -500.0, 500.0);

        builder.pop();

        // Size settings
        builder.comment("Size Settings").push("size");

        initialSize = builder
                .comment("Initial size of kill icon when it first appears (in pixels)")
                .defineInRange("initialSize", 40.0, 10.0, 200.0);

        finalSize = builder
                .comment("Final size of kill icon after appear animation (in pixels)")
                .defineInRange("finalSize", 16.0, 8.0, 100.0);

        iconSpacing = builder
                .comment("Horizontal spacing between multiple kill icons")
                .defineInRange("iconSpacing", 20.0, 5.0, 100.0);

        maxVisibleIcons = builder
                .comment("Maximum number of kill icons visible at once")
                .defineInRange("maxVisibleIcons", 8, 1, 20);

        builder.pop();

        // Animation duration settings
        builder.comment("Animation Duration Settings").push("animation");

        appearDuration = builder
                .comment("Duration of the appear animation (large+transparent -> small+opaque) in seconds")
                .defineInRange("appearDuration", 0.25, 0.05, 2.0);

        holdDuration = builder
                .comment("Duration the icon stays fully visible before fading (in seconds)")
                .defineInRange("holdDuration", 2.5, 0.5, 10.0);

        fadeDuration = builder
                .comment("Duration of the fade-out animation (in seconds)")
                .defineInRange("fadeDuration", 0.4, 0.1, 2.0);

        builder.pop();

        // Headshot ripple effect settings
        builder.comment("Headshot Ripple Effect Settings").push("ripple");

        rippleDuration = builder
                .comment("Duration of the headshot ripple effect (in seconds)")
                .defineInRange("rippleDuration", 0.5, 0.1, 2.0);

        rippleStartRadius = builder
                .comment("Starting radius of the ripple effect")
                .defineInRange("rippleStartRadius", 10.0, 1.0, 50.0);

        rippleEndRadius = builder
                .comment("Ending radius of the ripple effect")
                .defineInRange("rippleEndRadius", 40.0, 20.0, 200.0);

        rippleColor = builder
                .comment("Ripple effect color (RGB hex without alpha, e.g., 0xFF6600 for orange)")
                .defineInRange("rippleColor", 0xFF6600, 0x000000, 0xFFFFFF);

        builder.pop();

        // Sway effect settings
        builder.comment("Sway Effect Settings").push("sway");

        swayMultiplier = builder
                .comment("Sway effect multiplier (0 = no sway, 1 = full sway)")
                .defineInRange("swayMultiplier", 0.2, 0.0, 1.0);

        builder.pop();

        builder.pop();
    }
}

