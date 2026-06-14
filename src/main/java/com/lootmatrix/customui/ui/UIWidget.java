package com.lootmatrix.customui.ui;

import com.google.gson.*;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A single UI widget with absolute positioning on a reference canvas.
 */
public class UIWidget {

    public enum Type {
        LABEL, IMAGE, BUTTON, PROGRESS, TOGGLE, INPUT, ITEM_ICON, PANEL, DIVIDER;

        public static Type fromString(String s) {
            try { return valueOf(s.toUpperCase()); } catch (Exception e) { return LABEL; }
        }
    }

    // Core
    public Type type = Type.LABEL;
    public String id = "";
    public float x, y, w, h;
    public float originX = 0, originY = 0;
    public boolean visible = true;
    @Nullable public String tooltip;

    // Label
    @Nullable public String text;
    public int fontSize = 9;
    public int textColor = 0xFFFFFFFF;
    public boolean textShadow = true;
    public String textAlign = "left";

    // Image / texture
    @Nullable public String texture;
    public int texW = 256, texH = 256;
    public float u0 = 0, v0 = 0, u1 = 256, v1 = 256;
    public String scaleMode = "stretch";

    // Button style
    public int normalColor = 0x80333333;
    public int hoverColor = 0xA0555555;
    public int pressColor = 0xC0222222;
    @Nullable public String label;

    // Progress
    public int barColor = 0xFF44FF44;
    public int bgColor = 0x80333333;
    public float value = 0, max = 100;
    @Nullable public String variable;

    // Toggle
    public boolean toggleState = false;

    // Input
    @Nullable public String placeholder;
    public int maxLength = 256;

    // Item
    @Nullable public String itemId;
    public int itemCount = 1;

    // Panel
    @Nullable public List<UIWidget> children;
    public boolean scrollable = false;

    // Divider
    public int dividerColor = 0x80FFFFFF;
    public int dividerThickness = 1;

    // Actions
    @Nullable public UITemplate.UIAction onClick;
    @Nullable public UITemplate.UIAction onToggle;
    @Nullable public UITemplate.UIAction onSubmit;

    // ==================== JSON Parsing ====================

