package com.lootmatrix.customui.capturezone;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.lootmatrix.customui.network.ModNetworkHandler;
import com.lootmatrix.customui.network.CaptureZoneSyncPacket;
import com.lootmatrix.customui.network.CaptureZoneGeometrySyncPacket;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Server-side manager for capture zones.
 * Handles: zone registration, tick-based player detection, capture progress, SavedData persistence,
 * mcfunction execution, and network sync to clients.
 */
public class CaptureZoneManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureZoneManager.class);
    private static final CaptureZoneManager INSTANCE = new CaptureZoneManager();

    /** Zone definitions loaded from datapacks. */
    private final Map<String, CaptureZone> zoneDefinitions = new LinkedHashMap<>();

    /** Runtime state for each active zone. */
    private final Map<String, ZoneState> zoneStates = new LinkedHashMap<>();

    /** Set of active zone ids (zones currently being tracked). */
    private final Set<String> activeZoneIds = new LinkedHashSet<>();

    /** Tracking tick interval for player detection to minimize bandwidth. */
    private static final int DETECT_INTERVAL = 5; // every 5 ticks
    private int tickCounter = 0;

    /** Previous sync states for delta detection. */
    private final Map<String, ZoneState> prevSyncStates = new HashMap<>();
    private static final float PROGRESS_SYNC_STEP = 1.0f / 64.0f;

    public static CaptureZoneManager getInstance() { return INSTANCE; }

    /**
     * Runtime state for a single capture zone.
     */
    public static class ZoneState {
        public String zoneId;
        /** Capture progress 0.0 to 1.0 */
        public float progress = 0f;
        /** Current capturing team name, or null if uncaptured. */
        @Nullable public String capturingTeam;
        /** The team that currently owns this zone (capture completed). */
        @Nullable public String ownerTeam;
        /** Whether the zone is contested (multiple teams present). */
        public boolean contested = false;
        /** Remaining ticks the zone is locked after capture (HQ mode). */
        public int lockRemaining = 0;
        /** Players currently inside the zone. */
        public transient Set<UUID> playersInside = new HashSet<>();

        public ZoneState(String zoneId) { this.zoneId = zoneId; }

        public ZoneState copy() {
            ZoneState c = new ZoneState(zoneId);
            c.progress = progress;
            c.capturingTeam = capturingTeam;
            c.ownerTeam = ownerTeam;
            c.contested = contested;
            return c;
        }

        public boolean isDifferent(ZoneState other) {
            if (other == null) return true;
            if (quantizeProgress(progress) != quantizeProgress(other.progress)) return true;
            if (!Objects.equals(capturingTeam, other.capturingTeam)) return true;
            if (!Objects.equals(ownerTeam, other.ownerTeam)) return true;
            return contested != other.contested;
        }
    }

    private static int quantizeProgress(float progress) {
        return Math.round(Math.max(0f, Math.min(1f, progress)) / PROGRESS_SYNC_STEP);
    }

    // ==================== Registration ====================

    public void clearDefinitions() {
        zoneDefinitions.clear();
    }

    public void registerZone(CaptureZone zone) {
        zoneDefinitions.put(zone.id, zone);
    }

    @Nullable
    public CaptureZone getZone(String id) { return zoneDefinitions.get(id); }

    public Collection<CaptureZone> getAllZones() { return zoneDefinitions.values(); }

    public void activateZone(String zoneId) {
        if (zoneDefinitions.containsKey(zoneId)) {
            activeZoneIds.add(zoneId);
            zoneStates.computeIfAbsent(zoneId, ZoneState::new);
            // Send zone geometry to all clients for 3D boundary rendering
            CaptureZone zone = zoneDefinitions.get(zoneId);
            if (zone != null) {
                ModNetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(),
                        new CaptureZoneGeometrySyncPacket(zone));
            }
        }
    }

    public void deactivateZone(String zoneId) {
        activeZoneIds.remove(zoneId);
    }

    public void resetZone(String zoneId) {
        ZoneState state = zoneStates.get(zoneId);
        if (state != null) {
            state.progress = 0f;
            state.capturingTeam = null;
            state.ownerTeam = null;
            state.contested = false;
            state.playersInside.clear();
        }
    }

    public boolean isActive(String zoneId) { return activeZoneIds.contains(zoneId); }

    @Nullable
    public ZoneState getState(String zoneId) { return zoneStates.get(zoneId); }

    public Collection<String> getActiveZoneIds() { return activeZoneIds; }

    // ==================== Tick Handler ====================

    public static void register() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    /** Sync all active zone geometries to a player on login. */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        for (String zoneId : activeZoneIds) {
            CaptureZone zone = zoneDefinitions.get(zoneId);
            if (zone != null) {
                ModNetworkHandler.INSTANCE.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new CaptureZoneGeometrySyncPacket(zone));
                // Also send current state
                ZoneState state = zoneStates.get(zoneId);
                if (state != null) {
                    ModNetworkHandler.INSTANCE.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new CaptureZoneSyncPacket(zoneId, zone.displayName,
                                    state.progress, state.capturingTeam,
                                    state.ownerTeam, state.contested));
                }
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (activeZoneIds.isEmpty()) return;

        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;

        // Player detection at reduced interval
        if (tickCounter % DETECT_INTERVAL == 0) {
            detectPlayers(level);
        }

        // Update capture progress every tick for smooth animation
        updateCaptureProgress(level);

        // Sync to clients (delta-compressed)
        if (tickCounter % DETECT_INTERVAL == 0) {
            syncToClients(level);
        }
    }

    private void detectPlayers(ServerLevel level) {
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();

        for (String zoneId : activeZoneIds) {
            CaptureZone zone = zoneDefinitions.get(zoneId);
            ZoneState state = zoneStates.get(zoneId);
            if (zone == null || state == null) continue;

            state.playersInside.clear();
            for (ServerPlayer player : players) {
                if (player.isSpectator() || !player.isAlive()) continue;
                if (zone.containsPoint(player.getX(), player.getY(), player.getZ())) {
                    state.playersInside.add(player.getUUID());
                }
            }
        }
    }

    private void updateCaptureProgress(ServerLevel level) {
        MinecraftServer server = level.getServer();
        List<String> zonesToRotate = null; // deferred rotation to avoid ConcurrentModification

        for (String zoneId : activeZoneIds) {
            CaptureZone zone = zoneDefinitions.get(zoneId);
            ZoneState state = zoneStates.get(zoneId);
            if (zone == null || state == null) continue;

            // Handle lock-after-capture countdown (headquarters mode)
            if (state.lockRemaining > 0) {
                state.lockRemaining--;
                if (state.lockRemaining <= 0 && zone.rotateAfterCapture && zone.nextZoneId != null) {
                    if (zonesToRotate == null) zonesToRotate = new ArrayList<>();
                    zonesToRotate.add(zoneId);
                }
                continue; // skip capture logic while locked
            }

            // Count teams present
            Map<String, List<ServerPlayer>> teamPresence = new HashMap<>();
            for (UUID uuid : state.playersInside) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null) continue;
                PlayerTeam team = player.getTeam() instanceof PlayerTeam ? (PlayerTeam) player.getTeam() : null;
                String teamName = team != null ? team.getName() : "__none__";
                teamPresence.computeIfAbsent(teamName, k -> new ArrayList<>()).add(player);
            }

            boolean wasContested = state.contested;
            state.contested = teamPresence.size() > 1;

            if (state.contested) {
                // Call contested function
                if (!wasContested && zone.onContestedFunction != null) {
                    runFunction(server, zone.onContestedFunction);
                }
                // Progress frozen when contested
                continue;
            }

            if (teamPresence.isEmpty()) {
                // No one in zone: slow decay
                if (state.progress > 0f) {
                    state.progress = Math.max(0f, state.progress - 0.5f / zone.captureTimeTicks);
                    if (state.progress <= 0f) {
                        state.capturingTeam = null;
                    }
                }
                continue;
            }

            // Single team present
            String presentTeam = teamPresence.keySet().iterator().next();
            List<ServerPlayer> teamPlayers = teamPresence.get(presentTeam);

            // If same team as owner, do nothing
            if (presentTeam.equals(state.ownerTeam)) continue;

            // If zone is owned and recapture is disabled, skip
            if (state.ownerTeam != null && !zone.allowRecapture) continue;

            // Check minimum players requirement
            if (teamPlayers.size() < zone.minPlayersToCapture) continue;

            // If different team than current capturer, reset progress
            if (state.capturingTeam != null && !presentTeam.equals(state.capturingTeam)) {
                state.progress = 0f;
            }

            boolean wasCapturing = state.capturingTeam != null;
            state.capturingTeam = presentTeam;

            // Fire capture start
            if (!wasCapturing && zone.onCaptureStartFunction != null) {
                runFunction(server, zone.onCaptureStartFunction);
            }

            // Increment progress (more players = faster, with diminishing returns)
            float speedMultiplier = 1.0f + (teamPlayers.size() - 1) * 0.3f;
            state.progress = Math.min(1.0f, state.progress + speedMultiplier / zone.captureTimeTicks);

            // Fire capture tick function
            if (zone.onCaptureTickFunction != null) {
                for (ServerPlayer p : teamPlayers) {
                    runFunctionAs(server, zone.onCaptureTickFunction, p);
                }
            }

            // Check capture complete
            if (state.progress >= 1.0f) {
                state.ownerTeam = presentTeam;
                state.progress = 1.0f;
                if (zone.onCaptureCompleteFunction != null) {
                    runFunction(server, zone.onCaptureCompleteFunction);
                }
                // Apply lock-after-capture if configured
                if (zone.lockAfterCaptureTicks > 0) {
                    state.lockRemaining = zone.lockAfterCaptureTicks;
                }
                // Handle zone rotation (hardpoint mode)
                if (zone.rotateAfterCapture && zone.nextZoneId != null && zone.lockAfterCaptureTicks <= 0) {
                    if (zonesToRotate == null) zonesToRotate = new ArrayList<>();
                    zonesToRotate.add(zoneId);
                }
            }
        }

        // Process zone rotations
        if (zonesToRotate != null) {
            for (String fromZoneId : zonesToRotate) {
                CaptureZone fromZone = zoneDefinitions.get(fromZoneId);
                if (fromZone != null && fromZone.nextZoneId != null) {
                    deactivateZone(fromZoneId);
                    activateZone(fromZone.nextZoneId);
                    resetZone(fromZone.nextZoneId);
                    LOGGER.info("[CustomUI] Zone rotation: {} -> {}", fromZoneId, fromZone.nextZoneId);
                }
            }
        }
    }

    private void syncToClients(ServerLevel level) {
        for (String zoneId : activeZoneIds) {
            ZoneState state = zoneStates.get(zoneId);
            if (state == null) continue;

            ZoneState prev = prevSyncStates.get(zoneId);
            if (state.isDifferent(prev)) {
                CaptureZone zone = zoneDefinitions.get(zoneId);
                String displayName = zone != null ? zone.displayName : zoneId;
                CaptureZoneSyncPacket packet = new CaptureZoneSyncPacket(
                        zoneId, displayName, state.progress, state.capturingTeam,
                        state.ownerTeam, state.contested
                );
                ModNetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(), packet);
                prevSyncStates.put(zoneId, state.copy());
            }
        }
    }

    // ==================== mcfunction Execution ====================

    private void runFunction(MinecraftServer server, String functionId) {
        try {
            ResourceLocation loc = ResourceLocation.tryParse(functionId);
            if (loc == null) return;
            server.getFunctions().get(loc).ifPresent(func -> {
                CommandSourceStack source = server.createCommandSourceStack()
                        .withSuppressedOutput().withPermission(2);
                server.getFunctions().execute(func, source);
            });
        } catch (Exception e) {
            LOGGER.warn("[CustomUI] Failed to execute function {}: {}", functionId, e.getMessage());
        }
    }

    private void runFunctionAs(MinecraftServer server, String functionId, ServerPlayer player) {
        try {
            ResourceLocation loc = ResourceLocation.tryParse(functionId);
            if (loc == null) return;
            server.getFunctions().get(loc).ifPresent(func -> {
                CommandSourceStack source = player.createCommandSourceStack()
                        .withSuppressedOutput().withPermission(2);
                server.getFunctions().execute(func, source);
            });
        } catch (Exception e) {
            LOGGER.warn("[CustomUI] Failed to execute function {} as {}: {}",
                    functionId, player.getScoreboardName(), e.getMessage());
        }
    }

    // ==================== SavedData ====================

    public static class CaptureZoneData extends SavedData {
        private static final String DATA_NAME = "customui_capture_zones";

        private final Map<String, ZoneState> savedStates = new HashMap<>();
        private final Set<String> savedActiveIds = new HashSet<>();

        public CaptureZoneData() { super(); }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CaptureZoneManager mgr = CaptureZoneManager.getInstance();
            ListTag statesList = new ListTag();
            for (var entry : mgr.zoneStates.entrySet()) {
                ZoneState state = entry.getValue();
                CompoundTag st = new CompoundTag();
                st.putString("zoneId", state.zoneId);
                st.putFloat("progress", state.progress);
                if (state.capturingTeam != null) st.putString("capturingTeam", state.capturingTeam);
                if (state.ownerTeam != null) st.putString("ownerTeam", state.ownerTeam);
                st.putBoolean("contested", state.contested);
                statesList.add(st);
            }
            tag.put("states", statesList);

            ListTag activeList = new ListTag();
            for (String id : mgr.activeZoneIds) {
                CompoundTag at = new CompoundTag();
                at.putString("id", id);
                activeList.add(at);
            }
            tag.put("activeZones", activeList);
            return tag;
        }

        public static CaptureZoneData load(CompoundTag tag) {
            CaptureZoneData data = new CaptureZoneData();
            CaptureZoneManager mgr = CaptureZoneManager.getInstance();

            if (tag.contains("states")) {
                ListTag statesList = tag.getList("states", Tag.TAG_COMPOUND);
                for (int i = 0; i < statesList.size(); i++) {
                    CompoundTag st = statesList.getCompound(i);
                    String zoneId = st.getString("zoneId");
                    ZoneState state = new ZoneState(zoneId);
                    state.progress = st.getFloat("progress");
                    state.capturingTeam = st.contains("capturingTeam") ? st.getString("capturingTeam") : null;
                    state.ownerTeam = st.contains("ownerTeam") ? st.getString("ownerTeam") : null;
                    state.contested = st.getBoolean("contested");
                    mgr.zoneStates.put(zoneId, state);
                }
            }
            if (tag.contains("activeZones")) {
                ListTag activeList = tag.getList("activeZones", Tag.TAG_COMPOUND);
                for (int i = 0; i < activeList.size(); i++) {
                    mgr.activeZoneIds.add(activeList.getCompound(i).getString("id"));
                }
            }
            return data;
        }

        @Nullable
        public static CaptureZoneData get(MinecraftServer server) {
            if (server == null || server.overworld() == null) return null;
            return server.overworld().getDataStorage()
                    .computeIfAbsent(CaptureZoneData::load, CaptureZoneData::new, DATA_NAME);
        }

        public void markDirtyIfNeeded(MinecraftServer server) {
            CaptureZoneData data = get(server);
            if (data != null) data.setDirty();
        }
    }

    /**
     * Load saved state from world data. Called on server start.
     */
    public void loadFromWorld(MinecraftServer server) {
        CaptureZoneData.get(server); // triggers load
    }

    /**
     * Save state to world data. Called periodically.
     */
    public void saveToWorld(MinecraftServer server) {
        CaptureZoneData data = CaptureZoneData.get(server);
        if (data != null) data.setDirty();
    }
}
