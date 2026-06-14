package com.lootmatrix.customui.registry;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.enchantment.SlownessImmunityEnchantment;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEnchantments {

    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, Main.MODID);

    public static final RegistryObject<Enchantment> SLOWNESS_IMMUNITY =
            ENCHANTMENTS.register("slowness_immunity", SlownessImmunityEnchantment::new);

    public static void register(IEventBus eventBus) {
        ENCHANTMENTS.register(eventBus);
    }
}