    public static UIWidget fromJson(JsonObject json) {
        UIWidget w = new UIWidget();
        w.type = Type.fromString(GsonHelper.getAsString(json, "type", "label"));
        w.id = GsonHelper.getAsString(json, "id", "");
        w.x = GsonHelper.getAsFloat(json, "x", 0);
        w.y = GsonHelper.getAsFloat(json, "y", 0);
        w.w = GsonHelper.getAsFloat(json, "w", 50);
        w.h = GsonHelper.getAsFloat(json, "h", 20);
        w.originX = GsonHelper.getAsFloat(json, "originX", 0);
        w.originY = GsonHelper.getAsFloat(json, "originY", 0);
        w.visible = GsonHelper.getAsBoolean(json, "visible", true);
        w.tooltip = json.has("tooltip") ? GsonHelper.getAsString(json, "tooltip") : null;

        // Label
        w.text = json.has("text") ? GsonHelper.getAsString(json, "text") : null;
        w.fontSize = GsonHelper.getAsInt(json, "fontSize", 9);
        w.textColor = parseColorInt(json, "textColor", 0xFFFFFFFF);
        w.textShadow = GsonHelper.getAsBoolean(json, "shadow", true);
        w.textAlign = GsonHelper.getAsString(json, "align", "left");

        // Image
        w.texture = json.has("texture") ? GsonHelper.getAsString(json, "texture") : null;
        if (json.has("textureSize")) {
            JsonArray ts = json.getAsJsonArray("textureSize");
            w.texW = ts.get(0).getAsInt();
            w.texH = ts.get(1).getAsInt();
        }
        if (json.has("uv")) {
            JsonArray uv = json.getAsJsonArray("uv");
            w.u0 = uv.get(0).getAsFloat();
            w.v0 = uv.get(1).getAsFloat();
            w.u1 = uv.get(2).getAsFloat();
            w.v1 = uv.get(3).getAsFloat();
        } else {
            w.u0 = 0; w.v0 = 0; w.u1 = w.texW; w.v1 = w.texH;
        }
        w.scaleMode = GsonHelper.getAsString(json, "scaleMode", "stretch");

        // Button style
        if (json.has("style")) {
            JsonObject style = json.getAsJsonObject("style");
            w.normalColor = parseColorInt(style, "normalColor", 0x80333333);
            w.hoverColor = parseColorInt(style, "hoverColor", 0xA0555555);
            w.pressColor = parseColorInt(style, "pressColor", 0xC0222222);
        }
        w.label = json.has("label") ? GsonHelper.getAsString(json, "label") : null;

        // Progress
        w.barColor = parseColorInt(json, "barColor", 0xFF44FF44);
        w.bgColor = parseColorInt(json, "bgColor", 0x80333333);
        w.value = GsonHelper.getAsFloat(json, "value", 0);
        w.max = GsonHelper.getAsFloat(json, "max", 100);
        w.variable = json.has("variable") ? GsonHelper.getAsString(json, "variable") : null;

        // Toggle
        w.toggleState = GsonHelper.getAsBoolean(json, "state", false);

        // Input
        w.placeholder = json.has("placeholder") ? GsonHelper.getAsString(json, "placeholder") : null;
        w.maxLength = GsonHelper.getAsInt(json, "maxLength", 256);

        // Item
        w.itemId = json.has("itemId") ? GsonHelper.getAsString(json, "itemId") : null;
        w.itemCount = GsonHelper.getAsInt(json, "count", 1);

        // Divider
        w.dividerColor = parseColorInt(json, "dividerColor", 0x80FFFFFF);
        w.dividerThickness = GsonHelper.getAsInt(json, "thickness", 1);

        // Actions
        w.onClick = json.has("onClick") ? UITemplate.UIAction.fromJson(json.getAsJsonObject("onClick")) : null;
        w.onToggle = json.has("onToggle") ? UITemplate.UIAction.fromJson(json.getAsJsonObject("onToggle")) : null;
        w.onSubmit = json.has("onSubmit") ? UITemplate.UIAction.fromJson(json.getAsJsonObject("onSubmit")) : null;

        // Panel children
        if (json.has("children")) {
            w.children = new ArrayList<>();
            for (JsonElement child : json.getAsJsonArray("children")) {
                w.children.add(fromJson(child.getAsJsonObject()));
            }
        }
        w.scrollable = GsonHelper.getAsBoolean(json, "scrollable", false);

        return w;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", type.name().toLowerCase());
        json.addProperty("id", id);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("w", w);
        json.addProperty("h", h);
        if (originX != 0) json.addProperty("originX", originX);
        if (originY != 0) json.addProperty("originY", originY);
        if (!visible) json.addProperty("visible", false);
        if (tooltip != null) json.addProperty("tooltip", tooltip);

        switch (type) {
            case LABEL:
                if (text != null) json.addProperty("text", text);
                json.addProperty("fontSize", fontSize);
                addColorArray(json, "textColor", textColor);
                json.addProperty("shadow", textShadow);
                json.addProperty("align", textAlign);
                break;
            case IMAGE:
                if (texture != null) json.addProperty("texture", texture);
                addIntArray(json, "textureSize", texW, texH);
                addFloatArray(json, "uv", u0, v0, u1, v1);
                break;
            case BUTTON:
                if (label != null) json.addProperty("label", label);
                if (texture != null) json.addProperty("texture", texture);
                JsonObject style = new JsonObject();
                addColorArray(style, "normalColor", normalColor);
                addColorArray(style, "hoverColor", hoverColor);
                addColorArray(style, "pressColor", pressColor);
                json.add("style", style);
                if (onClick != null) json.add("onClick", onClick.toJson());
                break;
            case PROGRESS:
                addColorArray(json, "barColor", barColor);
                addColorArray(json, "bgColor", bgColor);
                json.addProperty("value", value);
                json.addProperty("max", max);
                if (variable != null) json.addProperty("variable", variable);
                break;
            case TOGGLE:
                if (label != null) json.addProperty("label", label);
                json.addProperty("state", toggleState);
                if (onToggle != null) json.add("onToggle", onToggle.toJson());
                break;
            case INPUT:
                if (placeholder != null) json.addProperty("placeholder", placeholder);
                json.addProperty("maxLength", maxLength);
                if (onSubmit != null) json.add("onSubmit", onSubmit.toJson());
                break;
            case ITEM_ICON:
                if (itemId != null) json.addProperty("itemId", itemId);
                json.addProperty("count", itemCount);
                break;
            case PANEL:
                if (children != null) {
                    JsonArray arr = new JsonArray();
                    for (UIWidget child : children) arr.add(child.toJson());
                    json.add("children", arr);
                }
                json.addProperty("scrollable", scrollable);
                break;
            case DIVIDER:
                addColorArray(json, "dividerColor", dividerColor);
                json.addProperty("thickness", dividerThickness);
                break;
        }
        return json;
    }

    // ==================== Color Helpers ====================

    public static int parseColorInt(JsonObject json, String key, int defaultVal) {
        if (!json.has(key)) return defaultVal;
        JsonElement el = json.get(key);
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            float r = arr.get(0).getAsFloat();
            float g = arr.get(1).getAsFloat();
            float b = arr.get(2).getAsFloat();
            float a = arr.size() > 3 ? arr.get(3).getAsFloat() : 1.0f;
            return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString().replace("#", "").replace("0x", "");
            return (int) Long.parseLong(s, 16);
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        return defaultVal;
    }

    public static void addColorArray(JsonObject json, String key, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        JsonArray arr = new JsonArray();
        arr.add(round2(r)); arr.add(round2(g)); arr.add(round2(b)); arr.add(round2(a));
        json.add(key, arr);
    }

    private static float round2(float v) { return Math.round(v * 100) / 100f; }

    private static void addIntArray(JsonObject json, String key, int... values) {
        JsonArray arr = new JsonArray();
        for (int v : values) arr.add(v);
        json.add(key, arr);
    }

    private static void addFloatArray(JsonObject json, String key, float... values) {
        JsonArray arr = new JsonArray();
        for (float v : values) arr.add(v);
        json.add(key, arr);
    }
}
