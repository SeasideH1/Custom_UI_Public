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
 * Mixin into Entity.getTeamColor() to return configured color for TeamGlow players.
 * getTeamColor is a Forge-added method, so we use remap=false.
 */
@Mixin(Entity.class)
public abstract class EntityTeamColorMixin {

    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true, remap = false)
    private void customui$getTeamColor(CallbackInfoReturnable<Integer> cir) {
        // Skip if team glow is disabled in config
        if (!TeamIndicatorConfig.INSTANCE.enabled.get()) return;
        
        if (BackgroundGuard.isInCooldown()) return;

        Entity self = (Entity) (Object) this;
        if (self instanceof Player player) {
            if (TeamGlowRenderer.hasTeamGlow(player)) {
                cir.setReturnValue(TeamGlowRenderer.getGlowColor());
            }
        }
    }
}


