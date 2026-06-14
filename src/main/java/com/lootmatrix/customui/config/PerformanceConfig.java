package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Performance-related configuration options.
 * These settings allow users to trade visual features for better FPS.
 */
public class PerformanceConfig {

    public static final ForgeConfigSpec SPEC;
    public static final PerformanceConfig INSTANCE;

    static {
        Pair<PerformanceConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(PerformanceConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== HUD Rendering Optimizations ====================
    
    /**
     * Enable batched rendering for HUD elements.
     * Reduces draw calls by combining multiple render operations.
     * Recommended: true (significant performance improvement)
     */
    public final ForgeConfigSpec.BooleanValue enableBatchedRendering;
    
    /**
     * Show economy display in health overlay.
     * Disabling can improve performance slightly.
     */
    public final ForgeConfigSpec.BooleanValue showEconomyDisplay;
    
    /**
     * Show air/oxygen display in health overlay.
     * Disabling can improve performance slightly.
     */
    public final ForgeConfigSpec.BooleanValue showAirDisplay;
    
    /**
     * Show experience level 100 effects (circular expansion, fade animation).
     * Disabling can improve performance when at level 100.
     */
    public final ForgeConfigSpec.BooleanValue showExp100Effects;
    
    /**
     * Enable UI sway effect (subtle movement based on player motion).
     * Disabling can improve performance slightly.
     */
    public final ForgeConfigSpec.BooleanValue enableUISway;
    
    /**
     * Reduce HUD update frequency (updates every N ticks instead of every frame).
     * Higher values = better performance but less smooth animations.
     * 1 = every tick (default), 2 = every other tick, etc.
     */
    public final ForgeConfigSpec.IntValue hudUpdateInterval;

    private PerformanceConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Performance Optimization Settings")
                .push("performance");

        enableBatchedRendering = builder
                .comment("Enable batched rendering for HUD elements",
                        "Reduces draw calls significantly - HIGHLY RECOMMENDED",
                        "Disable only if you experience rendering issues")
                .define("enableBatchedRendering", true);

        showEconomyDisplay = builder
                .comment("Show economy display ($) in health overlay",
                        "Disabling can improve performance slightly")
                .define("showEconomyDisplay", true);

        showAirDisplay = builder
                .comment("Show air/oxygen display when underwater",
                        "Disabling can improve performance slightly")
                .define("showAirDisplay", true);

        showExp100Effects = builder
                .comment("Show special effects when experience level is 100",
                        "Includes circular expansion and fade animations",
                        "Disabling can improve performance when at level 100")
                .define("showExp100Effects", true);

        enableUISway = builder
                .comment("Enable UI sway effect (subtle movement based on player motion)",
                        "Disabling can improve performance slightly")
                .define("enableUISway", true);

        hudUpdateInterval = builder
                .comment("HUD update interval in ticks (1 = every tick, 2 = every other tick)",
                        "Higher values improve performance but reduce animation smoothness",
                        "Recommended: 1 for smooth animations, 2-3 for better performance")
                .defineInRange("hudUpdateInterval", 1, 1, 10);

        builder.pop();
    }
}
