package com.lootmatrix.customui.mixin;

import net.minecraftforge.client.loading.ForgeLoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ForgeLoadingOverlay declares its own private {@code fadeOutStart} that shadows
 * the vanilla {@code LoadingOverlay} field, so the vanilla accessor always reads
 * -1 on the initial mod-loading overlay and the themed layer never knew the
 * fade-out had begun. This accessor exposes the real Forge-side timestamp.
 */
@Mixin(value = ForgeLoadingOverlay.class, remap = false)
public interface ForgeLoadingOverlayAccessor {

    @Accessor("fadeOutStart")
    long customui$getForgeFadeOutStart();
}
