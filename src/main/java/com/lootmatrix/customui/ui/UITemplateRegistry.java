package com.lootmatrix.customui.ui;

import com.lootmatrix.customui.Main;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side singleton registry for UI templates and active menu sessions.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class UITemplateRegistry {

    private static final UITemplateRegistry INSTANCE = new UITemplateRegistry();
    public static UITemplateRegistry getInstance() { return INSTANCE; }

    private final Map<ResourceLocation, UITemplate> templates = new HashMap<>();
    private final Map<UUID, String> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public void reloadTemplates(Map<ResourceLocation, UITemplate> loaded) {
        templates.clear();
        templates.putAll(loaded);
    }

    public Map<ResourceLocation, UITemplate> getTemplates() {
        return Collections.unmodifiableMap(templates);
    }

    @Nullable
    public UITemplate getTemplate(String id) {
        for (var entry : templates.entrySet()) {
            if (entry.getKey().toString().equals(id)) return entry.getValue();
            if (entry.getKey().getPath().equals(id)) return entry.getValue();
        }
        return null;
    }

    // ==================== Session Management ====================

    public void openSession(ServerPlayer player, String templateId) {
        activeSessions.put(player.getUUID(), templateId);
    }

    public void closeSession(ServerPlayer player) {
        activeSessions.remove(player.getUUID());
    }

    public boolean isActive(ServerPlayer player, String templateId) {
        return templateId.equals(activeSessions.get(player.getUUID()));
    }

    // ==================== Variable Resolution ====================

    public Map<String, String> resolveVariables(UITemplate template, ServerPlayer player) {
        Map<String, String> resolved = new HashMap<>();
        Scoreboard scoreboard = player.getServer().getScoreboard();

        for (var entry : template.variables.entrySet()) {
            UITemplate.VariableDef def = entry.getValue();
            String val = def.defaultValue;

            if ("scoreboard".equals(def.source)) {
                Objective obj = scoreboard.getObjective(def.objective);
                if (obj != null && scoreboard.hasPlayerScore(player.getScoreboardName(), obj)) {
                    val = String.valueOf(scoreboard.getOrCreatePlayerScore(player.getScoreboardName(), obj).getScore());
                }
            }
            resolved.put(entry.getKey(), val);
        }
        return resolved;
    }

    // ==================== Cooldown Management ====================

    public boolean checkCooldown(UUID playerUUID, String key, int cooldownTicks, long currentTick) {
        Map<String, Long> playerCooldowns = cooldowns.computeIfAbsent(playerUUID, k -> new ConcurrentHashMap<>());
        Long lastExec = playerCooldowns.get(key);
        if (lastExec != null && currentTick - lastExec < cooldownTicks) {
            return false;
        }
        playerCooldowns.put(key, currentTick);
        return true;
    }

    public void onPlayerLogout(UUID playerUUID) {
        activeSessions.remove(playerUUID);
        cooldowns.remove(playerUUID);
    }

    @SubscribeEvent
    public static void onPlayerLogoutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            INSTANCE.onPlayerLogout(event.getEntity().getUUID());
        }
    }
}
