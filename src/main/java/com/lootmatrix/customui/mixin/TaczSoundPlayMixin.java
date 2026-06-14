package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.sound.TaczSoundDeduplicator;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.network.message.ServerMessageSound;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SoundPlayManager.class, remap = false)
public abstract class TaczSoundPlayMixin {

    private static final String CUSTOMUI_SHOOT = "shoot";
    private static final String CUSTOMUI_SHOOT_3P = "shoot_3p";
    private static final String CUSTOMUI_SILENCE = "silence";
    private static final String CUSTOMUI_SILENCE_3P = "silence_3p";

    @Inject(method = "playFleshHitSound", at = @At("HEAD"), cancellable = true)
    private static void customui$deduplicateFleshHitSound(LivingEntity entity, GunDisplayInstance display, CallbackInfo ci) {
        if (entity != null && TaczSoundDeduplicator.shouldCancelHitSound(entity.getId(), false, System.currentTimeMillis())) {
            ci.cancel();
        }
    }

    @Inject(method = "playHeadHitSound", at = @At("HEAD"), cancellable = true)
    private static void customui$deduplicateHeadHitSound(LivingEntity entity, GunDisplayInstance display, CallbackInfo ci) {
        if (entity != null && TaczSoundDeduplicator.shouldCancelHitSound(entity.getId(), true, System.currentTimeMillis())) {
            ci.cancel();
        }
    }

    @Inject(method = "playShootSound", at = @At("HEAD"), cancellable = true)
    private static void customui$deduplicateShootSound(LivingEntity entity, GunDisplayInstance display, GunData gunData, CallbackInfo ci) {
        customui$handleDirectGunSound(entity, CUSTOMUI_SHOOT, ci);
    }

    @Inject(method = "playSilenceSound", at = @At("HEAD"), cancellable = true)
    private static void customui$deduplicateSilenceSound(LivingEntity entity, GunDisplayInstance display, GunData gunData, CallbackInfo ci) {
        customui$handleDirectGunSound(entity, CUSTOMUI_SILENCE, ci);
    }

    @Inject(method = "playMessageSound", at = @At("HEAD"), cancellable = true)
    private static void customui$deduplicateThirdPersonEcho(ServerMessageSound message, CallbackInfo ci) {
        if (message == null) {
            return;
        }

        String soundKind = customui$canonicalShootKind(message.getSoundName());
        if (soundKind != null && TaczSoundDeduplicator.shouldCancelEchoedThirdPersonShoot(
                message.getEntityId(), soundKind, System.currentTimeMillis(),
                customui$isLocalPlayer(message.getEntityId()))) {
            ci.cancel();
        }
    }

    private static void customui$handleDirectGunSound(LivingEntity entity, String soundKind, CallbackInfo ci) {
        if (entity == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int entityId = entity.getId();
        boolean localShooter = customui$isLocalPlayer(entityId);
        if (TaczSoundDeduplicator.shouldCancelDirectShootSound(entityId, soundKind, now, localShooter)) {
            ci.cancel();
            return;
        }
        TaczSoundDeduplicator.markLocalShootSound(entityId, soundKind, now);
    }

    private static boolean customui$isLocalPlayer(int entityId) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getId() == entityId;
    }

    private static String customui$canonicalShootKind(String soundName) {
        if (CUSTOMUI_SHOOT_3P.equals(soundName)) {
            return CUSTOMUI_SHOOT;
        }
        if (CUSTOMUI_SILENCE_3P.equals(soundName)) {
            return CUSTOMUI_SILENCE;
        }
        return null;
    }
}
