package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for the Scoreboard Overlay.
 * Controls position, size, and appearance.
 */
public class ScoreboardOverlayConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ScoreboardOverlayConfig INSTANCE;

    static {
        Pair<ScoreboardOverlayConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(ScoreboardOverlayConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== Position Configuration ====================
    /**
     * Horizontal offset from center (positive = right, negative = left)
     */
    public final ForgeConfigSpec.IntValue offsetX;

    /**
     * Vertical offset from top (positive = down)
     */
    public final ForgeConfigSpec.IntValue offsetY;

    // ==================== Size Configuration ====================
    /**
     * Progress bar width
     */
    public final ForgeConfigSpec.IntValue barWidth;

    /**
     * Progress bar height
     */
    public final ForgeConfigSpec.IntValue barHeight;

    /**
     * Icon size
     */
    public final ForgeConfigSpec.IntValue iconSize;

    /**
     * Spacing between sections
     */
    public final ForgeConfigSpec.IntValue sectionSpacing;

    /**
     * Text scale (1.0 = normal size)
     */
    public final ForgeConfigSpec.DoubleValue textScale;

    // ==================== Color Presets ====================
    /**
     * Timer color preset: white
     */
    public final ForgeConfigSpec.IntValue colorPresetWhite;

    /**
     * Timer color preset: yellow (default)
     */
    public final ForgeConfigSpec.IntValue colorPresetYellow;

    /**
     * Timer color preset: red
     */
    public final ForgeConfigSpec.IntValue colorPresetRed;

    /**
     * Timer color preset: green
     */
    public final ForgeConfigSpec.IntValue colorPresetGreen;

    /**
     * Timer color preset: blue
     */
    public final ForgeConfigSpec.IntValue colorPresetBlue;

    /**
     * Timer color preset: orange
     */
    public final ForgeConfigSpec.IntValue colorPresetOrange;

    /**
     * Timer color preset: purple
     */
    public final ForgeConfigSpec.IntValue colorPresetPurple;

    /**
     * Timer color preset: cyan
     */
    public final ForgeConfigSpec.IntValue colorPresetCyan;

    // ==================== Appearance Configuration ====================
    /**
     * Progress bar background alpha (0.0 - 1.0)
     */
    public final ForgeConfigSpec.DoubleValue barBackgroundAlpha;

    /**
     * Progress bar border alpha (0.0 - 1.0)
     */
    public final ForgeConfigSpec.DoubleValue barBorderAlpha;

    public ScoreboardOverlayConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Scoreboard Overlay Configuration")
                .push("scoreboard_overlay");

        // Position
        builder.comment("Position Settings").push("position");

        offsetX = builder
                .comment("Horizontal offset from screen center (positive = right, negative = left)")
                .defineInRange("offsetX", 0, -500, 500);

        offsetY = builder
                .comment("Vertical offset from top edge (positive = down)")
                .defineInRange("offsetY", 14, 0, 200);

        builder.pop();

        // Size
        builder.comment("Size Settings").push("size");

        barWidth = builder
                .comment("Progress bar width in pixels")
                .defineInRange("barWidth", 80, 20, 200);

        barHeight = builder
                .comment("Progress bar height in pixels")
                .defineInRange("barHeight", 6, 3, 20);

        iconSize = builder
                .comment("Team icon size in pixels")
                .defineInRange("iconSize", 16, 8, 32);

        sectionSpacing = builder
                .comment("Spacing between UI sections in pixels")
                .defineInRange("sectionSpacing", 3, 0, 20);

        textScale = builder
                .comment("Text scale multiplier (1.0 = normal)")
                .defineInRange("textScale", 1.0, 0.5, 2.0);

        builder.pop();

        // Color Presets
        builder.comment("Color Presets (ARGB hex values)").push("color_presets");

        colorPresetWhite = builder
                .comment("White color preset")
                .defineInRange("white", 0xFFFFFFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetYellow = builder
                .comment("Yellow color preset (default timer color)")
                .defineInRange("yellow", 0xFFFFFF00, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetRed = builder
                .comment("Red color preset")
                .defineInRange("red", 0xFFFF4444, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetGreen = builder
                .comment("Green color preset")
                .defineInRange("green", 0xFF44FF44, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetBlue = builder
                .comment("Blue color preset")
                .defineInRange("blue", 0xFF4444FF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetOrange = builder
                .comment("Orange color preset")
                .defineInRange("orange", 0xFFFF8800, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetPurple = builder
                .comment("Purple color preset")
                .defineInRange("purple", 0xFFAA44FF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        colorPresetCyan = builder
                .comment("Cyan color preset")
                .defineInRange("cyan", 0xFF44FFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        builder.pop();

        // Appearance
        builder.comment("Appearance Settings").push("appearance");

        barBackgroundAlpha = builder
                .comment("Progress bar background transparency (0.0 = invisible, 1.0 = opaque)")
                .defineInRange("barBackgroundAlpha", 0.35, 0.0, 1.0);

        barBorderAlpha = builder
                .comment("Progress bar border transparency (0.0 = invisible, 1.0 = opaque)")
                .defineInRange("barBorderAlpha", 0.5, 0.0, 1.0);

        builder.pop();

        builder.pop();
    }

    // ==================== Static Color Preset Defaults (for server-side use) ====================
    public static final int DEFAULT_WHITE = 0xFFFFFFFF;
    public static final int DEFAULT_YELLOW = 0xFFFFFF00;
    public static final int DEFAULT_RED = 0xFFFF4444;
    public static final int DEFAULT_GREEN = 0xFF44FF44;
    public static final int DEFAULT_BLUE = 0xFF4444FF;
    public static final int DEFAULT_ORANGE = 0xFFFF8800;
    public static final int DEFAULT_PURPLE = 0xFFAA44FF;
    public static final int DEFAULT_CYAN = 0xFF44FFFF;

    /** Error value for color not found (cannot use -1 because 0xFFFFFFFF = -1 = white) */
    public static final int COLOR_NOT_FOUND = Integer.MIN_VALUE;

    /**
     * Get a color preset by name.
     * Uses hardcoded defaults that work on both client and server.
     * @param name Preset name (white, yellow, red, green, blue, orange, purple, cyan) or hex color
     * @return The color value, or COLOR_NOT_FOUND if not found
     */
    public int getColorPreset(String name) {
        if (name == null || name.isEmpty()) {
            return COLOR_NOT_FOUND;
        }

        // First try preset names
        String lower = name.toLowerCase();
        return switch (lower) {
            case "white" -> DEFAULT_WHITE;
            case "yellow" -> DEFAULT_YELLOW;
            case "red" -> DEFAULT_RED;
            case "green" -> DEFAULT_GREEN;
            case "blue" -> DEFAULT_BLUE;
            case "orange" -> DEFAULT_ORANGE;
            case "purple" -> DEFAULT_PURPLE;
            case "cyan" -> DEFAULT_CYAN;
            default -> tryParseHexColor(name);
        };
    }

    /**
     * Try to parse a hex color string.
     */
    private int tryParseHexColor(String name) {
        try {
            String hex = name.replace("#", "").replace("0x", "");
            int color = (int) Long.parseLong(hex, 16);
            // Ensure alpha channel if not specified (6-digit hex)
            if (hex.length() <= 6 && (color & 0xFF000000) == 0) {
                color |= 0xFF000000;
            }
            return color;
        } catch (NumberFormatException e) {
            return COLOR_NOT_FOUND;
        }
    }
}
