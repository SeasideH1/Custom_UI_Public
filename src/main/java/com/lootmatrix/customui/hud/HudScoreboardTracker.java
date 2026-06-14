package com.lootmatrix.customui.hud;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.HudScoreboardSyncPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side tracker for scoreboard bindings used by HUD templates.
 *
 * The binding table is rebuilt only when templates change. Every tick the
 * tracker reads the bound scores and pushes index-based delta packets to
 * clients — values travel only when they actually changed, and binding
 * strings travel only inside the (rare) DEFINE packet.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HudScoreboardTracker {

    private static final List<HudScoreboardBinding> BINDINGS = new ArrayList<>();
    private static final List<String> KEYS = new ArrayList<>();
    /** Last broadcast values for fixed-holder bindings (self slots unused). */
    private static int[] lastGlobal = new int[0];
    /** Last per-player values for "@s" bindings (full-size arrays, self slots used). */
    private static final Map<UUID, int[]> lastSelf = new ConcurrentHashMap<>();
    private static boolean hasSelfBindings = false;
    private static boolean dirty = true;

    // Per-tick scratch buffers (server thread only)
    private static final List<Integer> CHANGED_INDICES = new ArrayList<>();
    private static final List<Integer> CHANGED_VALUES = new ArrayList<>();

    private HudScoreboardTracker() {}

    /** Call when the template set changed; the table is rebuilt on the next tick. */
    public static void markDirty() {
        dirty = true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        if (dirty) {
            dirty = false;
            rebuildBindings();
            lastSelf.clear();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendDefine(server, player);
            }
            return;
        }
        if (BINDINGS.isEmpty() || server.getPlayerList().getPlayers().isEmpty()) return;

        Scoreboard scoreboard = server.getScoreboard();

        // Fixed-holder bindings: one comparison set, one broadcast packet
        CHANGED_INDICES.clear();
        CHANGED_VALUES.clear();
        for (int i = 0; i < BINDINGS.size(); i++) {
            HudScoreboardBinding binding = BINDINGS.get(i);
            if (binding.isSelf()) continue;
            int value = readScore(scoreboard, binding.holder, binding.objective);
            if (value != lastGlobal[i]) {
                lastGlobal[i] = value;
                CHANGED_INDICES.add(i);
                CHANGED_VALUES.add(value);
            }
        }
        if (!CHANGED_INDICES.isEmpty()) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(),
                    HudScoreboardSyncPacket.delta(toArray(CHANGED_INDICES), toArray(CHANGED_VALUES)));
        }

        // "@s" bindings: per-player comparison and per-player packets
        if (hasSelfBindings) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                int[] last = lastSelf.computeIfAbsent(player.getUUID(), k -> {
                    // New baseline (e.g. respawned tracker state); DEFINE already
                    // carried login values, so initialize from current scores.
                    int[] init = new int[BINDINGS.size()];
                    for (int i = 0; i < BINDINGS.size(); i++) {
                        HudScoreboardBinding b = BINDINGS.get(i);
                        if (b.isSelf()) init[i] = readScore(scoreboard, player.getScoreboardName(), b.objective);
                    }
                    return init;
                });
                CHANGED_INDICES.clear();
                CHANGED_VALUES.clear();
                for (int i = 0; i < BINDINGS.size(); i++) {
                    HudScoreboardBinding binding = BINDINGS.get(i);
                    if (!binding.isSelf()) continue;
                    int value = readScore(scoreboard, player.getScoreboardName(), binding.objective);
                    if (value != last[i]) {
                        last[i] = value;
                        CHANGED_INDICES.add(i);
                        CHANGED_VALUES.add(value);
                    }
                }
                if (!CHANGED_INDICES.isEmpty()) {
                    ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                            HudScoreboardSyncPacket.delta(toArray(CHANGED_INDICES), toArray(CHANGED_VALUES)));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (dirty) return; // next tick's rebuild will DEFINE everyone, including this player
        sendDefine(server, player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            lastSelf.remove(event.getEntity().getUUID());
        }
    }

    // ==================== Internals ====================

    /** Collect distinct bindings from every known template (datapack + world). */
    private static void rebuildBindings() {
        Set<HudScoreboardBinding> found = new LinkedHashSet<>();
        for (HudTemplate template : HudTemplateRegistry.getInstance().getAll().values()) {
            for (HudElement element : template.elements) {
                collectBindings(element, found, 0);
            }
        }
        BINDINGS.clear();
        BINDINGS.addAll(found);
        KEYS.clear();
        hasSelfBindings = false;
        for (HudScoreboardBinding binding : BINDINGS) {
            KEYS.add(binding.key());
            if (binding.isSelf()) hasSelfBindings = true;
        }
        lastGlobal = new int[BINDINGS.size()];
        collectBaseline();
    }

    /** dataSource + GUI condition bindings, recursing into GROUP children. */
    private static void collectBindings(HudElement element, Set<HudScoreboardBinding> found, int depth) {
        HudScoreboardBinding binding = HudScoreboardBinding.parse(element.dataSource);
        if (binding != null) found.add(binding);
        HudCondition condition = HudCondition.parse(element.condition);
        if (condition != null) found.add(condition.binding);
        if (depth < HudTemplate.MAX_NESTING_DEPTH) {
            for (HudElement child : element.children) {
                collectBindings(child, found, depth + 1);
            }
        }
    }

    private static void collectBaseline() {
        // Baseline so the first regular tick only sends actual changes
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            Scoreboard scoreboard = server.getScoreboard();
            for (int i = 0; i < BINDINGS.size(); i++) {
                HudScoreboardBinding binding = BINDINGS.get(i);
                if (!binding.isSelf()) {
                    lastGlobal[i] = readScore(scoreboard, binding.holder, binding.objective);
                }
            }
        }
    }

    /** Full binding table + this player's current values. */
    private static void sendDefine(MinecraftServer server, ServerPlayer player) {
        if (BINDINGS.isEmpty()) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                    HudScoreboardSyncPacket.define(List.of(), new int[0]));
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        int[] values = new int[BINDINGS.size()];
        int[] self = new int[BINDINGS.size()];
        for (int i = 0; i < BINDINGS.size(); i++) {
            HudScoreboardBinding binding = BINDINGS.get(i);
            String holder = binding.isSelf() ? player.getScoreboardName() : binding.holder;
            values[i] = readScore(scoreboard, holder, binding.objective);
            if (binding.isSelf()) {
                self[i] = values[i];
            } else {
                lastGlobal[i] = values[i];
            }
        }
        lastSelf.put(player.getUUID(), self);
        ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                HudScoreboardSyncPacket.define(KEYS, values));
    }

    private static int readScore(Scoreboard scoreboard, String holder, String objectiveName) {
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) return 0;
        if (!scoreboard.hasPlayerScore(holder, objective)) return 0;
        return scoreboard.getOrCreatePlayerScore(holder, objective).getScore();
    }

    private static int[] toArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i);
        return array;
    }
}
