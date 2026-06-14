package com.lootmatrix.customui.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code GameRenderer.loadEffect} so GUI templates can drive the
 * vanilla post-effect chain (processed after the world, before HUD/GUI —
 * the same path spectator mob shaders use, so the GUI itself stays sharp).
 */
@Mixin(GameRenderer.class)
public interface GameRendererBlurInvoker {

    @Invoker("loadEffect")
    void customui$loadEffect(ResourceLocation effect);
}
