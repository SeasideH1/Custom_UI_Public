package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.CursorEffectFrameRateHelper;
import com.lootmatrix.customui.config.CursorEffectConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftFramerateLimitMixin {

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void customui$raiseMenuFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return;
        }

        boolean effectsEnabled;
        try {
            effectsEnabled = CursorEffectConfig.INSTANCE.enabled.get();
        } catch (Exception e) {
            effectsEnabled = true;
        }

        int configuredLimit = minecraft.options.framerateLimit().get();
        int resolved = CursorEffectFrameRateHelper.resolveMenuFramerateLimit(
                cir.getReturnValue(),
                configuredLimit,
                effectsEnabled,
                minecraft.level != null,
                minecraft.screen != null
        );
        cir.setReturnValue(resolved);
    }
}
