package com.lootmatrix.customui.server;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.registry.ModEnchantments;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SlownessImmunityHandler {

    @SubscribeEvent
    public static void onEffectApplicable(MobEffectEvent.Applicable event) {
        if (event.getEffectInstance().getEffect() == MobEffects.MOVEMENT_SLOWDOWN) {
            LivingEntity entity = event.getEntity();
            if (entity.level().isClientSide()) return;
            if (hasSlownessImmunity(entity)) {
                event.setResult(Event.Result.DENY);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (!hasSlownessImmunity(entity)) return;
        if (!entity.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) return;

        // Keep the enchantment authoritative even if another mod reapplies slowness
        // or the player equips the leggings after the effect was already present.
        entity.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
    }

    static boolean hasSlownessImmunity(LivingEntity entity) {
        if (!ModEnchantments.SLOWNESS_IMMUNITY.isPresent()) return false;
        return EnchantmentHelper.getEnchantmentLevel(ModEnchantments.SLOWNESS_IMMUNITY.get(), entity) > 0;
    }
}
