package com.lootmatrix.customui.entity;

import com.lootmatrix.customui.Main;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for custom entities.
 */
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Main.MODID);

    public static final RegistryObject<EntityType<DeathMarkerEntity>> DEATH_MARKER =
            ENTITIES.register("death_marker", () ->
                    EntityType.Builder.<DeathMarkerEntity>of(DeathMarkerEntity::new, MobCategory.MISC)
                            .sized(0.1f, 0.1f)
                            .clientTrackingRange(64)
                            .updateInterval(20)
                            .noSummon()
                            .fireImmune()
                            .build("death_marker")
            );

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}

