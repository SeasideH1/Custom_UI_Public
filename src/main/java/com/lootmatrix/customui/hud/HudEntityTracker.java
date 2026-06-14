package com.lootmatrix.customui.hud;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.network.HudEntitySyncPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
 * Server-side tracker for entity field bindings used by HUD templates.
 * Values are resolved per viewing player (selectors use that player's context).
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HudEntityTracker {

    private static final List<HudEntityBinding> BINDINGS = new ArrayList<>();
    private static final List<String> KEYS = new ArrayList<>();
    private static final Map<UUID, String[]> lastPerPlayer = new ConcurrentHashMap<>();
    private static boolean dirty = true;

    private static final List<Integer> CHANGED_INDICES = new ArrayList<>();
    private static final List<String> CHANGED_VALUES = new ArrayList<>();

    private HudEntityTracker() {}

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
            lastPerPlayer.clear();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendDefine(server, player);
            }
            return;
        }
        if (BINDINGS.isEmpty() || server.getPlayerList().getPlayers().isEmpty()) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String[] last = lastPerPlayer.computeIfAbsent(player.getUUID(), k -> new String[BINDINGS.size()]);
            CHANGED_INDICES.clear();
            CHANGED_VALUES.clear();
            for (int i = 0; i < BINDINGS.size(); i++) {
                String value = HudEntityValueResolver.readValue(BINDINGS.get(i), player.createCommandSourceStack());
                if (!value.equals(last[i])) {
                    last[i] = value;
                    CHANGED_INDICES.add(i);
                    CHANGED_VALUES.add(value);
                }
            }
            if (!CHANGED_INDICES.isEmpty()) {
                ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        HudEntitySyncPacket.delta(toArray(CHANGED_INDICES), CHANGED_VALUES));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (dirty) return;
        sendDefine(server, player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            lastPerPlayer.remove(event.getEntity().getUUID());
        }
    }

    private static void rebuildBindings() {
        Set<HudEntityBinding> found = new LinkedHashSet<>();
        for (HudTemplate template : HudTemplateRegistry.getInstance().getAll().values()) {
            for (HudElement element : template.elements) {
                collectBindings(element, found, 0);
            }
        }
        BINDINGS.clear();
        BINDINGS.addAll(found);
        KEYS.clear();
        for (HudEntityBinding binding : BINDINGS) {
            KEYS.add(binding.key());
        }
        HudEntityValueResolver.clearSelectorCache();
    }

    private static void collectBindings(HudElement element, Set<HudEntityBinding> found, int depth) {
        HudEntityBinding binding = HudEntityBinding.parse(element.dataSource);
        if (binding != null) found.add(binding);
        if (depth < HudTemplate.MAX_NESTING_DEPTH) {
            for (HudElement child : element.children) {
                collectBindings(child, found, depth + 1);
            }
        }
    }

    private static void sendDefine(MinecraftServer server, ServerPlayer player) {
        if (BINDINGS.isEmpty()) {
            ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                    HudEntitySyncPacket.define(List.of(), List.of()));
            return;
        }
        List<String> values = new ArrayList<>(BINDINGS.size());
        String[] last = new String[BINDINGS.size()];
        for (int i = 0; i < BINDINGS.size(); i++) {
            String value = HudEntityValueResolver.readValue(BINDINGS.get(i), player.createCommandSourceStack());
            values.add(value);
            last[i] = value;
        }
        lastPerPlayer.put(player.getUUID(), last);
        ModNetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                HudEntitySyncPacket.define(KEYS, values));
    }

    private static int[] toArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) array[i] = list.get(i);
        return array;
    }
}
