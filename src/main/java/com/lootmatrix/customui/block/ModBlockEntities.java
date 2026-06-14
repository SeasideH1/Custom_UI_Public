package com.lootmatrix.customui.block;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.registry.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for block entity types.
 */
public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Main.MODID);

    public static final RegistryObject<BlockEntityType<VoidBarrierBlockEntity>> VOID_BARRIER =
            BLOCK_ENTITIES.register("void_barrier",
                    () -> BlockEntityType.Builder.of(
                            VoidBarrierBlockEntity::new,
                            ModBlocks.VOID_BARRIER.get()
                    ).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}

