package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for damage number display.
 * Configures: vertical offset, horizontal offset, spacing, colors.
 */
public class DamageNumberConfig {

    public static final ForgeConfigSpec SPEC;
    public static final DamageNumberConfig INSTANCE;

    static {
        Pair<DamageNumberConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(DamageNumberConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== Position Configuration ====================
    /**
     * Horizontal offset from the left side of screen center (negative = left, positive = right)
     */
    public final ForgeConfigSpec.DoubleValue horizontalOffset;

    /**
     * Vertical offset from the center of screen (negative = up, positive = down)
     */
    public final ForgeConfigSpec.DoubleValue verticalOffset;

    /**
     * Spacing between damage number entries
     */
    public final ForgeConfigSpec.DoubleValue spacing;

    // ==================== Appearance Configuration ====================

    /**
     * Scale of damage numbers
     */
    public final ForgeConfigSpec.DoubleValue scale;

    /**
     * Normal damage number color (hex RGB, e.g., 0xFFFFFF for white)
     */
    public final ForgeConfigSpec.IntValue normalColor;

    /**
     * Kill damage number color (hex RGB, e.g., 0xFF6B6B for light red)
     */
    public final ForgeConfigSpec.IntValue killColor;

    // ==================== Animation Configuration ====================
    /**
     * Duration that damage numbers stay fully visible before fading (in seconds)
     */
    public final ForgeConfigSpec.DoubleValue stayDuration;

    /**
     * Duration of the fade-out animation (in seconds)
     */
    public final ForgeConfigSpec.DoubleValue fadeDuration;

    // ==================== Toggle Configuration ====================
    /**
     * Enable or disable damage number display
     */
    public final ForgeConfigSpec.BooleanValue enabled;

    /**
     * Show drop shadow on damage numbers
     */
    public final ForgeConfigSpec.BooleanValue dropShadow;

    /**
     * Number of decimal places to show (0 = integer only, 1 = one decimal, etc.)
     * Using decimals shows more precise accumulated damage values
     */
    public final ForgeConfigSpec.IntValue decimalPlaces;

    /**
     * Rounding mode when decimalPlaces is 0:
     * 0 = round (standard rounding, 0.5 rounds up)
     * 1 = ceil (always round up)
     * 2 = floor (always round down)
     */
    public final ForgeConfigSpec.IntValue roundingMode;

    private DamageNumberConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Damage Number Display Configuration")
                .push("damage_numbers");

        // Position settings
        builder.comment("Position Settings").push("position");

        horizontalOffset = builder
                .comment("Horizontal offset from left of screen center (negative = left, positive = right)")
                .defineInRange("horizontalOffset", -50.0, -500.0, 500.0);

        verticalOffset = builder
                .comment("Vertical offset from screen center (negative = up, positive = down)")
                .defineInRange("verticalOffset", 0.0, -500.0, 500.0);

        spacing = builder
                .comment("Vertical spacing between damage number entries")
                .defineInRange("spacing", 10.0, 0.0, 50.0);

        builder.pop();

        // Appearance settings
        builder.comment("Appearance Settings").push("appearance");


        scale = builder
                .comment("Scale of damage numbers")
                .defineInRange("scale", 1, 0.5, 5.0);

        normalColor = builder
                .comment("Normal damage number color (hex RGB without alpha, e.g., 0xFFFFFF for white)")
                .defineInRange("normalColor", 0xFFFFFF, 0x000000, 0xFFFFFF);

        killColor = builder
                .comment("Kill damage number color (hex RGB without alpha, e.g., 0xE05555 for medium red)")
                .defineInRange("killColor", 0xFC0000, 0x000000, 0xFFFFFF);

        dropShadow = builder
                .comment("Show drop shadow on damage numbers")
                .define("dropShadow", true);

        decimalPlaces = builder
                .comment("Number of decimal places to show (0 = integer, 1-2 = with decimals)",
                         "Using decimals shows precise accumulated damage without rounding errors")
                .defineInRange("decimalPlaces", 0, 0, 2);

        roundingMode = builder
                .comment("Rounding mode when decimalPlaces is 0:",
                         "0 = round (standard rounding, 0.5 rounds up)",
                         "1 = ceil (always round up)",
                         "2 = floor (always round down)")
                .defineInRange("roundingMode", 2, 0, 2);

        builder.pop();

        // Animation settings
        builder.comment("Animation Settings").push("animation");

        stayDuration = builder
                .comment("Duration that damage numbers stay fully visible before fading (in seconds)")
                .defineInRange("stayDuration", 3.0, 0.0, 10.0);

        fadeDuration = builder
                .comment("Duration of the fade-out animation (in seconds)")
                .defineInRange("fadeDuration", 0.6, 0.1, 10.0);

        builder.pop();

        // Toggle settings
        builder.comment("Toggle Settings").push("toggle");

        enabled = builder
                .comment("Enable or disable damage number display")
                .define("enabled", true);

        builder.pop();
        builder.pop();
    }
}

