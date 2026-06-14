package com.lootmatrix.customui.client.title;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.cinematic.CameraKeyframe;
import com.lootmatrix.customui.cinematic.CameraPath;
import com.lootmatrix.customui.cinematic.CameraPathLoader;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the LabyMod-style title scene definition from the mod's own resource
 * pack:
 * <ul>
 *   <li>{@code assets/customui/title_scene/scene.json} — sky/fog/light theme,
 *       entrance + idle camera paths and per-menu camera anchors (camera
 *       keyframes reuse the cinematic_paths JSON format)</li>
 *   <li>{@code assets/customui/title_scene/scene.nbt}  — a vanilla structure
 *       block .nbt with the 3D scenery; optional, the menus fall back to the
 *       vanilla panorama / dirt background when absent</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class TitleSceneAssets {

    private static final Logger LOGGER = LoggerFactory.getLogger(TitleSceneAssets.class);
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    public static final ResourceLocation CONFIG_LOCATION =
            ResourceLocation.fromNamespaceAndPath("customui", "title_scene/scene.json");
    public static final ResourceLocation STRUCTURE_LOCATION =
            ResourceLocation.fromNamespaceAndPath("customui", "title_scene/scene.nbt");

    private TitleSceneAssets() {}

    /** Parsed scene.json. All colors are ARGB/RGB ints, all distances in blocks. */
    public static final class SceneConfig {
        public boolean sceneEnabled = true;
        public boolean glassButtons = true;

        public int skyTopColor = 0xFF0B1026;
        public int skyBottomColor = 0xFF16243C;
        public int fogColor = 0x0B0F14;
        public float fogStart = 12f;
        public float fogEnd = 44f;

        /** Uniform light levels used while baking (0-15). */
        public int skyLight = 13;
        public int blockLight = 12;
        /** Floor of the baked light ramp so nothing goes fully black. */
        public float minBrightness = 0.18f;

        @Nullable public CameraPath entrance;
        @Nullable public CameraPath idle;
        public final Map<String, CameraKeyframe> anchors = new HashMap<>();
        public int transitionTicks = 18;
    }

    public static SceneConfig loadConfig(ResourceManager resourceManager) {
        SceneConfig config = new SceneConfig();
        Optional<Resource> resource = resourceManager.getResource(CONFIG_LOCATION);
        if (resource.isEmpty()) {
            LOGGER.info("[CustomUI] No title_scene/scene.json found, title scene disabled");
            config.sceneEnabled = false;
            return config;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            config.sceneEnabled = getBool(json, "enabled", true);
            config.glassButtons = getBool(json, "glassButtons", true);
            config.skyTopColor = parseColor(json, "skyTopColor", config.skyTopColor);
            config.skyBottomColor = parseColor(json, "skyBottomColor", config.skyBottomColor);
            config.fogColor = parseColor(json, "fogColor", config.fogColor) & 0xFFFFFF;
            config.fogStart = getFloat(json, "fogStart", config.fogStart);
            config.fogEnd = getFloat(json, "fogEnd", config.fogEnd);
            config.skyLight = clampLight(getInt(json, "skyLight", config.skyLight));
            config.blockLight = clampLight(getInt(json, "blockLight", config.blockLight));
            config.minBrightness = getFloat(json, "minBrightness", config.minBrightness);
            config.transitionTicks = Math.max(1, getInt(json, "transitionTicks", config.transitionTicks));

            if (json.has("entrance")) {
                config.entrance = CameraPathLoader.parseFromJson(
                        "customui:title_entrance", json.getAsJsonObject("entrance"));
            }
            if (json.has("idle")) {
                config.idle = CameraPathLoader.parseFromJson(
                        "customui:title_idle", json.getAsJsonObject("idle"));
            }
            if (json.has("anchors")) {
                JsonObject anchors = json.getAsJsonObject("anchors");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : anchors.entrySet()) {
                    CameraKeyframe keyframe = parseSingleKeyframe(
                            entry.getKey(), entry.getValue().getAsJsonObject());
                    if (keyframe != null) {
                        config.anchors.put(entry.getKey().toLowerCase(Locale.ROOT), keyframe);
                    }
                }
            }
        } catch (Exception exception) {
            LOGGER.error("[CustomUI] Failed to parse title_scene/scene.json", exception);
            config.sceneEnabled = false;
        }
        return config;
    }

    /** Reuses the cinematic keyframe JSON format by wrapping into a 1-keyframe path. */
    @Nullable
    private static CameraKeyframe parseSingleKeyframe(String name, JsonObject keyframeJson) {
        JsonObject wrapper = new JsonObject();
        JsonArray array = new JsonArray();
        array.add(keyframeJson);
        wrapper.add("keyframes", array);
        CameraPath path = CameraPathLoader.parseFromJson("customui:title_anchor_" + name, wrapper);
        return path != null && !path.getKeyframes().isEmpty() ? path.getKeyframes().get(0) : null;
    }

    /**
     * Loads the structure .nbt into a {@link SceneBlockGetter}.
     * Returns null when the resource is missing or unreadable.
     */
    @Nullable
    public static SceneBlockGetter loadStructure(ResourceManager resourceManager, SceneConfig config) {
        Optional<Resource> resource = resourceManager.getResource(STRUCTURE_LOCATION);
        if (resource.isEmpty()) {
            LOGGER.info("[CustomUI] No title_scene/scene.nbt found, falling back to vanilla menu background");
            return null;
        }
        try (InputStream stream = resource.get().open()) {
            CompoundTag root = NbtIo.readCompressed(stream);
            ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
            HolderGetter<Block> blockLookup = BuiltInRegistries.BLOCK.asLookup();
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                palette[i] = NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i));
            }

            ListTag blocksTag = root.getList("blocks", Tag.TAG_COMPOUND);
            ListTag sizeTag = root.getList("size", Tag.TAG_INT);
            int sizeX = sizeTag.size() >= 3 ? sizeTag.getInt(0) : 1;
            int sizeY = sizeTag.size() >= 3 ? sizeTag.getInt(1) : 1;
            int sizeZ = sizeTag.size() >= 3 ? sizeTag.getInt(2) : 1;

            SceneBlockGetter scene = new SceneBlockGetter(sizeX, sizeY, sizeZ,
                    config.skyLight, config.blockLight);
            for (int i = 0; i < blocksTag.size(); i++) {
                CompoundTag blockTag = blocksTag.getCompound(i);
                int stateIndex = blockTag.getInt("state");
                if (stateIndex < 0 || stateIndex >= palette.length) continue;
                ListTag pos = blockTag.getList("pos", Tag.TAG_INT);
                if (pos.size() < 3) continue;
                scene.setBlock(pos.getInt(0), pos.getInt(1), pos.getInt(2), palette[stateIndex]);
            }
            scene.computeLighting();
            LOGGER.info("[CustomUI] Title scene structure loaded: {}x{}x{} ({} palette entries)",
                    sizeX, sizeY, sizeZ, palette.length);
            return scene;
        } catch (Exception exception) {
            LOGGER.error("[CustomUI] Failed to read title_scene/scene.nbt", exception);
            return null;
        }
    }

    // ==================== JSON helpers ====================

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static float getFloat(JsonObject obj, String key, float def) {
        return obj.has(key) ? obj.get(key).getAsFloat() : def;
    }

    private static int clampLight(int value) {
        return Math.max(0, Math.min(15, value));
    }

    /** Accepts "#RRGGBB", "#AARRGGBB" or plain ints. Missing alpha defaults to 0xFF. */
    private static int parseColor(JsonObject obj, String key, int def) {
        if (!obj.has(key)) return def;
        try {
            com.google.gson.JsonElement element = obj.get(key);
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
            String text = element.getAsString().trim();
            if (text.startsWith("#")) text = text.substring(1);
            long parsed = Long.parseLong(text, 16);
            if (text.length() <= 6) {
                parsed |= 0xFF000000L;
            }
            return (int) parsed;
        } catch (Exception ignored) {
            return def;
        }
    }

}
