package com.lootmatrix.customui.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.HudTemplateSyncPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Server-side registry for HUD templates.
 *
 * Two template sources are merged (world overrides datapack on id conflict):
 * - datapack: data/&lt;ns&gt;/hud_templates/*.json (read-only, via {@link HudTemplateLoader})
 * - world storage: &lt;world&gt;/customui/hud_templates/*.json (editor uploads, hot-editable)
 *
 * All template definitions are pushed to clients on login / reload / upload so
 * playback never has to wait for a round-trip.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class HudTemplateRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(HudTemplateRegistry.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WORLD_DIR = "customui/hud_templates";
    /** Hard cap on a single uploaded template JSON (bytes). */
    public static final int MAX_TEMPLATE_BYTES = 262144;

    private static final HudTemplateRegistry INSTANCE = new HudTemplateRegistry();

    public static HudTemplateRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, HudTemplate> datapackTemplates = new TreeMap<>();
    private final Map<String, HudTemplate> worldTemplates = new TreeMap<>();
    /** Active playback sessions per player: templateId -> game time when started. */
    private final Map<UUID, Map<String, Long>> activeSessions = new ConcurrentHashMap<>();

    @Nullable
    private MinecraftServer server;

    // ==================== Template access ====================

    /** Merged view, world templates take precedence. */
    public Map<String, HudTemplate> getAll() {
        Map<String, HudTemplate> merged = new TreeMap<>(datapackTemplates);
        merged.putAll(worldTemplates);
        return Collections.unmodifiableMap(merged);
    }

    @Nullable
    public HudTemplate get(String id) {
        HudTemplate t = worldTemplates.get(id);
        if (t != null) return t;
        t = datapackTemplates.get(id);
        if (t != null) return t;
        // Allow path-only lookup ("foo" matches "customui:foo")
        for (Map.Entry<String, HudTemplate> entry : worldTemplates.entrySet()) {
            if (pathMatches(entry.getKey(), id)) return entry.getValue();
        }
        for (Map.Entry<String, HudTemplate> entry : datapackTemplates.entrySet()) {
            if (pathMatches(entry.getKey(), id)) return entry.getValue();
        }
        return null;
    }

    private static boolean pathMatches(String fullId, String query) {
        int colon = fullId.indexOf(':');
        return colon >= 0 && fullId.substring(colon + 1).equals(query);
    }

    // ==================== Datapack reload ====================

    public void reloadDatapackTemplates(Map<ResourceLocation, HudTemplate> loaded) {
        datapackTemplates.clear();
        for (Map.Entry<ResourceLocation, HudTemplate> entry : loaded.entrySet()) {
            datapackTemplates.put(entry.getKey().toString(), entry.getValue());
        }
        HudScoreboardTracker.markDirty();
        HudEntityTracker.markDirty();
        if (server != null) {
            syncAllToAllPlayers();
        }
    }

    // ==================== World storage ====================

    public void loadFromWorld(MinecraftServer server) {
        this.server = server;
        loadFromWorldDirectory(worldTemplateDir(server));
    }

    void loadFromWorldDirectory(Path dir) {
        worldTemplates.clear();
        if (!Files.isDirectory(dir)) {
            HudScoreboardTracker.markDirty();
            HudEntityTracker.markDirty();
            return;
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(file -> {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    JsonObject obj = GSON.fromJson(json, JsonObject.class);
                    String name = file.getFileName().toString();
                    name = name.substring(0, name.length() - 5);
                    String fallbackId = Main.MODID + ":" + name;
                    HudTemplate template = HudTemplate.fromJson(fallbackId, obj);
                    if (template.id == null || template.id.isEmpty()) template.id = fallbackId;
                    worldTemplates.put(template.id, template);
                } catch (Exception e) {
                    LOGGER.error("[CustomUI] Failed to load world HUD template {}: {}", file, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.error("[CustomUI] Failed to list world HUD templates", e);
        }
        HudScoreboardTracker.markDirty();
        HudEntityTracker.markDirty();
        LOGGER.info("[CustomUI] Loaded {} world HUD template(s)", worldTemplates.size());
    }

    /**
     * Persist an uploaded/edited template into world storage and broadcast the
     * updated definition to every online player. Returns null on success or a
     * human-readable error.
     */
    @Nullable
    public String saveWorldTemplate(String templateJson) {
        if (server == null) return "Server not ready";
        if (templateJson.getBytes(StandardCharsets.UTF_8).length > MAX_TEMPLATE_BYTES) {
            return "Template too large (max " + (MAX_TEMPLATE_BYTES / 1024) + " KB)";
        }
        HudTemplate template;
        try {
            JsonObject obj = GSON.fromJson(templateJson, JsonObject.class);
            template = HudTemplate.fromJson("", obj);
        } catch (Exception e) {
            return "Invalid template JSON: " + e.getMessage();
        }
        if (template.id == null || template.id.isEmpty()) return "Template id is required";
        ResourceLocation rl = ResourceLocation.tryParse(template.id);
        if (rl == null) return "Invalid template id: " + template.id;
        template.id = rl.toString();

        try {
            Path dir = worldTemplateDir(server);
            Files.createDirectories(dir);
            String fileName = rl.getNamespace() + "." + rl.getPath().replace('/', '.') + ".json";
            Path file = dir.resolve(fileName);
            Files.writeString(file, GSON.toJson(template.toJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[CustomUI] Failed to save HUD template {}", template.id, e);
            return "Failed to write template file";
        }

        worldTemplates.put(template.id, template);
        HudScoreboardTracker.markDirty();
        HudEntityTracker.markDirty();
        broadcastTemplate(template);
        LOGGER.info("[CustomUI] Saved HUD template '{}' to world storage", template.id);
        return null;
    }

    /**
     * Delete a world-storage template (editor uploads). Datapack templates are
     * read-only and cannot be deleted. Returns null on success or a
     * human-readable error. On success the full template set is re-synced so
     * every client drops the removed definition.
     */
    @Nullable
    public String deleteWorldTemplate(String templateId) {
        if (server == null) return "Server not ready";
        ResourceLocation rl = ResourceLocation.tryParse(templateId);
        if (rl == null) return "Invalid template id: " + templateId;
        String id = rl.toString();
        if (!worldTemplates.containsKey(id)) {
            return datapackTemplates.containsKey(id)
                    ? "Datapack templates are read-only"
                    : "Template not found: " + id;
        }
        String fileName = rl.getNamespace() + "." + rl.getPath().replace('/', '.') + ".json";
        try {
            Files.deleteIfExists(worldTemplateDir(server).resolve(fileName));
        } catch (IOException e) {
            LOGGER.error("[CustomUI] Failed to delete HUD template file {}", fileName, e);
            return "Failed to delete template file";
        }
        worldTemplates.remove(id);
        HudScoreboardTracker.markDirty();
        HudEntityTracker.markDirty();
        HudTemplate datapackFallback = datapackTemplates.get(id);
        if (datapackFallback != null) {
            // World override removed: clients fall back to the datapack definition
            broadcastTemplate(datapackFallback);
        } else {
            broadcastRemoveTemplate(id);
        }
        LOGGER.info("[CustomUI] Deleted HUD template '{}' from world storage", id);
        return null;
    }

    /** Tiny id-only broadcast so clients drop one definition without a full re-sync. */
    private void broadcastRemoveTemplate(String templateId) {
        if (server == null) return;
        ModNetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(),
                new com.lootmatrix.customui.network.HudTemplateRemovePacket(templateId));
    }

    private static Path worldTemplateDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(WORLD_DIR);
    }

    // ==================== Client sync ====================

    /** Push the full template set to one player (login / on demand). */
    public void syncAllTo(ServerPlayer player) {
        List<String> jsons = new ArrayList<>();
        for (HudTemplate t : getAll().values()) {
            jsons.add(t.toJson().toString());
        }
        for (HudTemplateSyncPacket packet : HudTemplateSyncPacket.full(jsons)) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public void syncAllToAllPlayers() {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncAllTo(player);
        }
    }

    /** Incremental single-template broadcast (after editor upload). */
    public void broadcastTemplate(HudTemplate template) {
        if (server == null) return;
        HudTemplateSyncPacket packet = HudTemplateSyncPacket.merge(template.toJson().toString());
        ModNetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(), packet);
    }

    // ==================== Playback sessions ====================

    public void onPlay(ServerPlayer player, String templateId) {
        long now = player.serverLevel().getGameTime();
        activeSessions.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                .put(templateId, now);
    }

    public void onStop(ServerPlayer player, String templateId) {
        Map<String, Long> sessions = activeSessions.get(player.getUUID());
        if (sessions != null) sessions.remove(templateId);
    }

    public void onStopAll(ServerPlayer player) {
        activeSessions.remove(player.getUUID());
    }

    public Map<String, Long> getActiveSessions(ServerPlayer player) {
        Map<String, Long> sessions = activeSessions.get(player.getUUID());
        return sessions != null ? Collections.unmodifiableMap(sessions) : Map.of();
    }

    // ==================== Events ====================

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            INSTANCE.server = player.getServer();
            INSTANCE.loadFromWorld(player.getServer());
            INSTANCE.syncAllTo(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            INSTANCE.activeSessions.remove(event.getEntity().getUUID());
        }
    }
}
