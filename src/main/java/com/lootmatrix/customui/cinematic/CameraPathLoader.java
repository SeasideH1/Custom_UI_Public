package com.lootmatrix.customui.cinematic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads cinematic camera path presets from datapack JSON files.
 * Path: data/<namespace>/cinematic_paths/<name>.json
 * <p>
 * Supports 4-channel per-keyframe interpolation. Legacy JSON files that use
 * the old single {@code interpolation}, {@code mergeWithNext}, and {@code easing}
 * fields are automatically upgraded to the new format.
 */
public class CameraPathLoader extends SimplePreparableReloadListener<Map<ResourceLocation, CameraPath>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CameraPathLoader.class);
    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final String DIRECTORY = "cinematic_paths";
    private static final String DEFAULT_NAMESPACE = "customui";

    private static final Map<ResourceLocation, CameraPath> LOADED_PATHS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, CameraPath> RUNTIME_PATHS = new LinkedHashMap<>();

    public static Map<ResourceLocation, CameraPath> getLoadedPaths() {
        Map<ResourceLocation, CameraPath> combined = new LinkedHashMap<>(LOADED_PATHS);
        combined.putAll(RUNTIME_PATHS);
        return Collections.unmodifiableMap(combined);
    }

    public static CameraPath getPath(ResourceLocation id) {
        CameraPath runtime = RUNTIME_PATHS.get(id);
        return runtime != null ? runtime : LOADED_PATHS.get(id);
    }

    public static CameraPath getPath(String idString) {
        ResourceLocation id = resolveLoadedPathId(idString);
        return id != null ? getPath(id) : null;
    }

    public static ResourceLocation canonicalizePathId(String idString) {
        if (idString == null) {
            return null;
        }

        String trimmed = idString.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.indexOf(':') >= 0) {
            return ResourceLocation.tryParse(trimmed);
        }
        return ResourceLocation.tryParse(DEFAULT_NAMESPACE + ":" + trimmed);
    }

    public static ResourceLocation resolveLoadedPathId(String idString) {
        if (idString == null) {
            return null;
        }

        String trimmed = idString.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Map<ResourceLocation, CameraPath> paths = getLoadedPaths();
        ResourceLocation canonical = canonicalizePathId(trimmed);
        if (canonical != null && paths.containsKey(canonical)) {
            return canonical;
        }

        if (trimmed.indexOf(':') >= 0) {
            return null;
        }

        ResourceLocation uniqueMatch = null;
        for (ResourceLocation id : paths.keySet()) {
            if (!id.getPath().equals(trimmed)) {
                continue;
            }
            if (uniqueMatch != null) {
                return null;
            }
            uniqueMatch = id;
        }
        return uniqueMatch;
    }

    public static void registerRuntimePath(ResourceLocation id, CameraPath path) {
        RUNTIME_PATHS.put(id, path);
    }

    public static void unregisterRuntimePath(ResourceLocation id) {
        RUNTIME_PATHS.remove(id);
    }

    @Override
    protected Map<ResourceLocation, CameraPath> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, CameraPath> result = new LinkedHashMap<>();
        Map<ResourceLocation, List<net.minecraft.server.packs.resources.Resource>> resources =
                resourceManager.listResourceStacks(DIRECTORY, loc -> loc.getPath().endsWith(".json"));

        for (Map.Entry<ResourceLocation, List<net.minecraft.server.packs.resources.Resource>> entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            String path = fileId.getPath();
            String name = path.substring(DIRECTORY.length() + 1, path.length() - 5);
            ResourceLocation pathId = new ResourceLocation(fileId.getNamespace(), name);

            for (net.minecraft.server.packs.resources.Resource resource : entry.getValue()) {
                try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    CameraPath cameraPath = parseFromJson(pathId.toString(), json);
                    if (cameraPath != null) {
                        result.put(pathId, cameraPath);
                    }
                } catch (Exception exception) {
                    LOGGER.error("[CustomUI] Failed to load cinematic path {}: {}", pathId, exception.getMessage());
                }
            }
        }
        return result;
    }

    @Override
    protected void apply(Map<ResourceLocation, CameraPath> prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        LOADED_PATHS.clear();
        LOADED_PATHS.putAll(prepared);
        LOGGER.info("[CustomUI] Loaded {} cinematic camera path(s) and kept {} runtime path(s)",
                LOADED_PATHS.size(), RUNTIME_PATHS.size());
    }

    public static CameraPath parseFromJson(String id, JsonObject json) {
        try {
            boolean loop = getBool(json, "loop", false);

            JsonArray keyframesArray = json.getAsJsonArray("keyframes");
            if (keyframesArray == null || keyframesArray.isEmpty()) {
                LOGGER.warn("[CustomUI] Cinematic path '{}' has no keyframes", id);
                return null;
            }

            List<CameraKeyframe> keyframes = new ArrayList<>();
            for (JsonElement element : keyframesArray) {
                keyframes.add(parseKeyframe(element.getAsJsonObject()));
            }
            return new CameraPath(id, keyframes, loop);
        } catch (Exception exception) {
            LOGGER.error("[CustomUI] Error parsing cinematic path '{}': {}", id, exception.getMessage());
            return null;
        }
    }

    private static CameraKeyframe parseKeyframe(JsonObject obj) {
        Vec3 position = parseVec3(obj, "position", Vec3.ZERO);
        Vec3 lookAt = obj.has("lookAt") ? parseVec3(obj, "lookAt", null) : null;

        float yaw = getFloat(obj, "yaw", 0f);
        float pitch = getFloat(obj, "pitch", 0f);
        float roll = getFloat(obj, "roll", 0f);

        float fov = obj.has("fov") ? getFloat(obj, "fov", 70f) : getFloat(obj, "fovStart", 70f);
        int durationTicks = Math.max(0, getInt(obj, "duration", 60));

        boolean absolutePosition = getBool(obj, "absolutePosition", true);
        boolean nightVision = getBool(obj, "nightVision", false);
        int sendChunksRadius = Math.max(0, getInt(obj, "sendChunksRadius", 0));
        boolean sendChunks = getBool(obj, "sendChunks", sendChunksRadius > 0);
        boolean hideHud = getBool(obj, "hideHud", true);
        boolean showSelf = getBool(obj, "showSelf", false);

        // ---- 4-channel interpolation parsing with backward compatibility ----

        // Legacy fallback: if old-style "interpolation" or "easing" or "mergeWithNext" present,
        // use them as defaults for all 4 channels.
        CameraKeyframe.InterpolationMode legacyInterp = parseInterpolationMode(
                obj, "interpolation", CameraKeyframe.InterpolationMode.CENTRIPETAL_CATMULL_ROM);
        boolean legacyMerge = getBool(obj, "mergeWithNext", true);
        CameraKeyframe.EasingType legacyEasing = parseEasingType(
                obj, "easing", CameraKeyframe.EasingType.EASE_IN_OUT);
        // Old per-channel easing fallbacks
        CameraKeyframe.EasingType legacyCameraLookEasing = parseEasingType(
                obj, "cameraLookEasing", legacyEasing);
        CameraKeyframe.EasingType legacyCameraLocationEasing = parseEasingType(
                obj, "cameraLocationEasing", legacyEasing);

        // New 4-channel fields (fall back to legacy values)
        CameraKeyframe.InterpolationMode positionPathInterp = parseInterpolationMode(
                obj, "positionPathInterp", legacyInterp);
        boolean positionPathMerge = getBool(obj, "positionPathMerge", legacyMerge);

        CameraKeyframe.EasingType positionMoveEasing = parseEasingType(
                obj, "positionMoveEasing", legacyCameraLocationEasing);
        boolean positionMoveMerge = getBool(obj, "positionMoveMerge", legacyMerge);

        CameraKeyframe.InterpolationMode orientationPathInterp = parseInterpolationMode(
                obj, "orientationPathInterp", legacyInterp);
        boolean orientationPathMerge = getBool(obj, "orientationPathMerge", legacyMerge);

        CameraKeyframe.EasingType orientationMoveEasing = parseEasingType(
                obj, "orientationMoveEasing", legacyCameraLookEasing);
        boolean orientationMoveMerge = getBool(obj, "orientationMoveMerge", legacyMerge);

        return new CameraKeyframe(position, lookAt, yaw, pitch, roll,
                positionPathInterp, positionPathMerge,
                positionMoveEasing, positionMoveMerge,
                orientationPathInterp, orientationPathMerge,
                orientationMoveEasing, orientationMoveMerge,
                fov, durationTicks, absolutePosition, nightVision, sendChunks, hideHud,
                showSelf, sendChunksRadius);
    }

    private static Vec3 parseVec3(JsonObject obj, String key, Vec3 fallback) {
        if (!obj.has(key)) return fallback;
        JsonElement element = obj.get(key);
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() >= 3) {
                return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
            }
        } else if (element.isJsonObject()) {
            JsonObject vector = element.getAsJsonObject();
            return new Vec3(getDouble(vector, "x", 0.0), getDouble(vector, "y", 0.0), getDouble(vector, "z", 0.0));
        }
        return fallback;
    }

    private static float getFloat(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        return obj.has(key) ? obj.get(key).getAsDouble() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static <E extends Enum<E>> E parseEnum(JsonObject obj, String key, Class<E> enumClass, E def) {
        if (!obj.has(key)) return def;
        try {
            return Enum.valueOf(enumClass, obj.get(key).getAsString().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static CameraKeyframe.InterpolationMode parseInterpolationMode(
            JsonObject obj, String key, CameraKeyframe.InterpolationMode def) {
        return parseEnum(obj, key, CameraKeyframe.InterpolationMode.class, def);
    }

    private static CameraKeyframe.EasingType parseEasingType(
            JsonObject obj, String key, CameraKeyframe.EasingType def) {
        return parseEnum(obj, key, CameraKeyframe.EasingType.class, def);
    }
}
