package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.ImmediateRespawnTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mixin for tracking immediate respawn events.
 *
 * Hooks into packet handlers to detect:
 * - Other player deaths (entity event byte 3)
 * - Other player entity additions (possible immediate respawn)
 * - Local player respawn in immediate-respawn mode
 * - Gamemode change packets (resolves local wait state)
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientRespawnMixin {

    /**
     * Track other player deaths via entity event byte 3.
     */
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void customui$trackPlayerDeath(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (packet.getEventId() != 3) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Entity entity = packet.getEntity(mc.level);
        if (entity instanceof Player player && entity != mc.player) {
            ImmediateRespawnTracker.onPlayerDeath(player.getUUID());
        }
    }

    /**
     * Track when an entity is added. If it matches a recently-dead player
     * and doImmediateRespawn is enabled, the tracker begins render suppression.
     */
    @Inject(method = "handleAddEntity", at = @At("RETURN"))
    private void customui$trackPlayerSpawn(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        ImmediateRespawnTracker.onPlayerEntityAdded(packet.getId(), packet.getUUID());
    }

    @Inject(method = "handleAddPlayer", at = @At("RETURN"))
    private void customui$trackPlayerRespawn(ClientboundAddPlayerPacket packet, CallbackInfo ci) {
        ImmediateRespawnTracker.onPlayerEntityAdded(packet.getEntityId(), packet.getPlayerId());
    }

    /**
     * Track local player immediate respawn.
     * Only activates when doImmediateRespawn is on and the player is actually dead
     * (not dimension hopping).
     */
    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void customui$trackLocalRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (mc.level.getGameRules().getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN)
                && mc.player.isDeadOrDying()) {
            ImmediateRespawnTracker.onLocalImmediateRespawn();
        }
    }

    /**
     * Track gamemode change events for the local player.
     * Resolves the waiting state after immediate respawn.
     */
    @Inject(method = "handleGameEvent", at = @At("HEAD"))
    private void customui$trackGameModeChange(ClientboundGameEventPacket packet, CallbackInfo ci) {
        if (packet.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            ImmediateRespawnTracker.onLocalGameModeReceived();
        }
    }
}
