package com.lootmatrix.customui.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages preset icons for the scoreboard overlay.
 * Provides easy-to-use preset names that map to texture paths.
 */
@OnlyIn(Dist.CLIENT)
public class ScoreboardIconPresets {

    private static final ScoreboardIconPresets INSTANCE = new ScoreboardIconPresets();

    /** Map of preset name to ResourceLocation */
    private final Map<String, ResourceLocation> presets = new HashMap<>();

    private boolean initialized = false;

    public static ScoreboardIconPresets getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize preset icons. Called on client setup.
     */
    public void init() {
        if (initialized) return;
        initialized = true;

        // Register preset icons - these should be in assets/customui/textures/icons/
        registerPreset("sword", "customui:textures/icons/sword.png");
        registerPreset("shield", "customui:textures/icons/shield.png");
        registerPreset("crown", "customui:textures/icons/crown.png");
        registerPreset("star", "customui:textures/icons/star.png");
        registerPreset("heart", "customui:textures/icons/heart.png");
        registerPreset("skull", "customui:textures/icons/skull.png");
        registerPreset("flag", "customui:textures/icons/flag.png");
        registerPreset("diamond", "customui:textures/icons/diamond.png");
        registerPreset("fire", "customui:textures/icons/fire.png");
        registerPreset("lightning", "customui:textures/icons/lightning.png");
        registerPreset("red", "customui:textures/icons/team_red.png");
        registerPreset("blue", "customui:textures/icons/team_blue.png");
        registerPreset("green", "customui:textures/icons/team_green.png");
        registerPreset("yellow", "customui:textures/icons/team_yellow.png");
    }

    /**
     * Register a preset icon.
     */
    public void registerPreset(String name, String path) {
        ResourceLocation loc = ResourceLocation.tryParse(path);
        if (loc != null) {
            presets.put(name.toLowerCase(), loc);
        }
    }

    /**
     * Get a preset icon by name.
     * @return ResourceLocation or null if not found
     */
    @Nullable
    public ResourceLocation getPreset(String name) {
        if (name == null || name.isEmpty()) return null;
        return presets.get(name.toLowerCase());
    }

    /**
     * Check if a preset exists.
     */
    public boolean hasPreset(String name) {
        return name != null && presets.containsKey(name.toLowerCase());
    }

    /**
     * Parse an icon path - can be a preset name or a full resource location.
     * @param pathOrPreset Preset name (e.g., "sword") or full path (e.g., "minecraft:textures/item/diamond_sword.png")
     * @return ResourceLocation or null
     */
    @Nullable
    public ResourceLocation resolveIcon(String pathOrPreset) {
        if (pathOrPreset == null || pathOrPreset.isEmpty()) {
            return null;
        }

        // First check if it's a preset name
        if (hasPreset(pathOrPreset)) {
            return getPreset(pathOrPreset);
        }

        // Otherwise try to parse as ResourceLocation
        return ResourceLocation.tryParse(pathOrPreset);
    }
}


