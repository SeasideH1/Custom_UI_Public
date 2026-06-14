package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.server.RespawnSpectatorState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to fix respawn + spectator mode flash.
 *
 * Vanilla respawn restores the new ServerPlayer with the previous game mode,
 * emits respawn/player-info packets, and only then changes the player to
 * spectator. That creates a visible flash for immediate spectator respawns.
 *
 * The actual packet-safe mode rewrite now happens in ServerPlayer.restoreFrom;
 * this mixin only manages the per-respawn state and finalizes the returned
 * player instance after the respawn call completes.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRespawnMixin {

    /**
     * Evaluate whether this respawn should initialize directly in spectator mode.
     */
    @Inject(method = "respawn", at = @At("HEAD"))
    private void customui$onRespawnHead(ServerPlayer oldPlayer, boolean wonGame,
                                         CallbackInfoReturnable<ServerPlayer> cir) {
        RespawnSpectatorState.beginRespawn(oldPlayer, wonGame);
    }

    @Surrogate
    private void customui$onRespawnHead(ServerPlayer oldPlayer, boolean wonGame,
                                        @Coerce Object respawnReason,
                                        CallbackInfoReturnable<ServerPlayer> cir) {
        RespawnSpectatorState.beginRespawn(oldPlayer, wonGame);
    }

    /**
     * After respawn completes, set the actual gameMode on the new player and clean up.
     */
    @Inject(method = "respawn", at = @At("RETURN"))
    private void customui$onRespawnReturn(ServerPlayer oldPlayer, boolean wonGame,
                                           CallbackInfoReturnable<ServerPlayer> cir) {
        try {
            if (!RespawnSpectatorState.shouldForceSpectator()) {
                return;
            }

            ServerPlayer newPlayer = cir.getReturnValue();
            if (newPlayer != null) {
                newPlayer.setGameMode(GameType.SPECTATOR);
            }
        } finally {
            RespawnSpectatorState.clearRespawnFlag();
        }
    }

    @Surrogate
    private void customui$onRespawnReturn(ServerPlayer oldPlayer, boolean wonGame,
                                          @Coerce Object respawnReason,
                                          CallbackInfoReturnable<ServerPlayer> cir) {
        try {
            if (!RespawnSpectatorState.shouldForceSpectator()) {
                return;
            }

            ServerPlayer newPlayer = cir.getReturnValue();
            if (newPlayer != null) {
                newPlayer.setGameMode(GameType.SPECTATOR);
            }
        } finally {
            RespawnSpectatorState.clearRespawnFlag();
        }
    }
}
