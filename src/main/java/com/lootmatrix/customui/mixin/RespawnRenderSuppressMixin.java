package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.ImmediateRespawnTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses rendering of other players for a short window (~250ms / ~5 ticks)
 * after they immediately respawn, hiding the brief gamemode flash
 * (e.g. ADVENTURE → SPECTATOR transition).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class RespawnRenderSuppressMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void customui$suppressRecentlyRespawned(Entity entity, Frustum frustum,
                                                     double camX, double camY, double camZ,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Player player)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (entity == mc.player) {
            if (ImmediateRespawnTracker.shouldSuppressLocalPlayer()) {
                cir.setReturnValue(false);
            }
            return;
        }

        if (ImmediateRespawnTracker.shouldSuppressRendering(player.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
