package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for custom window icon.
 * Allows replacing the default Minecraft window icon with custom icons.
 * If custom icons are not found, the default Minecraft icon will be used.
 */
public class WindowIconConfig {

    public static final ForgeConfigSpec SPEC;
    public static final WindowIconConfig INSTANCE;

    static {
        Pair<WindowIconConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(WindowIconConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== Toggle Configuration ====================

    /**
     * Enable or disable custom window icon
     */
    public final ForgeConfigSpec.BooleanValue enabled;

    /**
     * Use built-in mod icons instead of custom files from config folder
     */
    public final ForgeConfigSpec.BooleanValue useBuiltIn;

    // ==================== Custom Icon Path Configuration ====================

    /**
     * Path to custom 32x32 icon file (relative to config/customui/icons/)
     * Only used when useBuiltIn = false
     */
    public final ForgeConfigSpec.ConfigValue<String> customIcon32Path;

    /**
     * Path to custom 64x64 icon file (relative to config/customui/icons/)
     * Only used when useBuiltIn = false
     */
    public final ForgeConfigSpec.ConfigValue<String> customIcon64Path;

    private WindowIconConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Window Icon Configuration",
                        "===========================================",
                        "Customize the Minecraft window icon.",
                        "",
                        "Built-in icons are located at:",
                        "  assets/customui/textures/icons/window/icon_32x32.png",
                        "  assets/customui/textures/icons/window/icon_64x64.png",
                        "",
                        "Custom icons should be placed at:",
                        "  config/customui/icons/",
                        "",
                        "Supported sizes: 32x32, 64x64 (PNG format, 32-bit RGBA)",
                        "If custom icons are not found, the default Minecraft icon will be used.",
                        "===========================================")
                .push("window_icon");

        // Toggle
        enabled = builder
                .comment("Enable custom window icon (requires game restart to take effect)")
                .define("enabled", true);

        useBuiltIn = builder
                .comment("Use built-in mod icons (true) or custom icons from config folder (false)")
                .define("useBuiltIn", true);

        builder.comment("Custom icon paths (only used when useBuiltIn = false)",
                        "Place custom icons in: config/customui/icons/")
                .push("custom_paths");

        customIcon32Path = builder
                .comment("Custom 32x32 icon filename")
                .define("icon32", "icon_32x32.png");

        customIcon64Path = builder
                .comment("Custom 64x64 icon filename")
                .define("icon64", "icon_64x64.png");

        builder.pop(); // custom_paths
        builder.pop(); // window_icon
    }
}
