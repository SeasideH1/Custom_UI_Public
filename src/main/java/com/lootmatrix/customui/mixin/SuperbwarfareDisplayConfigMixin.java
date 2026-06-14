package com.lootmatrix.customui.mixin;

import com.atsuishio.superbwarfare.config.client.DisplayConfig;
import com.lootmatrix.customui.client.SuperbwarfareExplosionShakeLock;
import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(value = DisplayConfig.class, remap = false)
public abstract class SuperbwarfareDisplayConfigMixin {

    @Redirect(
            method = "<clinit>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/common/ForgeConfigSpec$Builder;defineInRange(Ljava/lang/String;III)Lnet/minecraftforge/common/ForgeConfigSpec$IntValue;"
            ),
            slice = @Slice(
                    from = @At(value = "CONSTANT", args = "stringValue=The strength of screen shaking while exploding"),
                    to = @At(value = "CONSTANT", args = "stringValue=The strength of screen shaking when shocked")
            )
    )
    private static ForgeConfigSpec.IntValue customui$lockExplosionScreenShakeSpec(ForgeConfigSpec.Builder builder,
                                                                                   String path,
                                                                                   int defaultValue,
                                                                                   int min,
                                                                                   int max) {
        return builder.defineInRange(
                path,
                SuperbwarfareExplosionShakeLock.lockedValue(),
                SuperbwarfareExplosionShakeLock.minValue(),
                SuperbwarfareExplosionShakeLock.maxValue()
        );
    }
}
