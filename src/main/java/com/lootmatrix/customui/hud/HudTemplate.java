package com.lootmatrix.customui.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Format-2 HUD template: a list of animated elements rendered as a game overlay
 * (not a menu screen). Advanced templates may embed other templates through
 * {@link HudElement.Type#TEMPLATE} elements; render-time nesting is depth-limited.
 */
public class HudTemplate {

    public static final int FORMAT = 2;
    /** Maximum nesting depth when templates embed other templates. */
    public static final int MAX_NESTING_DEPTH = 4;
    /** lifetime value meaning "play until explicitly stopped". */
    public static final int LIFETIME_PERSISTENT = -1;

    public String id = "";
    /**
     * Lifetime in ticks. 0 = derive from the last keyframe across all elements;
     * {@link #LIFETIME_PERSISTENT} = persistent until stopped.
     */
    public int lifetime = 0;
    public boolean loop = false;
    /** "hud" = passive overlay (default); "gui" = interactive mouse-driven screen. */
    public String screenType = "hud";
    /** GUI only: pause singleplayer while open. */
    public boolean pauseGame = false;
    /** GUI only: blur the world behind the screen (vanilla blur post chain). */
    public boolean blurBackground = false;
    /** GUI only: key that opens this screen (player-side interact key or template default). */
    @Nullable public HudGuiKey openKey;
    /** GUI only: extra close key besides ESC; its function fires on every close. */
    @Nullable public HudGuiKey closeKey;
    public final List<HudElement> elements = new ArrayList<>();

    public boolean isGui() {
        return "gui".equals(screenType);
    }

    /** Elements sorted by zIndex for rendering (stable; rebuilt on demand). */
    private List<HudElement> renderOrder = null;

    public List<HudElement> renderOrder() {
        if (renderOrder == null) {
            List<HudElement> sorted = new ArrayList<>(elements);
            sorted.sort((a, b) -> Integer.compare(a.zIndex, b.zIndex));
            renderOrder = sorted;
        }
        return renderOrder;
    }

    public void markRenderOrderDirty() {
        renderOrder = null;
    }

    /** Effective playback length in ticks (excludes persistent mode). */
    public int effectiveLifetime() {
        if (lifetime != 0) return lifetime;
        int last = 0;
        for (HudElement e : elements) last = Math.max(last, e.lastKeyframeTick());
        return Math.max(last, 1);
    }

    public boolean isPersistent() {
        return lifetime == LIFETIME_PERSISTENT;
    }

    // ==================== JSON ====================

    public static HudTemplate fromJson(String idFallback, JsonObject json) {
        HudTemplate t = new HudTemplate();
        t.id = json.has("id") ? GsonHelper.getAsString(json, "id") : idFallback;
        t.lifetime = GsonHelper.getAsInt(json, "lifetime", 0);
        t.loop = GsonHelper.getAsBoolean(json, "loop", false);
        t.screenType = GsonHelper.getAsString(json, "screenType", "hud");
        if (json.has("gui") && json.get("gui").isJsonObject()) {
            JsonObject gui = json.getAsJsonObject("gui");
            t.pauseGame = GsonHelper.getAsBoolean(gui, "pauseGame", false);
            t.blurBackground = GsonHelper.getAsBoolean(gui, "blur", false);
            t.openKey = gui.has("open") && gui.get("open").isJsonObject()
                    ? HudGuiKey.fromJson(gui.getAsJsonObject("open")) : null;
            t.closeKey = gui.has("close") && gui.get("close").isJsonObject()
                    ? HudGuiKey.fromJson(gui.getAsJsonObject("close")) : null;
        }
        if (json.has("elements")) {
            for (JsonElement el : json.getAsJsonArray("elements")) {
                t.elements.add(HudElement.fromJson(el.getAsJsonObject()));
            }
        }
        return t;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("id", id);
        if (lifetime != 0) json.addProperty("lifetime", lifetime);
        if (loop) json.addProperty("loop", true);
        if (!"hud".equals(screenType)) json.addProperty("screenType", screenType);
        if (pauseGame || blurBackground || openKey != null || closeKey != null) {
            JsonObject gui = new JsonObject();
            if (pauseGame) gui.addProperty("pauseGame", true);
            if (blurBackground) gui.addProperty("blur", true);
            if (openKey != null) gui.add("open", openKey.toJson());
            if (closeKey != null) gui.add("close", closeKey.toJson());
            json.add("gui", gui);
        }
        JsonArray arr = new JsonArray();
        for (HudElement e : elements) arr.add(e.toJson());
        json.add("elements", arr);
        return json;
    }

    public HudTemplate copy() {
        return fromJson(id, toJson());
    }
}
