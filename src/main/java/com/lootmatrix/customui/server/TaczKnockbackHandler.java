package com.lootmatrix.customui.server;

import com.lootmatrix.customui.Main;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;

/**
 * Server-side handler to cancel knockback and slowdown for TaCZ bullet hits
 * when the bullet has zero knockback or the victim has slowness immunity.
 *
 * TaCZ writes the effective knockback value to KnockBackModifier immediately
 * before calling hurt(). Reading the modifier in EntityHurtByGunEvent.Pre is
 * too early, so we check the modifier directly in LivingKnockBackEvent while
 * that value is still active for the current hit.
 *
 * Canceling the event is not enough on its own: LivingEntity.hurt() calls
 * markHurt() before knockback(), so the server would still push a stale
 * (near-zero) velocity to the victim's client via ClientboundSetEntityMotionPacket,
 * wiping sprint momentum on every hit. We therefore also clear hurtMarked
 * whenever we cancel the knockback.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TaczKnockbackHandler {

    private static boolean reflectionInitialized;
    private static Method fromLivingEntityMethod;
    private static Method getKnockBackStrengthMethod;

    /**
     * Cancel knockback before TaCZ rewrites the event strength.
     * Canceling the event also skips vanilla's horizontal velocity halving.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKnockBack(LivingKnockBackEvent event) {
        LivingEntity victim = event.getEntity();
        try {
            initReflection();
            if (fromLivingEntityMethod == null || getKnockBackStrengthMethod == null) {
                return;
            }
            Object modifier = fromLivingEntityMethod.invoke(null, victim);
            if (modifier == null) {
                return;
            }
            Object strength = getKnockBackStrengthMethod.invoke(modifier);
            if (strength instanceof Number number
                    && shouldCancelKnockback(number.doubleValue(), SlownessImmunityHandler.hasSlownessImmunity(victim))) {
                event.setCanceled(true);
                // hurt() already ran markHurt(); without this the server still sends
                // its stale velocity to the victim's client and zeroes their momentum.
                victim.hurtMarked = false;
            }
        } catch (Throwable ignored) {
            // TaCZ not present on this entity, let other handlers proceed.
        }
    }

    static boolean shouldCancelKnockback(double strength, boolean hasSlownessImmunity) {
        if (strength < 0.0D) {
            return false;
        }
        return strength == 0.0D || hasSlownessImmunity;
    }

    private static void initReflection() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;
        try {
            Class<?> modifierClass = Class.forName("com.tacz.guns.api.entity.KnockBackModifier");
            fromLivingEntityMethod = modifierClass.getMethod("fromLivingEntity", LivingEntity.class);
            getKnockBackStrengthMethod = modifierClass.getMethod("getKnockBackStrength");
        } catch (Throwable ignored) {
            fromLivingEntityMethod = null;
            getKnockBackStrengthMethod = null;
        }
    }
}
