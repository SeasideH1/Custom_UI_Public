package com.lootmatrix.customui.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.hud.HudTemplate;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-side cache of every HUD template definition pushed by the server.
 * Templates are parsed once on arrival; textures stay lazy — they are only
 * resolved/bound the first time an element actually renders.
 */
@OnlyIn(Dist.CLIENT)
public final class HudClientTemplateCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(HudClientTemplateCache.class);
    private static final Gson GSON = new GsonBuilder().create();

    private static final Map<String, HudTemplate> TEMPLATES = new ConcurrentHashMap<>();
    private static final List<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();
    /** Bumped on every content change; lets per-element resolution caches revalidate cheaply. */
    private static volatile int generation = 0;

    private HudClientTemplateCache() {}

    public static int generation() {
        return generation;
    }

    public static AutoCloseable addChangeListener(Runnable listener) {
        CHANGE_LISTENERS.add(listener);
        return () -> CHANGE_LISTENERS.remove(listener);
    }

    /** Handle a definition sync packet (already on the client main thread). */
    public static void applySync(boolean reset, List<String> templateJsons) {
        if (reset) {
            TEMPLATES.clear();
            HudElementRuntime.clearAll();
        }
        for (String json : templateJsons) {
            try {
                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                HudTemplate template = HudTemplate.fromJson("", obj);
                if (template.id == null || template.id.isEmpty()) continue;
                HudTemplate previous = TEMPLATES.put(template.id, template);
                // Invalidate only the replaced definition's runtime caches and keep
                // already-active playback instances following the new definition.
                if (previous != null) {
                    HudElementRuntime.clearTemplate(previous);
                }
                HudPlaybackManager.onTemplateReplaced(template.id, template);
            } catch (Exception e) {
                LOGGER.error("[CustomUI] Failed to parse synced HUD template: {}", e.getMessage());
            }
        }
        generation++;
        notifyChangeListeners();
    }

    /** Handle a single-template removal pushed by the server (client main thread). */
    public static void applyRemove(String templateId) {
        HudTemplate previous = TEMPLATES.remove(templateId);
        if (previous == null) {
            return;
        }
        HudElementRuntime.clearTemplate(previous);
        HudPlaybackManager.stop(templateId);
        generation++;
        notifyChangeListeners();
    }

    @Nullable
    public static HudTemplate get(String id) {
        HudTemplate t = TEMPLATES.get(id);
        if (t != null) return t;
        // Path-only lookup ("foo" matches "ns:foo"), allocation-free comparison
        for (Map.Entry<String, HudTemplate> entry : TEMPLATES.entrySet()) {
            String key = entry.getKey();
            int colon = key.indexOf(':');
            if (colon >= 0 && key.length() - colon - 1 == id.length()
                    && key.regionMatches(colon + 1, id, 0, id.length())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Sorted snapshot for the editor template library. */
    public static Map<String, HudTemplate> snapshot() {
        return new TreeMap<>(TEMPLATES);
    }

    public static void clear() {
        TEMPLATES.clear();
        HudElementRuntime.clearAll();
        generation++;
        notifyChangeListeners();
    }

    private static void notifyChangeListeners() {
        for (Runnable listener : CHANGE_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.warn("[CustomUI] HUD template cache listener failed", e);
            }
        }
    }
}
