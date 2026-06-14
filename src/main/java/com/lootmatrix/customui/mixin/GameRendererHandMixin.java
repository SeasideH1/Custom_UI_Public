package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.ImmediateRespawnTracker;
import com.lootmatrix.customui.cinematic.CinematicCameraEngine;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes the first-person hand/weapon model disappearing when F1 (hideGui) is pressed.
 *
 * In vanilla MC 1.20.1, {@code GameRenderer.renderItemInHand()} checks
 * {@code !this.minecraft.options.hideGui} before rendering the hand items.
 * This causes the gun model (and any held item) to vanish when F1 hides the HUD.
 *
 * This mixin redirects that field read to always return {@code false},
 * so hands render regardless of the GUI visibility toggle.
 * The HUD itself is still hidden by Gui.render()'s own hideGui check.
 *
 * Also suppresses hand rendering during immediate respawn wait to prevent
 * a brief flash of wrong-gamemode hands (e.g. ADVENTURE hands before SPECTATOR).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {

    /**
     * Suppress hand rendering while the local player is waiting for the
     * gamemode change packet after an immediate respawn.
     */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void customui$suppressHandsDuringRespawnWait(PoseStack poseStack, Camera camera,
                                                          float partialTick, CallbackInfo ci) {
        if (ImmediateRespawnTracker.shouldSuppressLocalPlayer()
                || CinematicCameraEngine.getInstance().shouldHideHud()) {
            ci.cancel();
        }
    }

    /**
     * Redirect the {@code options.hideGui} read inside {@code renderItemInHand}
     * so the hand is always rendered even when F1 hides the GUI.
     */
    @Redirect(method = "renderItemInHand",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/Options;hideGui:Z",
                    opcode = Opcodes.GETFIELD))
    private boolean customui$forceRenderHandWhenGuiHidden(Options options) {
        // Always pretend hideGui is false for hand/item rendering
        return false;
    }
}



