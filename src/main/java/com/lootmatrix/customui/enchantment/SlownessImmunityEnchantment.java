package com.lootmatrix.customui.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

public class SlownessImmunityEnchantment extends Enchantment {

    public SlownessImmunityEnchantment() {
        super(Rarity.RARE, EnchantmentCategory.ARMOR_LEGS,
                new EquipmentSlot[]{EquipmentSlot.LEGS});
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public int getMinCost(int level) {
        return 20;
    }

    @Override
    public int getMaxCost(int level) {
        return 50;
    }
}
