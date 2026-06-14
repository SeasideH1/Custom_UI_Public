package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.title.TitleSceneManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Swaps the title screen's cubemap panorama for the real-time 3D scene
 * (LabyMod-style dynamic background). Falls back to the vanilla panorama
 * whenever the scene is disabled or its structure asset is missing.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenSceneMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/PanoramaRenderer;render(FF)V"))
    private void customui$renderDynamicBackground(PanoramaRenderer panorama, float deltaT, float alpha,
                                                  GuiGraphics graphics, int mouseX, int mouseY,
                                                  float partialTick) {
        if (!TitleSceneManager.renderTitleBackground(graphics)) {
            panorama.render(deltaT, alpha);
        }
    }
}
