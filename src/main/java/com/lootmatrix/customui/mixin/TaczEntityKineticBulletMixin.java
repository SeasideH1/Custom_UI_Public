package com.lootmatrix.customui.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to prevent igniting entities on the same team.
 * Uses @Redirect to intercept the setSecondsOnFire call and check team membership.
 * Supports both player-to-player and player-to-owned-entity team checks.
 */
@Mixin(value = EntityKineticBullet.class, remap = false)
public abstract class TaczEntityKineticBulletMixin {

    /**
     * Redirect the setSecondsOnFire call to check if the target is on the same team as the attacker.
     * If they are on the same team, don't ignite — and also clear any residual fire ticks
     * to prevent even a single tick of fire damage.
     */
    @Redirect(
            method = "onHitEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setSecondsOnFire(I)V", remap = true)
    )
    private void customui$redirectSetSecondsOnFire(Entity target, int seconds) {
        EntityKineticBullet self = (EntityKineticBullet) (Object) this;
        Entity owner = self.getOwner();

        if (owner instanceof Player ownerPlayer) {
            // Check direct player targets
            if (target instanceof Player targetPlayer) {
                if (customui$areAllied(ownerPlayer, targetPlayer)) {
                    // Safety: clear any residual fire ticks to prevent fire damage
                    target.clearFire();
                    return;
                }
            }
            // Check owned entities (tamed animals, etc.)
            if (target instanceof OwnableEntity ownable) {
                Entity entityOwner = ownable.getOwner();
                if (entityOwner instanceof Player entityOwnerPlayer) {
                    if (customui$areAllied(ownerPlayer, entityOwnerPlayer)) {
                        target.clearFire();
                        return;
                    }
                }
            }
            // Check entities with isAlliedTo
            if (target.isAlliedTo(ownerPlayer)) {
                target.clearFire();
                return;
            }
        }

        // Not same team, proceed with normal ignite
        target.setSecondsOnFire(seconds);
    }

    /**
     * Check if two players are allied (same team / same scoreboard relation).
     */
    @Unique
    private boolean customui$areAllied(Player player1, Player player2) {
        if (player1 == null || player2 == null) {
            return false;
        }
        if (player1 == player2) {
            return true;
        }
        return player1.isAlliedTo(player2);
    }
}
