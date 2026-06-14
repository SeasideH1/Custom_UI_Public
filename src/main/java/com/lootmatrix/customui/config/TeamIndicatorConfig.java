package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for Team Indicator (Team Glow) effect.
 */
public class TeamIndicatorConfig {

    public static final ForgeConfigSpec SPEC;
    public static final TeamIndicatorConfig INSTANCE;

    static {
        Pair<TeamIndicatorConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(TeamIndicatorConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== General Settings ====================
    /**
     * Master switch for team glow effect
     */
    public final ForgeConfigSpec.BooleanValue enabled;

    /**
     * Maximum distance for team glow effect (in blocks)
     */
    public final ForgeConfigSpec.DoubleValue maxDistance;

    /**
     * Maximum number of players to process per frame
     */
    public final ForgeConfigSpec.IntValue maxPlayersToProcess;

    /**
     * Glow color (RGB hex value, e.g., 0x55FF55 for green)
     */
    public final ForgeConfigSpec.IntValue glowColor;

    private TeamIndicatorConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Team Indicator (Team Glow) Configuration",
                        "Controls the green glow outline effect for teammates")
                .push("team_indicator");

        enabled = builder
                .comment("Master switch for team glow effect",
                        "When disabled, teammates will not have the green glow outline",
                        "This can improve performance if you don't need team indicators",
                        "Default: true")
                .define("enabled", true);

        maxDistance = builder
                .comment("Maximum distance (in blocks) for team glow effect",
                        "Players beyond this distance won't glow",
                        "Lower values can improve performance",
                        "Default: 128.0")
                .defineInRange("maxDistance", 128.0, 16.0, 512.0);

        maxPlayersToProcess = builder
                .comment("Maximum number of players to process per frame for glow effect",
                        "Lower values improve performance but may cause glow to not appear on some players",
                        "Default: 256")
                .defineInRange("maxPlayersToProcess", 256, 16, 1024);

        glowColor = builder
                .comment("Glow color in RGB hex format (e.g., 0x55FF55 for green)",
                        "Only affects team glow, not vanilla glowing effect",
                        "Default: 0x55FF55 (green)")
                .defineInRange("glowColor", 0x55FF55, 0x000000, 0xFFFFFF);

        builder.pop();
    }
}

