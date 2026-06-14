package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.SuperbwarfareExplosionShakeLock;
import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ForgeConfigSpec.ConfigValue.class, remap = false)
public abstract class SuperbwarfareConfigValueMixin {

    @ModifyVariable(method = "set", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Object customui$lockExplosionScreenShakeOnSet(Object requestedValue) {
        ForgeConfigSpec.ConfigValue<?> configValue = (ForgeConfigSpec.ConfigValue<?>) (Object) this;
        return SuperbwarfareExplosionShakeLock.coerceSet(configValue, requestedValue);
    }
}
