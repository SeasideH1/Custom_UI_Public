package com.lootmatrix.customui.ui;

import com.google.gson.*;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Client-side registry that manages widget market entries.
 * Loads built-in presets and external JSON files from config/customui/widget_market/.
 */
public class WidgetMarketRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(WidgetMarketRegistry.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final WidgetMarketRegistry INSTANCE = new WidgetMarketRegistry();

    private final List<WidgetMarketEntry> entries = new ArrayList<>();
    private boolean loaded = false;

    public static WidgetMarketRegistry getInstance() { return INSTANCE; }

    public void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        entries.clear();
        addBuiltInPresets();
        loadFromDirectory();
    }

    public void reload() {
        loaded = false;
        loadIfNeeded();
    }

    public List<WidgetMarketEntry> getEntries() { return entries; }

    public List<String> getCategories() {
        Set<String> cats = new LinkedHashSet<>();
        for (WidgetMarketEntry e : entries) cats.add(e.category);
        return new ArrayList<>(cats);
    }

    public List<WidgetMarketEntry> getByCategory(String category) {
        List<WidgetMarketEntry> result = new ArrayList<>();
        for (WidgetMarketEntry e : entries) {
            if (category.equals(e.category)) result.add(e);
        }
        return result;
    }

    // ==================== Built-in Presets ====================

    private void addBuiltInPresets() {
        addPreset("Title Label", "Basic", "Large title text label", UIWidget.Type.LABEL, w -> {
            w.text = "Title"; w.fontSize = 14; w.textColor = 0xFFFFFF00; w.w = 120; w.h = 16;
        });
        addPreset("Body Text", "Basic", "Regular body text", UIWidget.Type.LABEL, w -> {
            w.text = "Body text here"; w.fontSize = 9; w.textColor = 0xFFCCCCCC; w.w = 200; w.h = 12;
        });
        addPreset("Icon Button", "Basic", "Standard button with label", UIWidget.Type.BUTTON, w -> {
            w.label = "Click Me"; w.w = 80; w.h = 24;
        });
        addPreset("Close Button", "Basic", "Red close button", UIWidget.Type.BUTTON, w -> {
            w.label = "\u2715"; w.w = 20; w.h = 20;
            w.normalColor = 0x80CC3333; w.hoverColor = 0xC0FF4444; w.pressColor = 0xC0AA2222;
        });
        addPreset("Health Bar", "Gameplay", "Red health progress bar", UIWidget.Type.PROGRESS, w -> {
            w.w = 120; w.h = 12; w.barColor = 0xFFFF4444; w.value = 80; w.max = 100;
        });
        addPreset("Mana Bar", "Gameplay", "Blue mana progress bar", UIWidget.Type.PROGRESS, w -> {
            w.w = 120; w.h = 12; w.barColor = 0xFF4488FF; w.value = 60; w.max = 100;
        });
        addPreset("XP Bar", "Gameplay", "Green experience bar", UIWidget.Type.PROGRESS, w -> {
            w.w = 200; w.h = 8; w.barColor = 0xFF44FF44; w.value = 35; w.max = 100;
        });
        addPreset("Toggle Switch", "Input", "On/off toggle switch", UIWidget.Type.TOGGLE, w -> {
            w.label = "Option"; w.w = 30; w.h = 16;
        });
        addPreset("Text Input", "Input", "Text input field", UIWidget.Type.INPUT, w -> {
            w.w = 150; w.h = 20; w.placeholder = "Enter text...";
        });
        addPreset("Separator", "Layout", "Horizontal divider line", UIWidget.Type.DIVIDER, w -> {
            w.w = 200; w.h = 1; w.dividerThickness = 1; w.dividerColor = 0x60FFFFFF;
        });
        addPreset("Container Panel", "Layout", "Background container panel", UIWidget.Type.PANEL, w -> {
            w.w = 200; w.h = 120; w.children = new ArrayList<>();
        });

        // ==================== Mod Overlay Presets ====================

        addPreset("Health Bar (Custom)", "Mod Overlays", "CustomUI health overlay bar", UIWidget.Type.PROGRESS, w -> {
            w.w = 81; w.h = 9; w.barColor = 0xFFFF4444; w.bgColor = 0x80222222; w.value = 20; w.max = 20;
            w.variable = "health";
        });
        addPreset("Crosshair Dot", "Mod Overlays", "Custom crosshair dot overlay", UIWidget.Type.IMAGE, w -> {
            w.w = 15; w.h = 15; w.texture = "customui:textures/gui/crosshair.png"; w.texW = 16; w.texH = 16;
            w.originX = 0.5f; w.originY = 0.5f;
        });
        addPreset("Damage Number", "Mod Overlays", "Floating damage number text", UIWidget.Type.LABEL, w -> {
            w.text = "-12.5"; w.fontSize = 12; w.textColor = 0xFFFF6644; w.textShadow = true;
            w.w = 50; w.h = 14; w.textAlign = "center";
        });
        addPreset("Damage Indicator Arrow", "Mod Overlays", "Directional damage indicator", UIWidget.Type.IMAGE, w -> {
            w.w = 16; w.h = 32; w.texture = "customui:textures/gui/damage_indicator.png";
            w.texW = 16; w.texH = 32; w.originX = 0.5f; w.originY = 0.5f;
        });
        addPreset("Scoreboard Team Bar", "Mod Overlays", "Team progress bar for scoreboard overlay", UIWidget.Type.PROGRESS, w -> {
            w.w = 160; w.h = 10; w.barColor = 0xFFFF0000; w.bgColor = 0x80333333; w.value = 75; w.max = 100;
        });
        addPreset("Scoreboard Timer", "Mod Overlays", "Timer text for scoreboard overlay", UIWidget.Type.LABEL, w -> {
            w.text = "05:00"; w.fontSize = 14; w.textColor = 0xFFFFFF00; w.textShadow = true;
            w.w = 60; w.h = 16; w.textAlign = "center";
        });
        addPreset("Kill Message Line", "Mod Overlays", "Kill feed entry row", UIWidget.Type.LABEL, w -> {
            w.text = "Player1 \u2694 Player2"; w.fontSize = 9; w.textColor = 0xFFFFCCCC; w.textShadow = true;
            w.w = 200; w.h = 12; w.textAlign = "right";
        });
        addPreset("Kill Icon", "Mod Overlays", "Kill count icon overlay", UIWidget.Type.IMAGE, w -> {
            w.w = 32; w.h = 32; w.texture = "customui:textures/gui/kill_icon.png"; w.texW = 32; w.texH = 32;
        });
        addPreset("Objective Marker", "Mod Overlays", "3D-to-2D objective marker HUD element", UIWidget.Type.LABEL, w -> {
            w.text = "\u25C6 Objective A"; w.fontSize = 9; w.textColor = 0xFF44FF44; w.textShadow = true;
            w.w = 100; w.h = 12; w.textAlign = "center";
        });
        addPreset("Objective Distance", "Mod Overlays", "Distance indicator below objective marker", UIWidget.Type.LABEL, w -> {
            w.text = "120m"; w.fontSize = 7; w.textColor = 0xFFAAAAAA; w.textShadow = true;
            w.w = 40; w.h = 10; w.textAlign = "center";
        });
        addPreset("Spectate Info Bar", "Mod Overlays", "Spectating player info bar", UIWidget.Type.LABEL, w -> {
            w.text = "\u25B6 Spectating: Player1"; w.fontSize = 9; w.textColor = 0xFF88CCFF; w.textShadow = true;
            w.w = 180; w.h = 12; w.textAlign = "center";
        });
        addPreset("Gun Ammo Counter", "Mod Overlays", "TaCZ gun HUD ammo display", UIWidget.Type.LABEL, w -> {
            w.text = "30 / 90"; w.fontSize = 12; w.textColor = 0xFFFFFFFF; w.textShadow = true;
            w.w = 80; w.h = 16; w.textAlign = "right";
        });
        addPreset("Gun Ammo Bar", "Mod Overlays", "TaCZ gun HUD ammo progress bar", UIWidget.Type.PROGRESS, w -> {
            w.w = 80; w.h = 4; w.barColor = 0xFFFFAA00; w.bgColor = 0x40FFFFFF; w.value = 30; w.max = 30;
        });
    }

    private void addPreset(String name, String category, String description,
                           UIWidget.Type type, Consumer<UIWidget> configure) {
        WidgetMarketEntry entry = new WidgetMarketEntry();
        entry.name = name;
        entry.category = category;
        entry.description = description;

        UIWidget w = new UIWidget();
        w.type = type;
        w.id = name.toLowerCase().replace(" ", "_");
        configure.accept(w);

        entry.widgets = new ArrayList<>();
        entry.widgets.add(w);
        entries.add(entry);
    }

    // ==================== External File Loading ====================

    private void loadFromDirectory() {
        Path marketDir = FMLPaths.GAMEDIR.get().resolve("config")
                .resolve("customui").resolve("widget_market");

        if (!Files.exists(marketDir)) {
            try {
                Files.createDirectories(marketDir);
                LOGGER.info("[CustomUI] Created widget market directory: {}", marketDir);
            } catch (IOException e) {
                LOGGER.warn("[CustomUI] Failed to create widget market directory: {}", e.getMessage());
            }
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(marketDir, "*.json")) {
            for (Path file : stream) {
                loadMarketFile(file);
            }
        } catch (IOException e) {
            LOGGER.error("[CustomUI] Failed to scan widget market directory: {}", e.getMessage());
        }
    }

    private void loadMarketFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) return;

            if (json.has("entries")) {
                for (JsonElement el : json.getAsJsonArray("entries")) {
                    entries.add(WidgetMarketEntry.fromJson(el.getAsJsonObject()));
                }
            } else {
                entries.add(WidgetMarketEntry.fromJson(json));
            }
            LOGGER.info("[CustomUI] Loaded widget market file: {}", file.getFileName());
        } catch (Exception e) {
            LOGGER.error("[CustomUI] Failed to load widget market file {}: {}",
                    file.getFileName(), e.getMessage());
        }
    }
}
