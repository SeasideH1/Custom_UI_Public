package com.lootmatrix.customui.registry;

import com.lootmatrix.customui.Main;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModEnchantedBooksTest {

    @Test
    void createsSlownessImmunityStoredEnchantmentTag() {
        CompoundTag enchantment = ModEnchantedBooks.createStoredEnchantmentTag(
                new ResourceLocation(Main.MODID, "slowness_immunity"),
                1
        );

        assertEquals(new ResourceLocation(Main.MODID, "slowness_immunity").toString(), enchantment.getString("id"));
        assertEquals(1, enchantment.getShort("lvl"));
    }
}
