package com.lootmatrix.customui.client.hud;

import com.lootmatrix.customui.mixin.GameRendererBlurInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Background blur for GUI template screens, driven through the vanilla
 * {@code GameRenderer} post-effect slot (the same channel spectator mob
 * shaders use). The chain is processed after the world render and before
 * HUD/GUI drawing, so the world blurs while the GUI stays sharp — no
 * manual per-frame PostChain management or render-state juggling.
 */
@OnlyIn(Dist.CLIENT)
public final class GuiBlurEffect {

    private static final ResourceLocation BLUR_POST_CHAIN =
            ResourceLocation.fromNamespaceAndPath("customui", "shaders/post/gui_blur.json");

    private boolean active = false;

    /** Load the blur post effect unless another effect (e.g. spectator) owns the slot. */
    public void activate() {
        if (active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.currentEffect() != null) return;
        ((GameRendererBlurInvoker) mc.gameRenderer).customui$loadEffect(BLUR_POST_CHAIN);
        active = true;
    }

    /** Shut the effect down only if it is still ours. */
    public void close() {
        if (!active) return;
        active = false;
        Minecraft mc = Minecraft.getInstance();
        PostChain current = mc.gameRenderer.currentEffect();
        if (current != null && BLUR_POST_CHAIN.toString().equals(current.getName())) {
            mc.gameRenderer.shutdownEffect();
        }
    }
}
