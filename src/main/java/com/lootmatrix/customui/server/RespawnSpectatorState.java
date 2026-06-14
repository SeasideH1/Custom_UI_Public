package com.lootmatrix.customui.server;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks whether the next respawn should initialize directly into spectator mode.
 */
public final class RespawnSpectatorState {

    private static final Set<UUID> PENDING_SPECTATORS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final ThreadLocal<Boolean> FORCE_SPECTATOR = ThreadLocal.withInitial(() -> false);

    private RespawnSpectatorState() {
    }

    public static void registerPendingSpectator(UUID playerUUID) {
        if (playerUUID != null) {
            PENDING_SPECTATORS.add(playerUUID);
        }
    }

    public static void clearPendingSpectator(UUID playerUUID) {
        if (playerUUID != null) {
            PENDING_SPECTATORS.remove(playerUUID);
        }
    }

    /**
     * Evaluate and store whether the current respawn should force spectator mode.
     */
    public static void beginRespawn(ServerPlayer oldPlayer, boolean wonGame) {
        boolean shouldForce = false;

        if (oldPlayer != null) {
            shouldForce = PENDING_SPECTATORS.remove(oldPlayer.getUUID());
        }

        FORCE_SPECTATOR.set(shouldForce);
    }

    public static boolean shouldForceSpectator() {
        return Boolean.TRUE.equals(FORCE_SPECTATOR.get());
    }

    public static void clearRespawnFlag() {
        FORCE_SPECTATOR.remove();
    }
}
