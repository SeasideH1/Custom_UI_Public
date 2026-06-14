package com.lootmatrix.customui.ui;

import com.google.gson.*;
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
 * Server-side datapack reload listener for UI templates.
 * Loads JSON files from: data/&lt;namespace&gt;/ui_templates/&lt;name&gt;.json
 */
public class UITemplateLoader extends SimplePreparableReloadListener<Map<ResourceLocation, UITemplate>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UITemplateLoader.class);
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "ui_templates";

    @Override
    protected Map<ResourceLocation, UITemplate> prepare(ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, UITemplate> result = new HashMap<>();
        var resources = rm.listResources(DIRECTORY, loc -> loc.getPath().endsWith(".json"));

        for (var entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            String path = fileId.getPath();
            String name = path.substring(DIRECTORY.length() + 1, path.length() - 5);
            ResourceLocation templateId = new ResourceLocation(fileId.getNamespace(), name);

            try (var reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                UITemplate template = UITemplate.fromJson(templateId.toString(), json);
                result.put(templateId, template);
            } catch (Exception e) {
                LOGGER.error("[CustomUI] Failed to load UI template {}: {}", templateId, e.getMessage());
            }
        }
        return result;
    }

    @Override
    protected void apply(Map<ResourceLocation, UITemplate> prepared, ResourceManager rm, ProfilerFiller profiler) {
        UITemplateRegistry.getInstance().reloadTemplates(prepared);
        LOGGER.info("[CustomUI] Loaded {} UI template(s)", prepared.size());
    }
}
