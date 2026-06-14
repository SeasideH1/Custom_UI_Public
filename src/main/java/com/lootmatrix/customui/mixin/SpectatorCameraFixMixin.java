package com.lootmatrix.customui.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to fix spectator camera issues:
 * 1. Auto-exit spectate when the spectated player dies or becomes spectator
 *    (prevents camera shake/jitter from dead entity)
 * 2. Prevent non-OP players from pressing sneak to exit spectate mode
 *
 * In vanilla, LocalPlayer.aiStep() checks:
 *   if (this.minecraft.getCameraEntity() != this && this.input.shiftKeyDown) {
 *       this.minecraft.setCameraEntity(this);
 *   }
 * We redirect the shiftKeyDown check to also require OP permissions.
 */
@Mixin(LocalPlayer.class)
public abstract class SpectatorCameraFixMixin {

    /**
     * Check each tick if we're spectating a dead or spectator entity,
     * and if so, auto-exit spectate.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void customui$checkSpectateTarget(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Minecraft mc = Minecraft.getInstance();

        if (!self.isSpectator() || mc.gameMode == null) return;
        if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) return;

        Entity camera = mc.getCameraEntity();
        if (camera == null || camera == self) return;

        boolean shouldExitSpectate = false;

        // Check if spectated entity is dead
        if (camera instanceof LivingEntity living) {
            if (living.isDeadOrDying()) {
                shouldExitSpectate = true;
            }
        }

        // Check if spectated player became spectator
        if (camera instanceof net.minecraft.world.entity.player.Player targetPlayer) {
            if (targetPlayer.isSpectator()) {
                shouldExitSpectate = true;
            }
        }

        // Check if spectated entity was removed
        if (camera.isRemoved()) {
            shouldExitSpectate = true;
        }

        if (shouldExitSpectate) {
            // Reset camera to self (exit spectate)
            mc.setCameraEntity(self);
        }
    }

    /**
     * Redirect the shiftKeyDown check in aiStep() to prevent non-OP players from
     * manually exiting spectate mode via sneak key.
     *
     * The vanilla code in aiStep():
     *   if (this.minecraft.getCameraEntity() != this) {
     *       if (this.input.shiftKeyDown) {
     *           this.minecraft.setCameraEntity(this);
     *       }
     *   }
     *
     * We redirect input.shiftKeyDown to return false for non-OP spectators,
     * preventing them from using sneak to exit spectate.
     */
    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/player/Input;shiftKeyDown:Z",
                    ordinal = 0
            )
    )
    private boolean customui$blockSneakExitForNonOp(net.minecraft.client.player.Input input) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Minecraft mc = Minecraft.getInstance();

        // Only intercept in spectator mode when spectating another entity
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            Entity camera = mc.getCameraEntity();
            if (camera != null && camera != self) {
                // Non-OP players cannot exit spectate via sneak
                if (!self.hasPermissions(1)) {
                    return false;
                }
            }
        }

        return input.shiftKeyDown;
    }
}
