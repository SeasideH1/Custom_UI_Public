package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.server.RespawnSpectatorState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewrites the restored player's in-memory game mode before PlayerList.respawn
 * emits any respawn or player-info packets.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRestoreFromMixin {

    @Inject(method = "restoreFrom", at = @At("RETURN"))
    private void customui$applyForcedSpectatorState(ServerPlayer oldPlayer, boolean keepEverything, CallbackInfo ci) {
        if (!RespawnSpectatorState.shouldForceSpectator()) {
            return;
        }

        ServerPlayer self = (ServerPlayer) (Object) this;
        ServerPlayerGameMode gameMode = self.gameMode;
        GameType currentMode = gameMode.getGameModeForPlayer();

        if (currentMode != GameType.SPECTATOR) {
            ServerPlayerGameModeAccessor accessor = (ServerPlayerGameModeAccessor) gameMode;
            accessor.customui$setPreviousGameModeForPlayerField(currentMode);
            accessor.customui$setGameModeForPlayerField(GameType.SPECTATOR);
        }

        GameType.SPECTATOR.updatePlayerAbilities(self.getAbilities());
    }
}
