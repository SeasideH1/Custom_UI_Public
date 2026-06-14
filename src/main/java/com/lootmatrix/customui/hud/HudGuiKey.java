package com.lootmatrix.customui.hud;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;

/**
 * GUI open/close key binding declared by a template.
 *
 * The player picks one of three generic, rebindable interact keys
 * (key.customui.interact1/2/3 in vanilla Controls). {@code interactKey}
 * selects which one (1-3); 0 means "none" and falls back to the raw
 * {@code key} (GLFW key name like "key.keyboard.b") declared by the template.
 */
public final class HudGuiKey {

    /** 0 = none (use the default {@code key}), 1-3 = generic interact key slot. */
    public int interactKey = 0;
    /** Fallback raw key name, e.g. "key.keyboard.b". */
    public String key = "";
    /** Translation key describing the action (for hint texts / docs). */
    public String translation = "";
    /** Stable action name declared by the template (for datapack reference). */
    public String name = "";
    /** Datapack function fired when the action happens (open/close). */
    @Nullable public String function;

    public boolean isUsable() {
        return interactKey >= 1 && interactKey <= 3 || !key.isEmpty();
    }

    @Nullable
    public static HudGuiKey fromJson(@Nullable JsonObject json) {
        if (json == null) return null;
        HudGuiKey k = new HudGuiKey();
        k.interactKey = GsonHelper.getAsInt(json, "interactKey", 0);
        k.key = GsonHelper.getAsString(json, "key", "");
        k.translation = GsonHelper.getAsString(json, "translation", "");
        k.name = GsonHelper.getAsString(json, "name", "");
        k.function = json.has("function") ? GsonHelper.getAsString(json, "function") : null;
        return k;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (interactKey != 0) json.addProperty("interactKey", interactKey);
        if (!key.isEmpty()) json.addProperty("key", key);
        if (!translation.isEmpty()) json.addProperty("translation", translation);
        if (!name.isEmpty()) json.addProperty("name", name);
        if (function != null) json.addProperty("function", function);
        return json;
    }
}
