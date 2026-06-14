package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.title.TitleSceneManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the dirt background of out-of-world menus (options, world select,
 * multiplayer, ...) with the live 3D title scene so every menu shares the
 * same dynamic backdrop, LabyMod-style. No-ops in-world and when the scene
 * is unavailable.
 */
@Mixin(Screen.class)
public abstract class ScreenDirtBackgroundMixin {

    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
    private void customui$sceneMenuBackground(GuiGraphics graphics, CallbackInfo ci) {
        if (TitleSceneManager.renderMenuBackground((Screen) (Object) this, graphics)) {
            ci.cancel();
        }
    }
}
