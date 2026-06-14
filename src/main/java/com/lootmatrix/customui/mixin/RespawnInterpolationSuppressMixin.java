package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.ImmediateRespawnTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class RespawnInterpolationSuppressMixin {

    @Inject(method = "lerpTo", at = @At("HEAD"), cancellable = true)
    private void customui$suppressRespawnLerp(double x, double y, double z, float yRot, float xRot,
                                              int steps, boolean teleport, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player player)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        boolean localPlayer = self == minecraft.player;
        boolean suppress = localPlayer
                ? ImmediateRespawnTracker.shouldSuppressLocalInterpolation()
                : ImmediateRespawnTracker.shouldSuppressInterpolation(self.getId(), player.getUUID());
        if (!suppress) {
            return;
        }

        self.moveTo(x, y, z, yRot, xRot);
        if (self instanceof LivingEntity living) {
            living.setYBodyRot(yRot);
            living.setYHeadRot(yRot);
        }
        ci.cancel();
    }

    @Inject(method = "lerpHeadTo", at = @At("HEAD"), cancellable = true)
    private void customui$suppressRespawnHeadLerp(float yHeadRot, int steps, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof LivingEntity living) || !(self instanceof Player player)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        boolean localPlayer = self == minecraft.player;
        boolean suppress = localPlayer
                ? ImmediateRespawnTracker.shouldSuppressLocalInterpolation()
                : ImmediateRespawnTracker.shouldSuppressInterpolation(self.getId(), player.getUUID());
        if (!suppress) {
            return;
        }

        living.setYHeadRot(yHeadRot);
        living.setYBodyRot(yHeadRot);
        ci.cancel();
    }
}
