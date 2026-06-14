package com.lootmatrix.customui.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;

/**
 * A single keyframe. Every animatable property is optional ({@code null} means
 * "this keyframe does not touch that property"), so each property forms its own
 * independent track. Two keyframes on the same tick produce an instant change.
 *
 * Property indices (used by compiled tracks and the editor):
 * 0=x, 1=y, 2=scale, 3=rotation, 4=opacity, 5=flash, 6=flashMin,
 * 7=space (0=2D, 1=3D), 8=worldX, 9=worldY, 10=worldZ.
 */
public class HudKeyframe {

    public static final int PROP_X = 0;
    public static final int PROP_Y = 1;
    public static final int PROP_SCALE = 2;
    public static final int PROP_ROTATION = 3;
    public static final int PROP_OPACITY = 4;
    public static final int PROP_FLASH = 5;
    public static final int PROP_FLASH_MIN = 6;
    public static final int PROP_SPACE = 7;
    public static final int PROP_WORLD_X = 8;
    public static final int PROP_WORLD_Y = 9;
    public static final int PROP_WORLD_Z = 10;
    public static final int PROP_COUNT = 11;

    public static final String[] PROP_NAMES = {
            "x", "y", "scale", "rotation", "opacity", "flash", "flashMin",
            "space", "worldX", "worldY", "worldZ"
    };

    public int tick;
    /** Per-property values; null = property untouched by this keyframe. */
    public final Float[] values = new Float[PROP_COUNT];
    /** Default easing for every property on this keyframe. */
    public HudEasing easing = HudEasing.LINEAR;
    /** Per-property easing overrides; null = use {@link #easing}. */
    public final HudEasing[] easingOverrides = new HudEasing[PROP_COUNT];

    public HudEasing easingFor(int prop) {
        HudEasing o = easingOverrides[prop];
        return o != null ? o : easing;
    }

    @Nullable
    public Float get(int prop) {
        return values[prop];
    }

    public void set(int prop, @Nullable Float value) {
        values[prop] = value;
    }

    // ==================== JSON ====================

    public static HudKeyframe fromJson(JsonObject json) {
        HudKeyframe kf = new HudKeyframe();
        kf.tick = GsonHelper.getAsInt(json, "tick", 0);
        kf.easing = HudEasing.fromString(GsonHelper.getAsString(json, "easing", "linear"));

        for (int p = 0; p < PROP_COUNT; p++) {
            String name = PROP_NAMES[p];
            if (p == PROP_SPACE) {
                if (json.has("space")) {
                    String space = GsonHelper.getAsString(json, "space", "2d");
                    kf.values[p] = "3d".equalsIgnoreCase(space) ? 1f : 0f;
                }
            } else if (json.has(name)) {
                kf.values[p] = GsonHelper.getAsFloat(json, name);
            }
        }
        // "world": [x,y,z] shorthand
        if (json.has("world") && json.get("world").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("world");
            if (arr.size() >= 3) {
                kf.values[PROP_WORLD_X] = arr.get(0).getAsFloat();
                kf.values[PROP_WORLD_Y] = arr.get(1).getAsFloat();
                kf.values[PROP_WORLD_Z] = arr.get(2).getAsFloat();
            }
        }

        if (json.has("easings") && json.get("easings").isJsonObject()) {
            JsonObject easings = json.getAsJsonObject("easings");
            for (int p = 0; p < PROP_COUNT; p++) {
                if (easings.has(PROP_NAMES[p])) {
                    kf.easingOverrides[p] = HudEasing.fromString(easings.get(PROP_NAMES[p]).getAsString());
                }
            }
        }
        return kf;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("tick", tick);
        if (easing != HudEasing.LINEAR) json.addProperty("easing", easing.toJsonName());

        for (int p = 0; p < PROP_COUNT; p++) {
            Float v = values[p];
            if (v == null) continue;
            if (p == PROP_SPACE) {
                json.addProperty("space", v >= 0.5f ? "3d" : "2d");
            } else {
                json.addProperty(PROP_NAMES[p], round3(v));
            }
        }

        JsonObject easings = null;
        for (int p = 0; p < PROP_COUNT; p++) {
            if (easingOverrides[p] != null) {
                if (easings == null) easings = new JsonObject();
                easings.addProperty(PROP_NAMES[p], easingOverrides[p].toJsonName());
            }
        }
        if (easings != null) json.add("easings", easings);
        return json;
    }

    private static float round3(float v) {
        return Math.round(v * 1000f) / 1000f;
    }

    public HudKeyframe copy() {
        HudKeyframe kf = new HudKeyframe();
        kf.tick = tick;
        kf.easing = easing;
        System.arraycopy(values, 0, kf.values, 0, PROP_COUNT);
        System.arraycopy(easingOverrides, 0, kf.easingOverrides, 0, PROP_COUNT);
        return kf;
    }
}
