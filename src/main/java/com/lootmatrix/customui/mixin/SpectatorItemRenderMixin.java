package com.lootmatrix.customui.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to render the spectated player's held items in first-person view
 * instead of the spectator's empty hands.
 *
 * When spectating another player, this redirects the hand item queries
 * to the spectated player's items, providing proper first-person weapon/item rendering.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class SpectatorItemRenderMixin {

    /**
     * Redirect main hand item to spectated player's main hand.
     */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"
            )
    )
    private ItemStack customui$redirectMainHandItem(LocalPlayer self) {
        Player spectated = getSpectatedPlayer();
        if (spectated != null) {
            return spectated.getMainHandItem();
        }
        return self.getMainHandItem();
    }

    /**
     * Redirect off hand item to spectated player's off hand.
     */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getOffhandItem()Lnet/minecraft/world/item/ItemStack;"
            )
    )
    private ItemStack customui$redirectOffHandItem(LocalPlayer self) {
        Player spectated = getSpectatedPlayer();
        if (spectated != null) {
            return spectated.getOffhandItem();
        }
        return self.getOffhandItem();
    }

    /**
     * Get the player being spectated, or null if not spectating.
     */
    private static Player getSpectatedPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return null;
        if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) return null;

        Entity camera = mc.getCameraEntity();
        if (camera instanceof Player player && camera != mc.player) {
            return player;
        }
        return null;
    }
}
