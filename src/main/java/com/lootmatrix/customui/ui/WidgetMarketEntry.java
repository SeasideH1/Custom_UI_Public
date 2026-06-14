package com.lootmatrix.customui.ui;

import com.google.gson.*;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * A single widget market entry containing one or more pre-built UIWidget definitions.
 */
public class WidgetMarketEntry {

    public String name;
    public String category;
    public String description;
    public List<UIWidget> widgets;

    public static WidgetMarketEntry fromJson(JsonObject json) {
        WidgetMarketEntry entry = new WidgetMarketEntry();
        entry.name = GsonHelper.getAsString(json, "name", "Unnamed");
        entry.category = GsonHelper.getAsString(json, "category", "General");
        entry.description = GsonHelper.getAsString(json, "description", "");
        entry.widgets = new ArrayList<>();

        if (json.has("widget")) {
            entry.widgets.add(UIWidget.fromJson(json.getAsJsonObject("widget")));
        }
        if (json.has("widgets")) {
            for (JsonElement el : json.getAsJsonArray("widgets")) {
                entry.widgets.add(UIWidget.fromJson(el.getAsJsonObject()));
            }
        }
        return entry;
    }
}
