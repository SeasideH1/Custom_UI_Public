package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.BackgroundGuard;
import com.lootmatrix.customui.client.TeamGlowRenderer;
import com.lootmatrix.customui.config.TeamIndicatorConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into Entity.isCurrentlyGlowing() to make TeamGlow players appear glowing.
 * Mixin remap handles Mojang→SRG name mapping automatically at build time.
 */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
    private void customui$isCurrentlyGlowing(CallbackInfoReturnable<Boolean> cir) {
        // Skip if team glow is disabled in config
        if (!TeamIndicatorConfig.INSTANCE.enabled.get()) return;

        // Skip during cooldown to prevent work during recovery
        if (BackgroundGuard.isInCooldown()) return;

        if (cir.getReturnValue()) return;

        Entity self = (Entity) (Object) this;
        if (self instanceof Player player) {
            if (TeamGlowRenderer.hasTeamGlow(player)) {
                cir.setReturnValue(true);
            }
        }
    }
}