package com.lootmatrix.customui.mixin;

import com.lootmatrix.customui.client.BackgroundGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept TACZ's GunHudOverlay and replace it with our custom HUD in Adventure mode.
 */

// 能解析就用value，不能才用target
@Mixin(value = com.tacz.guns.client.gui.overlay.GunHudOverlay.class, remap = false)
public abstract class TaczGunHudMixin implements IGuiOverlay {

    /**
     * Inject at the head of render method to cancel and replace with custom HUD in Adventure mode.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height, CallbackInfo ci) {
        if (BackgroundGuard.isInCooldown()) return;

        Minecraft mc = Minecraft.getInstance();

        // Only intercept in Adventure mode
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.ADVENTURE) {
            // Cancel the original TACZ HUD rendering
            ci.cancel();
            // Our custom HUD is rendered via TaczGunHudOverlay event subscriber
        }
    }
}

