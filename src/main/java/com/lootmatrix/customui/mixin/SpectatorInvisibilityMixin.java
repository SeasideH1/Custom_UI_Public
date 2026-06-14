package com.lootmatrix.customui.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin to prevent rendering invisible enemy team players when the local player
 * is in Spectator mode and has friendly Adventure mode players within 5m.
 *
 * Logic:
 * - Local player must be in SPECTATOR mode
 * - There must be at least one same-team ADVENTURE mode player within 5m
 * - Target player must be on a different team
 * - Target player must have invisibility effect (even semi-transparent)
 * - If all conditions met: don't render the target
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class SpectatorInvisibilityMixin {

    // ── Per-tick cache for the nearby-adventure-player query ──
    // Avoids running getEntitiesOfClass() for every entity every frame.
    @Unique private static boolean customui$cachedHasNearbyAdventure = false;
    @Unique private static int customui$cacheTickCount = -1;

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void customui$hideInvisibleEnemies(Entity entity, Frustum frustum, double camX, double camY, double camZ,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Player targetPlayer)) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer localPlayer = mc.player;
        if (localPlayer == null || mc.gameMode == null) return;

        // Only active in Spectator mode
        if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) return;

        // Target must have invisibility effect
        if (!targetPlayer.isInvisible()) return;

        // Don't hide self
        if (targetPlayer == localPlayer) return;

        // Check if target is on a different team (use robust name comparison)
        if (customui$isSameTeam(localPlayer, targetPlayer)) return;

        // Check for nearby same-team Adventure mode players within 5m (cached per tick)
        if (customui$hasNearbyFriendlyAdventureCached(localPlayer)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Cached wrapper: recomputes at most once per client tick.
     */
    @Unique
    private boolean customui$hasNearbyFriendlyAdventureCached(LocalPlayer localPlayer) {
        int currentTick = localPlayer.tickCount;
        if (currentTick != customui$cacheTickCount) {
            customui$cacheTickCount = currentTick;
            customui$cachedHasNearbyAdventure = customui$hasNearbyFriendlyAdventurePlayer(localPlayer);
        }
        return customui$cachedHasNearbyAdventure;
    }

    @Unique
    private boolean customui$hasNearbyFriendlyAdventurePlayer(LocalPlayer localPlayer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;

        // Use camera entity's position when spectating another entity via /spectate.
        // The local player's actual entity position may differ from the camera position,
        // which would cause the distance check to fail when the spectator's real body
        // drifts away from the adventure players.
        Entity cameraEntity = mc.getCameraEntity();
        Entity referenceEntity = (cameraEntity != null && cameraEntity != localPlayer)
                ? cameraEntity : localPlayer;

        double range = 5.0;
        AABB searchBox = referenceEntity.getBoundingBox().inflate(range);

        List<Player> nearbyPlayers = localPlayer.level().getEntitiesOfClass(
                Player.class, searchBox,
                p -> p != localPlayer && customui$isSameTeam(localPlayer, p)
        );

        for (Player p : nearbyPlayers) {
            // Include the spectated player too: when the camera is attached to an
            // adventure-mode teammate, excluding referenceEntity breaks the render skip logic.
            // Use PlayerInfo.getGameMode() for accurate client-side game mode detection.
            // This reads the GameType sent by the server in the player info update packet,
            // which is always reliable regardless of server implementation.
            PlayerInfo playerInfo = mc.getConnection().getPlayerInfo(p.getUUID());
            if (playerInfo != null && playerInfo.getGameMode() == GameType.ADVENTURE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Robust same-team check that falls back to direct team name comparison.
     * Works correctly on Mohist/Catserver hybrid servers where isAlliedTo()
     * may return incorrect results due to team data desync.
     */
    @Unique
    private static boolean customui$isSameTeam(Player a, Player b) {
        // Fast path: vanilla check
        if (a.isAlliedTo(b)) return true;

        // Fallback: direct team name comparison
        Team teamA = a.getTeam();
        Team teamB = b.getTeam();
        if (teamA == null && teamB == null) return true;
        if (teamA == null || teamB == null) return false;
        return teamA.getName().equals(teamB.getName());
    }
}
