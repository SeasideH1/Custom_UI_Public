package com.lootmatrix.customui.registry;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.effect.TeamGlowEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for custom mob effects.
 */
public class ModEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Main.MODID);

    /**
     * Team Glow effect - makes same-team players glow green.
     * If the player also has vanilla Glowing effect, vanilla takes priority.
     */
    public static final RegistryObject<MobEffect> TEAM_GLOW = MOB_EFFECTS.register("team_glow",
            TeamGlowEffect::new);

    public static void register(IEventBus eventBus) {
        MOB_EFFECTS.register(eventBus);
    }
}

