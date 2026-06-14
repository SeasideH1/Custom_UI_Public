package com.lootmatrix.customui.registry;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.block.FastLadderBlock;
import com.lootmatrix.customui.block.VerticalSlabBlock;
import com.lootmatrix.customui.block.VoidBarrierBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Consolidated registry for ALL mod blocks, items, and creative tabs.
 * All DeferredRegisters for blocks and items live here to avoid duplicate-register conflicts.
 */
public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Main.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Main.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Main.MODID);



    // Creative tab for vertical slabs — declared after VERTICAL_SLAB_ITEMS is populated
    // (moved to end of class to avoid forward reference errors)

    // List of all vertical slab items for creative tab registration
    public static final List<RegistryObject<Item>> VERTICAL_SLAB_ITEMS = new ArrayList<>();

    // ==================== Utility Blocks (Void Barrier & Fast Ladder) ====================
    public static final RegistryObject<Block> VOID_BARRIER = BLOCKS.register("void_barrier",
            () -> new VoidBarrierBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.NONE)
                    .strength(-1.0f, 3600000.0f)  // Unbreakable like barrier
                    .noLootTable()
                    // Removed noOcclusion() - we WANT it to occlude adjacent faces
                    .isValidSpawn((state, level, pos, type) -> false)
                    .noParticlesOnBreak()
                    .pushReaction(PushReaction.BLOCK)));
    public static final RegistryObject<Item> VOID_BARRIER_ITEM = ITEMS.register("void_barrier",
            () -> new BlockItem(VOID_BARRIER.get(), new Item.Properties()));

    public static final RegistryObject<Block> FAST_LADDER = BLOCKS.register("fast_ladder",
            () -> new FastLadderBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(0.4f)
                    .sound(SoundType.LADDER)
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Item> FAST_LADDER_ITEM = ITEMS.register("fast_ladder",
            () -> new BlockItem(FAST_LADDER.get(), new Item.Properties()));

    // ==================== Utility Blocks Creative Tab ====================
    public static final RegistryObject<CreativeModeTab> UTILITY_BLOCKS_TAB = CREATIVE_MODE_TABS.register("utility_blocks",
            () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.BUILDING_BLOCKS)
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.customui.utility_blocks"))
                    .icon(() -> Items.BARRIER.getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(VOID_BARRIER_ITEM.get());
                        output.accept(FAST_LADDER_ITEM.get());
                    }).build());

    // ==================== Wood Vertical Slabs ====================
    public static final RegistryObject<Block> OAK_VERTICAL_SLAB = registerVerticalSlab("oak_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.OAK_SLAB));
    public static final RegistryObject<Block> SPRUCE_VERTICAL_SLAB = registerVerticalSlab("spruce_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.SPRUCE_SLAB));
    public static final RegistryObject<Block> BIRCH_VERTICAL_SLAB = registerVerticalSlab("birch_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.BIRCH_SLAB));
    public static final RegistryObject<Block> JUNGLE_VERTICAL_SLAB = registerVerticalSlab("jungle_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.JUNGLE_SLAB));
    public static final RegistryObject<Block> ACACIA_VERTICAL_SLAB = registerVerticalSlab("acacia_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.ACACIA_SLAB));
    public static final RegistryObject<Block> DARK_OAK_VERTICAL_SLAB = registerVerticalSlab("dark_oak_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.DARK_OAK_SLAB));
    public static final RegistryObject<Block> MANGROVE_VERTICAL_SLAB = registerVerticalSlab("mangrove_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.MANGROVE_SLAB));
    public static final RegistryObject<Block> CHERRY_VERTICAL_SLAB = registerVerticalSlab("cherry_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.CHERRY_SLAB));
    public static final RegistryObject<Block> BAMBOO_VERTICAL_SLAB = registerVerticalSlab("bamboo_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.BAMBOO_SLAB));
    public static final RegistryObject<Block> BAMBOO_MOSAIC_VERTICAL_SLAB = registerVerticalSlab("bamboo_mosaic_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.BAMBOO_MOSAIC_SLAB));
    public static final RegistryObject<Block> CRIMSON_VERTICAL_SLAB = registerVerticalSlab("crimson_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.CRIMSON_SLAB));
    public static final RegistryObject<Block> WARPED_VERTICAL_SLAB = registerVerticalSlab("warped_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.WARPED_SLAB));

    // ==================== Stone Vertical Slabs ====================
    public static final RegistryObject<Block> COBBLESTONE_VERTICAL_SLAB = registerVerticalSlab("cobblestone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.COBBLESTONE_SLAB));
    public static final RegistryObject<Block> MOSSY_COBBLESTONE_VERTICAL_SLAB = registerVerticalSlab("mossy_cobblestone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.MOSSY_COBBLESTONE_SLAB));
    public static final RegistryObject<Block> SMOOTH_STONE_VERTICAL_SLAB = registerVerticalSlab("smooth_stone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.SMOOTH_STONE_SLAB));
    public static final RegistryObject<Block> STONE_BRICK_VERTICAL_SLAB = registerVerticalSlab("stone_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.STONE_BRICK_SLAB));
    public static final RegistryObject<Block> MOSSY_STONE_BRICK_VERTICAL_SLAB = registerVerticalSlab("mossy_stone_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.MOSSY_STONE_BRICK_SLAB));
    public static final RegistryObject<Block> ANDESITE_VERTICAL_SLAB = registerVerticalSlab("andesite_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.ANDESITE_SLAB));
    public static final RegistryObject<Block> POLISHED_ANDESITE_VERTICAL_SLAB = registerVerticalSlab("polished_andesite_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.POLISHED_ANDESITE_SLAB));
    public static final RegistryObject<Block> DIORITE_VERTICAL_SLAB = registerVerticalSlab("diorite_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.DIORITE_SLAB));
    public static final RegistryObject<Block> POLISHED_DIORITE_VERTICAL_SLAB = registerVerticalSlab("polished_diorite_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.POLISHED_DIORITE_SLAB));
    public static final RegistryObject<Block> GRANITE_VERTICAL_SLAB = registerVerticalSlab("granite_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.GRANITE_SLAB));
    public static final RegistryObject<Block> POLISHED_GRANITE_VERTICAL_SLAB = registerVerticalSlab("polished_granite_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.POLISHED_GRANITE_SLAB));

    // ==================== Sandstone Vertical Slabs ====================
    public static final RegistryObject<Block> SANDSTONE_VERTICAL_SLAB = registerVerticalSlab("sandstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.SANDSTONE_SLAB));
    public static final RegistryObject<Block> SMOOTH_SANDSTONE_VERTICAL_SLAB = registerVerticalSlab("smooth_sandstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.SMOOTH_SANDSTONE_SLAB));
    public static final RegistryObject<Block> CUT_SANDSTONE_VERTICAL_SLAB = registerVerticalSlab("cut_sandstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.CUT_SANDSTONE_SLAB));
    public static final RegistryObject<Block> RED_SANDSTONE_VERTICAL_SLAB = registerVerticalSlab("red_sandstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.RED_SANDSTONE_SLAB));
    public static final RegistryObject<Block> SMOOTH_RED_SANDSTONE_VERTICAL_SLAB = registerVerticalSlab("smooth_red_sandstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.SMOOTH_RED_SANDSTONE_SLAB));
    public static final RegistryObject<Block> CUT_RED_SANDSTONE_VERTICAL_SLAB = registerVerticalSlab("cut_red_sandstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.CUT_RED_SANDSTONE_SLAB));

    // ==================== Brick & Nether Vertical Slabs ====================
    public static final RegistryObject<Block> BRICK_VERTICAL_SLAB = registerVerticalSlab("brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.BRICK_SLAB));
    public static final RegistryObject<Block> NETHER_BRICK_VERTICAL_SLAB = registerVerticalSlab("nether_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.NETHER_BRICK_SLAB));
    public static final RegistryObject<Block> RED_NETHER_BRICK_VERTICAL_SLAB = registerVerticalSlab("red_nether_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.RED_NETHER_BRICK_SLAB));

    // ==================== Prismarine Vertical Slabs ====================
    public static final RegistryObject<Block> PRISMARINE_VERTICAL_SLAB = registerVerticalSlab("prismarine_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.PRISMARINE_SLAB));
    public static final RegistryObject<Block> PRISMARINE_BRICK_VERTICAL_SLAB = registerVerticalSlab("prismarine_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.PRISMARINE_BRICK_SLAB));
    public static final RegistryObject<Block> DARK_PRISMARINE_VERTICAL_SLAB = registerVerticalSlab("dark_prismarine_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.DARK_PRISMARINE_SLAB));

    // ==================== Quartz & Purpur Vertical Slabs ====================
    public static final RegistryObject<Block> QUARTZ_VERTICAL_SLAB = registerVerticalSlab("quartz_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.QUARTZ_SLAB));
    public static final RegistryObject<Block> SMOOTH_QUARTZ_VERTICAL_SLAB = registerVerticalSlab("smooth_quartz_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.SMOOTH_QUARTZ_SLAB));
    public static final RegistryObject<Block> PURPUR_VERTICAL_SLAB = registerVerticalSlab("purpur_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.PURPUR_SLAB));
    public static final RegistryObject<Block> END_STONE_BRICK_VERTICAL_SLAB = registerVerticalSlab("end_stone_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.END_STONE_BRICK_SLAB));

    // ==================== Blackstone Vertical Slabs ====================
    public static final RegistryObject<Block> BLACKSTONE_VERTICAL_SLAB = registerVerticalSlab("blackstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.BLACKSTONE_SLAB));
    public static final RegistryObject<Block> POLISHED_BLACKSTONE_VERTICAL_SLAB = registerVerticalSlab("polished_blackstone_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.POLISHED_BLACKSTONE_SLAB));
    public static final RegistryObject<Block> POLISHED_BLACKSTONE_BRICK_VERTICAL_SLAB = registerVerticalSlab("polished_blackstone_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB));

    // ==================== Deepslate Vertical Slabs ====================
    public static final RegistryObject<Block> COBBLED_DEEPSLATE_VERTICAL_SLAB = registerVerticalSlab("cobbled_deepslate_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.COBBLED_DEEPSLATE_SLAB));
    public static final RegistryObject<Block> POLISHED_DEEPSLATE_VERTICAL_SLAB = registerVerticalSlab("polished_deepslate_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.POLISHED_DEEPSLATE_SLAB));
    public static final RegistryObject<Block> DEEPSLATE_BRICK_VERTICAL_SLAB = registerVerticalSlab("deepslate_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_BRICK_SLAB));
    public static final RegistryObject<Block> DEEPSLATE_TILE_VERTICAL_SLAB = registerVerticalSlab("deepslate_tile_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_TILE_SLAB));

    // ==================== Mud Brick Vertical Slab ====================
    public static final RegistryObject<Block> MUD_BRICK_VERTICAL_SLAB = registerVerticalSlab("mud_brick_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.MUD_BRICK_SLAB));

    // ==================== Copper Vertical Slabs ====================
    public static final RegistryObject<Block> CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.CUT_COPPER_SLAB));
    public static final RegistryObject<Block> EXPOSED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("exposed_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.EXPOSED_CUT_COPPER_SLAB));
    public static final RegistryObject<Block> WEATHERED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("weathered_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.WEATHERED_CUT_COPPER_SLAB));
    public static final RegistryObject<Block> OXIDIZED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("oxidized_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.OXIDIZED_CUT_COPPER_SLAB));
    public static final RegistryObject<Block> WAXED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("waxed_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.WAXED_CUT_COPPER_SLAB));
    public static final RegistryObject<Block> WAXED_EXPOSED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("waxed_exposed_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.WAXED_EXPOSED_CUT_COPPER_SLAB));
    public static final RegistryObject<Block> WAXED_WEATHERED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("waxed_weathered_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.WAXED_WEATHERED_CUT_COPPER_SLAB));
    public static final RegistryObject<Block> WAXED_OXIDIZED_CUT_COPPER_VERTICAL_SLAB = registerVerticalSlab("waxed_oxidized_cut_copper_vertical_slab",
            () -> BlockBehaviour.Properties.copy(Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB));

    // ==================== Vertical Slabs Creative Tab ====================
    // Declared here (after all registerVerticalSlab calls) to avoid forward-reference errors
    public static final RegistryObject<CreativeModeTab> VERTICAL_SLAB_TAB = CREATIVE_MODE_TABS.register("vertical_slabs",
            () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.BUILDING_BLOCKS)
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.customui.vertical_slabs"))
                    .icon(() -> {
                        try {
                            if (!VERTICAL_SLAB_ITEMS.isEmpty() && VERTICAL_SLAB_ITEMS.get(0).isPresent()) {
                                Item item = VERTICAL_SLAB_ITEMS.get(0).get();
                                if (item != null) return item.getDefaultInstance();
                            }
                        } catch (Exception ignored) {}
                        return net.minecraft.world.item.Items.BARRIER.getDefaultInstance();
                    })
                    .displayItems((parameters, output) -> {
                        for (RegistryObject<Item> slabItem : VERTICAL_SLAB_ITEMS) {
                            try {
                                if (slabItem.isPresent()) {
                                    Item item = slabItem.get();
                                    if (item != null) output.accept(item);
                                }
                            } catch (Exception ignored) {}
                        }
                    }).build());

    /**
     * Helper to register a vertical slab block and its corresponding BlockItem.
     */
    private static RegistryObject<Block> registerVerticalSlab(String name, Supplier<BlockBehaviour.Properties> propertiesSupplier) {
        RegistryObject<Block> block = BLOCKS.register(name, () -> new VerticalSlabBlock(propertiesSupplier.get()));
        RegistryObject<Item> item = ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        VERTICAL_SLAB_ITEMS.add(item);
        return block;
    }

    /**
     * Register both deferred registers on the mod event bus.
     */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}


