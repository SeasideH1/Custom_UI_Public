package com.lootmatrix.customui.registry;

import com.lootmatrix.customui.Main;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ModEnchantedBooks {

    private static final String STORED_ENCHANTMENTS_TAG = "StoredEnchantments";
    private static final ResourceLocation SLOWNESS_IMMUNITY_ID =
            new ResourceLocation(Main.MODID, "slowness_immunity");

    private ModEnchantedBooks() {
    }

    public static ItemStack createSlownessImmunityBook() {
        return createEnchantedBook(SLOWNESS_IMMUNITY_ID, 1);
    }

    static ItemStack createEnchantedBook(ResourceLocation enchantmentId, int level) {
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
        ListTag enchantments = new ListTag();
        enchantments.add(createStoredEnchantmentTag(enchantmentId, level));
        stack.getOrCreateTag().put(STORED_ENCHANTMENTS_TAG, enchantments);
        return stack;
    }

    static CompoundTag createStoredEnchantmentTag(ResourceLocation enchantmentId, int level) {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", enchantmentId.toString());
        enchantment.putShort("lvl", (short) level);
        return enchantment;
    }
}
