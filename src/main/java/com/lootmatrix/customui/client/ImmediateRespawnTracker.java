package com.lootmatrix.customui.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side tracker for immediate respawn events.
 *
 * Tracks other player deaths and respawns to suppress their rendering
 * for a short window (~5 ticks / 250ms) after immediate respawn,
 * hiding the brief gamemode flash (e.g. ADVENTURE → SPECTATOR).
 *
 * Also tracks the local player's immediate respawn to wait for the
 * gamemode change packet before proceeding, with a timeout fallback.
 */
@OnlyIn(Dist.CLIENT)
public final class ImmediateRespawnTracker {

    private static final long DEATH_WINDOW_MS = 1000L;
    private static final long REMOTE_SUPPRESS_DURATION_MS = 450L;
    private static final long LOCAL_SUPPRESS_DURATION_MS = 400L;
    private static final long LOCAL_GAME_MODE_SETTLE_MS = 150L;

    /** Other players: UUID → system time when death event was received */
    private static final Map<UUID, Long> recentDeaths = new ConcurrentHashMap<>();

    /** Other players: UUID → suppression expiry time */
    private static final Map<UUID, Long> suppressedPlayers = new ConcurrentHashMap<>();

    /** Other players: entity id → suppression expiry time */
    private static final Map<Integer, Long> suppressedEntityIds = new ConcurrentHashMap<>();

    /** Local player: whether we are still waiting for the post-respawn game mode update */
    private static volatile boolean localWaitingForGameMode = false;

    /** Local player: suppression expiry time */
    private static volatile long localSuppressUntil = 0L;

    private ImmediateRespawnTracker() {}

    private static boolean isImmediateRespawnEnabled() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null
                && mc.level.getGameRules().getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN);
    }

    // ==================== Other Players ====================

    /**
     * Called when a player death entity event (byte 3) is received for another player.
     */
    public static void onPlayerDeath(UUID playerUUID) {
        if (playerUUID != null && isImmediateRespawnEnabled()) {
            recentDeaths.put(playerUUID, Util.getMillis());
        }
    }

    /**
     * Called when a player entity is added to the client level.
     * If the UUID matches a recent death and doImmediateRespawn is on,
     * begins render suppression.
     */
    public static void onPlayerEntityAdded(int entityId, UUID playerUUID) {
        if (playerUUID == null) return;

        Long deathTime = recentDeaths.remove(playerUUID);
        if (deathTime == null) return;

        long now = Util.getMillis();
        if (now - deathTime <= DEATH_WINDOW_MS) {
            long suppressUntil = now + REMOTE_SUPPRESS_DURATION_MS;
            suppressedPlayers.put(playerUUID, suppressUntil);
            suppressedEntityIds.put(entityId, suppressUntil);
        }
    }

    /**
     * Check if a player's rendering should be suppressed (recently immediately respawned).
     */
    public static boolean shouldSuppressRendering(UUID playerUUID) {
        return isSuppressed(suppressedPlayers, playerUUID);
    }

    public static boolean shouldSuppressInterpolation(int entityId, UUID playerUUID) {
        if (playerUUID != null && shouldSuppressRendering(playerUUID)) {
            return true;
        }
        return isSuppressed(suppressedEntityIds, entityId);
    }

    // ==================== Local Player ====================

    /**
     * Called when the local player receives a respawn packet while dead
     * and doImmediateRespawn is enabled. Enters waiting state for
     * the gamemode change packet.
     */
    public static void onLocalImmediateRespawn() {
        long now = Util.getMillis();
        localWaitingForGameMode = true;
        localSuppressUntil = now + LOCAL_SUPPRESS_DURATION_MS;
    }

    /**
     * Called when a CHANGE_GAME_MODE game event is received.
     * Clears the local waiting state.
     */
    public static void onLocalGameModeReceived() {
        long now = Util.getMillis();
        localWaitingForGameMode = false;
        localSuppressUntil = Math.max(localSuppressUntil, now + LOCAL_GAME_MODE_SETTLE_MS);
    }

    /**
     * Check if the local player is in the waiting state
     * (after immediate respawn, before gamemode packet arrives).
     * Auto-clears on timeout.
     */
    public static boolean isLocalPlayerWaiting() {
        return shouldSuppressLocalPlayer();
    }

    public static boolean shouldSuppressLocalPlayer() {
        if (localSuppressUntil <= 0L) {
            return false;
        }
        if (Util.getMillis() > localSuppressUntil) {
            localSuppressUntil = 0L;
            localWaitingForGameMode = false;
            return false;
        }
        return true;
    }

    public static boolean shouldSuppressLocalInterpolation() {
        return shouldSuppressLocalPlayer();
    }

    public static boolean isWaitingForLocalGameMode() {
        return localWaitingForGameMode && shouldSuppressLocalPlayer();
    }

    /**
     * Clear all state (e.g., when disconnecting).
     */
    public static void clear() {
        recentDeaths.clear();
        suppressedPlayers.clear();
        suppressedEntityIds.clear();
        localWaitingForGameMode = false;
        localSuppressUntil = 0L;
    }

    private static <T> boolean isSuppressed(Map<T, Long> suppressions, T key) {
        if (key == null) {
            return false;
        }

        Long suppressUntil = suppressions.get(key);
        if (suppressUntil == null) {
            return false;
        }

        if (Util.getMillis() > suppressUntil) {
            suppressions.remove(key);
            return false;
        }
        return true;
    }
}
