package com.lootmatrix.customui.atmosphere;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side datapack reload listener for atmosphere presets.
 * Loads JSON files from: data/<namespace>/atmosphere_presets/<name>.json
 */
public class AtmospherePresetLoader extends SimplePreparableReloadListener<Map<ResourceLocation, AtmospherePreset>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtmospherePresetLoader.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "atmosphere_presets";

    private static final Map<ResourceLocation, AtmospherePreset> LOADED_PRESETS = new HashMap<>();

    @Override
    protected Map<ResourceLocation, AtmospherePreset> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, AtmospherePreset> result = new HashMap<>();
        var resources = resourceManager.listResources(DIRECTORY,
                loc -> loc.getPath().endsWith(".json"));

        for (var entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            String path = fileId.getPath();
            // Extract name from "atmosphere_presets/<name>.json"
            String name = path.substring(DIRECTORY.length() + 1, path.length() - 5);
            ResourceLocation presetId = new ResourceLocation(fileId.getNamespace(), name);

            try (var reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                AtmospherePreset preset = parsePreset(presetId.toString(), json);
                result.put(presetId, preset);
            } catch (Exception e) {
                LOGGER.error("[CustomUI] Failed to load atmosphere preset {}: {}", presetId, e.getMessage());
            }
        }
        return result;
    }

    @Override
    protected void apply(Map<ResourceLocation, AtmospherePreset> prepared, ResourceManager manager, ProfilerFiller profiler) {
        LOADED_PRESETS.clear();
        LOADED_PRESETS.putAll(prepared);
        LOGGER.info("[CustomUI] Loaded {} atmosphere preset(s)", LOADED_PRESETS.size());
    }

    public static Map<ResourceLocation, AtmospherePreset> getLoadedPresets() {
        return Collections.unmodifiableMap(LOADED_PRESETS);
    }

    @Nullable
    public static AtmospherePreset getPreset(String id) {
        // Try as full ResourceLocation first
        for (var entry : LOADED_PRESETS.entrySet()) {
            if (entry.getKey().toString().equals(id)) return entry.getValue();
        }
        // Try path-only match
        for (var entry : LOADED_PRESETS.entrySet()) {
            if (entry.getKey().getPath().equals(id)) return entry.getValue();
        }
        return null;
    }

    // ==================== JSON Parsing ====================

    private static AtmospherePreset parsePreset(String id, JsonObject json) {
        int fadeIn = GsonHelper.getAsInt(json, "fadeInTicks", 40);
        int fadeOut = GsonHelper.getAsInt(json, "fadeOutTicks", 20);
        AtmospherePreset.EasingType easing = AtmospherePreset.EasingType.fromString(
                GsonHelper.getAsString(json, "easing", "EASE_IN_OUT"));

        AtmospherePreset.FogConfig fog = json.has("fog") ? parseFog(json.getAsJsonObject("fog")) : null;
        AtmospherePreset.SkyConfig sky = json.has("sky") ? parseSky(json.getAsJsonObject("sky")) : null;
        AtmospherePreset.SunConfig sun = json.has("sun") ? parseSun(json.getAsJsonObject("sun")) : null;
        AtmospherePreset.MoonConfig moon = json.has("moon") ? parseMoon(json.getAsJsonObject("moon")) : null;
        AtmospherePreset.StarsConfig stars = json.has("stars") ? parseStars(json.getAsJsonObject("stars")) : null;
        AtmospherePreset.CloudConfig clouds = json.has("clouds") ? parseClouds(json.getAsJsonObject("clouds")) : null;
        AtmospherePreset.AmbientConfig ambient = json.has("ambient") ? parseAmbient(json.getAsJsonObject("ambient")) : null;

        return new AtmospherePreset(id, fadeIn, fadeOut, easing, fog, sky, sun, moon, stars, clouds, ambient);
    }

    private static AtmospherePreset.FogConfig parseFog(JsonObject json) {
        float[] color = parseColor(json, "colorR", "colorG", "colorB", 0.5f, 0.5f, 0.5f);
        if (json.has("color")) {
            JsonArray arr = json.getAsJsonArray("color");
            color[0] = arr.get(0).getAsFloat(); color[1] = arr.get(1).getAsFloat(); color[2] = arr.get(2).getAsFloat();
        }
        float near = GsonHelper.getAsFloat(json, "nearDistance", 0f);
        float far = GsonHelper.getAsFloat(json, "farDistance", 192f);
        String shapeStr = GsonHelper.getAsString(json, "shape", "SPHERE");
        AtmospherePreset.FogShapeType shape = AtmospherePreset.FogShapeType.fromString(shapeStr);
        boolean overrideDensity = json.has("density");
        float density = GsonHelper.getAsFloat(json, "density", 1.0f);
        return new AtmospherePreset.FogConfig(color[0], color[1], color[2], near, far, shape, overrideDensity, density);
    }

    private static AtmospherePreset.SkyConfig parseSky(JsonObject json) {
        String typeStr = GsonHelper.getAsString(json, "type", "COLOR");
        AtmospherePreset.SkyType type = AtmospherePreset.SkyType.valueOf(typeStr.toUpperCase());

        float[] zenith = {0.1f, 0.1f, 0.2f};
        float[] horizon = {0.4f, 0.5f, 0.6f};
        if (json.has("zenith")) {
            JsonArray arr = json.getAsJsonArray("zenith");
            zenith[0] = arr.get(0).getAsFloat(); zenith[1] = arr.get(1).getAsFloat(); zenith[2] = arr.get(2).getAsFloat();
        }
        if (json.has("horizon")) {
            JsonArray arr = json.getAsJsonArray("horizon");
            horizon[0] = arr.get(0).getAsFloat(); horizon[1] = arr.get(1).getAsFloat(); horizon[2] = arr.get(2).getAsFloat();
        }

        String up = null, dn = null, n = null, s = null, e = null, w = null;
        float rotSpeed = 0;
        if (json.has("cubemap")) {
            JsonObject cm = json.getAsJsonObject("cubemap");
            up = GsonHelper.getAsString(cm, "up", "");
            dn = GsonHelper.getAsString(cm, "down", "");
            n = GsonHelper.getAsString(cm, "north", "");
            s = GsonHelper.getAsString(cm, "south", "");
            e = GsonHelper.getAsString(cm, "east", "");
            w = GsonHelper.getAsString(cm, "west", "");
            rotSpeed = GsonHelper.getAsFloat(cm, "rotationSpeed", 0f);
        }

        return new AtmospherePreset.SkyConfig(type,
                zenith[0], zenith[1], zenith[2],
                horizon[0], horizon[1], horizon[2],
                up, dn, n, s, e, w, rotSpeed);
    }

    private static AtmospherePreset.SunConfig parseSun(JsonObject json) {
        boolean visible = GsonHelper.getAsBoolean(json, "visible", true);
        String tex = GsonHelper.getAsString(json, "texture", "");
        if (tex.isEmpty()) tex = null;
        float scale = GsonHelper.getAsFloat(json, "scale", 1.0f);
        float[] color = parseColor(json, "colorR", "colorG", "colorB", 1f, 1f, 1f);
        if (json.has("color")) {
            JsonArray arr = json.getAsJsonArray("color");
            color[0] = arr.get(0).getAsFloat(); color[1] = arr.get(1).getAsFloat(); color[2] = arr.get(2).getAsFloat();
        }
        return new AtmospherePreset.SunConfig(visible, tex, scale, color[0], color[1], color[2]);
    }

    private static AtmospherePreset.MoonConfig parseMoon(JsonObject json) {
        boolean visible = GsonHelper.getAsBoolean(json, "visible", true);
        String tex = GsonHelper.getAsString(json, "texture", "");
        if (tex.isEmpty()) tex = null;
        float scale = GsonHelper.getAsFloat(json, "scale", 1.0f);
        float[] color = parseColor(json, "colorR", "colorG", "colorB", 1f, 1f, 1f);
        if (json.has("color")) {
            JsonArray arr = json.getAsJsonArray("color");
            color[0] = arr.get(0).getAsFloat(); color[1] = arr.get(1).getAsFloat(); color[2] = arr.get(2).getAsFloat();
        }
        return new AtmospherePreset.MoonConfig(visible, tex, scale, color[0], color[1], color[2]);
    }

    private static AtmospherePreset.StarsConfig parseStars(JsonObject json) {
        boolean visible = GsonHelper.getAsBoolean(json, "visible", true);
        float density = GsonHelper.getAsFloat(json, "density", 1.0f);
        float[] color = parseColor(json, "colorR", "colorG", "colorB", 1f, 1f, 1f);
        if (json.has("color")) {
            JsonArray arr = json.getAsJsonArray("color");
            color[0] = arr.get(0).getAsFloat(); color[1] = arr.get(1).getAsFloat(); color[2] = arr.get(2).getAsFloat();
        }
        float brightness = GsonHelper.getAsFloat(json, "brightness", 1.0f);
        return new AtmospherePreset.StarsConfig(visible, density, color[0], color[1], color[2], brightness);
    }

    private static AtmospherePreset.CloudConfig parseClouds(JsonObject json) {
        boolean visible = GsonHelper.getAsBoolean(json, "visible", true);
        float height = GsonHelper.getAsFloat(json, "height", 192f);
        float[] color = parseColor(json, "colorR", "colorG", "colorB", 1f, 1f, 1f);
        if (json.has("color")) {
            JsonArray arr = json.getAsJsonArray("color");
            color[0] = arr.get(0).getAsFloat(); color[1] = arr.get(1).getAsFloat(); color[2] = arr.get(2).getAsFloat();
        }
        return new AtmospherePreset.CloudConfig(visible, height, color[0], color[1], color[2]);
    }

    private static AtmospherePreset.AmbientConfig parseAmbient(JsonObject json) {
        float brightness = GsonHelper.getAsFloat(json, "brightnessMultiplier", 1.0f);
        boolean nightVision = GsonHelper.getAsBoolean(json, "nightVision", false);
        return new AtmospherePreset.AmbientConfig(brightness, nightVision);
    }

    private static float[] parseColor(JsonObject json, String rKey, String gKey, String bKey,
                                       float defR, float defG, float defB) {
        return new float[]{
                GsonHelper.getAsFloat(json, rKey, defR),
                GsonHelper.getAsFloat(json, gKey, defG),
                GsonHelper.getAsFloat(json, bKey, defB)
        };
    }
}
