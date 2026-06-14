package com.lootmatrix.customui.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccessor {

    @Accessor("reload")
    ReloadInstance customui$getReload();

    @Accessor("onFinish")
    Consumer<Optional<Throwable>> customui$getOnFinish();

    @Accessor("fadeIn")
    boolean customui$getFadeIn();

    @Accessor("fadeOutStart")
    long customui$getFadeOutStart();

    @Accessor("fadeInStart")
    long customui$getFadeInStart();
}
