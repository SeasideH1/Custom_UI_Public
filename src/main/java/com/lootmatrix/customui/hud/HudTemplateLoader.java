package com.lootmatrix.customui.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Datapack reload listener for HUD templates.
 * Loads JSON files from: data/&lt;namespace&gt;/hud_templates/&lt;name&gt;.json
 * Single-file failures never break the full reload.
 */
public class HudTemplateLoader extends SimplePreparableReloadListener<Map<ResourceLocation, HudTemplate>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(HudTemplateLoader.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "hud_templates";

    @Override
    protected Map<ResourceLocation, HudTemplate> prepare(ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, HudTemplate> result = new HashMap<>();
        var resources = rm.listResources(DIRECTORY, loc -> loc.getPath().endsWith(".json"));

        for (var entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            String path = fileId.getPath();
            String name = path.substring(DIRECTORY.length() + 1, path.length() - 5);
            ResourceLocation templateId = new ResourceLocation(fileId.getNamespace(), name);

            try (var reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                HudTemplate template = HudTemplate.fromJson(templateId.toString(), json);
                template.id = templateId.toString();
                result.put(templateId, template);
            } catch (Exception e) {
                LOGGER.error("[CustomUI] Failed to load HUD template {}: {}", templateId, e.getMessage());
            }
        }
        return result;
    }

    @Override
    protected void apply(Map<ResourceLocation, HudTemplate> prepared, ResourceManager rm, ProfilerFiller profiler) {
        HudTemplateRegistry.getInstance().reloadDatapackTemplates(prepared);
        LOGGER.info("[CustomUI] Loaded {} HUD template(s)", prepared.size());
    }
}
