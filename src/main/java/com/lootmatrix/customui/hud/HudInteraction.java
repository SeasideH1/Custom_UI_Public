package com.lootmatrix.customui.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Click behavior of a GUI template element ("onClick" / "onClickFail").
 *
 * All feedback fields are optional: a datapack function (server-validated),
 * a UI sound, replaying another element's keyframes, recoloring other
 * elements, opening another template and/or closing the current GUI.
 */
public final class HudInteraction {

    /** Datapack function id ("ns:path"), executed server-side after re-validation. */
    @Nullable public String function;
    /** Sound event id played client-side ("minecraft:ui.button.click"). */
    @Nullable public String sound;
    /** Element id whose keyframe animation is replayed from tick 0. */
    @Nullable public String anim;
    /** Template id to open: GUI templates replace the screen, HUD templates play as overlay. */
    @Nullable public String openTemplate;
    /** Close the current GUI after this interaction. */
    public boolean close = false;
    /** Recolor other elements of the same template. */
    public final List<SetColor> setColor = new ArrayList<>();

    public static final class SetColor {
        public String target = "";
        @Nullable public Integer fill;
        @Nullable public Integer textColor;

        static SetColor fromJson(JsonObject json) {
            SetColor s = new SetColor();
            s.target = GsonHelper.getAsString(json, "target", "");
            s.fill = json.has("fill") ? HudElement.parseColor(json, "fill", 0) : null;
            s.textColor = json.has("textColor") ? HudElement.parseColor(json, "textColor", 0) : null;
            return s;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("target", target);
            if (fill != null) json.addProperty("fill", HudElement.hexColor(fill));
            if (textColor != null) json.addProperty("textColor", HudElement.hexColor(textColor));
            return json;
        }
    }

    public static HudInteraction fromJson(JsonObject json) {
        HudInteraction a = new HudInteraction();
        a.function = json.has("function") ? GsonHelper.getAsString(json, "function") : null;
        a.sound = json.has("sound") ? GsonHelper.getAsString(json, "sound") : null;
        a.anim = json.has("anim") ? GsonHelper.getAsString(json, "anim") : null;
        a.openTemplate = json.has("openTemplate") ? GsonHelper.getAsString(json, "openTemplate") : null;
        a.close = GsonHelper.getAsBoolean(json, "close", false);
        if (json.has("setColor") && json.get("setColor").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("setColor")) {
                if (el.isJsonObject()) a.setColor.add(SetColor.fromJson(el.getAsJsonObject()));
            }
        }
        return a;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (function != null) json.addProperty("function", function);
        if (sound != null) json.addProperty("sound", sound);
        if (anim != null) json.addProperty("anim", anim);
        if (openTemplate != null) json.addProperty("openTemplate", openTemplate);
        if (close) json.addProperty("close", true);
        if (!setColor.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (SetColor s : setColor) arr.add(s.toJson());
            json.add("setColor", arr);
        }
        return json;
    }
}
