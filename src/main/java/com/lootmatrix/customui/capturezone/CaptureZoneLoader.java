package com.lootmatrix.customui.capturezone;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Datapack reload listener that loads capture zone definitions from
 * data/<namespace>/capture_zones/<name>.json
 */
public class CaptureZoneLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureZoneLoader.class);
    private static final Gson GSON = new GsonBuilder().create();

    public CaptureZoneLoader() {
        super(GSON, "capture_zones");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager, ProfilerFiller profiler) {
        CaptureZoneManager manager = CaptureZoneManager.getInstance();
        manager.clearDefinitions();

        int count = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                // Use file path as the fallback id
                String fallbackId = entry.getKey().getNamespace() + ":" + entry.getKey().getPath();
                if (!json.has("id")) {
                    json.addProperty("id", fallbackId);
                }
                CaptureZone zone = CaptureZone.fromJson(json);
                manager.registerZone(zone);
                count++;
            } catch (Exception e) {
                LOGGER.error("[CustomUI] Failed to load capture zone {}: {}", entry.getKey(), e.getMessage());
            }
        }
        LOGGER.info("[CustomUI] Loaded {} capture zone definition(s)", count);
    }
}
