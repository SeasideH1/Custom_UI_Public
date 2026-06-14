package com.lootmatrix.customui.ui;

import com.google.gson.*;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A complete UI template definition with canvas, widgets, variables and animations.
 */
public class UITemplate {

    public String id;
    public String title = "";
    public int canvasWidth = 480;
    public int canvasHeight = 270;
    @Nullable public String background;
    public int backgroundColor = 0xB0000000;
    public boolean pauseGame = false;
    public List<UIWidget> widgets = new ArrayList<>();
    public Map<String, VariableDef> variables = new HashMap<>();
    public int openAnimTicks = 6;
    public int closeAnimTicks = 4;

    /** Resolved variable values (populated at runtime by server). */
    public Map<String, String> resolvedVars = new HashMap<>();

    // ==================== JSON Parsing ====================

    public static UITemplate fromJson(String idFallback, JsonObject json) {
        UITemplate t = new UITemplate();
        t.id = json.has("id") ? GsonHelper.getAsString(json, "id") : idFallback;
        t.title = GsonHelper.getAsString(json, "title", "");

        if (json.has("canvas")) {
            JsonObject canvas = json.getAsJsonObject("canvas");
            t.canvasWidth = GsonHelper.getAsInt(canvas, "width", 480);
            t.canvasHeight = GsonHelper.getAsInt(canvas, "height", 270);
        }

        t.background = json.has("background") ? GsonHelper.getAsString(json, "background") : null;
        t.backgroundColor = UIWidget.parseColorInt(json, "backgroundColor", 0xB0000000);
        t.pauseGame = GsonHelper.getAsBoolean(json, "pauseGame", false);
        t.openAnimTicks = GsonHelper.getAsInt(json, "openAnimTicks", 6);
        t.closeAnimTicks = GsonHelper.getAsInt(json, "closeAnimTicks", 4);

        if (json.has("widgets")) {
            for (JsonElement el : json.getAsJsonArray("widgets")) {
                t.widgets.add(UIWidget.fromJson(el.getAsJsonObject()));
            }
        }

        if (json.has("variables")) {
            JsonObject vars = json.getAsJsonObject("variables");
            for (var entry : vars.entrySet()) {
                t.variables.put(entry.getKey(), VariableDef.fromJson(entry.getValue().getAsJsonObject()));
            }
        }
        return t;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("title", title);

        JsonObject canvas = new JsonObject();
        canvas.addProperty("width", canvasWidth);
        canvas.addProperty("height", canvasHeight);
        json.add("canvas", canvas);

        if (background != null) json.addProperty("background", background);
        UIWidget.addColorArray(json, "backgroundColor", backgroundColor);
        json.addProperty("pauseGame", pauseGame);
        json.addProperty("openAnimTicks", openAnimTicks);
        json.addProperty("closeAnimTicks", closeAnimTicks);

        JsonArray widgetArr = new JsonArray();
        for (UIWidget w : widgets) widgetArr.add(w.toJson());
        json.add("widgets", widgetArr);

        if (!variables.isEmpty()) {
            JsonObject vars = new JsonObject();
            for (var entry : variables.entrySet()) {
                vars.add(entry.getKey(), entry.getValue().toJson());
            }
            json.add("variables", vars);
        }
        return json;
    }

    // ==================== Widget Lookup ====================

    @Nullable
    public UIWidget findWidget(String widgetId) {
        return findInList(widgets, widgetId);
    }

    @Nullable
    private static UIWidget findInList(List<UIWidget> list, String widgetId) {
        for (UIWidget w : list) {
            if (widgetId.equals(w.id)) return w;
            if (w.children != null) {
                UIWidget found = findInList(w.children, widgetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Resolve ${variable} placeholders in text. */
    public String resolveText(@Nullable String text) {
        if (text == null || !text.contains("${")) return text;
        for (var entry : resolvedVars.entrySet()) {
            text = text.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    // ==================== Inner Classes ====================

    public static class UIAction {
        public String command = "";
        public String executor = "@s";
        public boolean closeOnExecute = false;
        public int cooldownTicks = 0;

        public static UIAction fromJson(JsonObject json) {
            UIAction a = new UIAction();
            a.command = GsonHelper.getAsString(json, "command", "");
            a.executor = GsonHelper.getAsString(json, "executor", "@s");
            a.closeOnExecute = GsonHelper.getAsBoolean(json, "closeOnExecute", false);
            a.cooldownTicks = GsonHelper.getAsInt(json, "cooldownTicks", 0);
            return a;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("command", command);
            json.addProperty("executor", executor);
            if (closeOnExecute) json.addProperty("closeOnExecute", true);
            if (cooldownTicks > 0) json.addProperty("cooldownTicks", cooldownTicks);
            return json;
        }
    }

    public static class VariableDef {
        public String source = "scoreboard";
        public String objective = "";
        public String defaultValue = "0";

        public static VariableDef fromJson(JsonObject json) {
            VariableDef v = new VariableDef();
            v.source = GsonHelper.getAsString(json, "source", "scoreboard");
            v.objective = GsonHelper.getAsString(json, "objective", "");
            v.defaultValue = GsonHelper.getAsString(json, "default", "0");
            return v;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("source", source);
            json.addProperty("objective", objective);
            json.addProperty("default", defaultValue);
            return json;
        }
    }
}
