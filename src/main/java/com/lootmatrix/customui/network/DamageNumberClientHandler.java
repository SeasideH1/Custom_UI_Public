package com.lootmatrix.customui.network;

import com.lootmatrix.customui.client.CrosshairOverlayRenderer;
import com.lootmatrix.customui.client.DamageNumberRenderer;
import com.lootmatrix.customui.client.GunAmmoHelper;
import com.lootmatrix.customui.client.KillIconOverlay;
import com.lootmatrix.customui.client.TaczKillEventHandler;
import com.lootmatrix.customui.config.CrosshairConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Client-side handler for damage number packets.
 * This class is only loaded on the client side to avoid class loading issues.
 */
@OnlyIn(Dist.CLIENT)
public class DamageNumberClientHandler {

    /**
     * Handle a damage packet on the client side.
     *
     * @param entityUUID UUID of the damaged entity
     * @param damage     Amount of damage dealt
     * @param isKill     Whether this damage killed the entity
     * @param isCritical Whether this was a critical hit
     */
    public static void handleDamagePacket(UUID entityUUID, float damage, boolean isKill, boolean isCritical,
                                          boolean isHeadshot, long killEventId) {
        DamageNumberRenderer.getInstance().addDamage(entityUUID, damage, isKill);

        boolean handledByTaczGunEvents = TaczKillEventHandler.isTaczAvailable() && isHoldingTaczGun();
        if (shouldUsePacketHitFeedback(handledByTaczGunEvents)) {
            if (isKill) {
                CrosshairOverlayRenderer.onKill(isHeadshot);
            } else {
                CrosshairOverlayRenderer.onHit(isHeadshot);
            }
        }

        // When kill is detected, add kill indicator
        if (isKill) {
            if (isHeadshot) {
                KillIconOverlay.markLastHitAsHeadshot();
            }
            KillIconOverlay.addKillIndicatorForKillEvent(isHeadshot, killEventId);
        }
    }

    /**
     * Check if the local player is currently holding a TACZ gun.
     */
    private static boolean isHoldingTaczGun() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return false;

        ItemStack mainHand = player.getMainHandItem();
        return GunAmmoHelper.isTaczGun(mainHand);
    }

    /**
     * Legacy method without critical hit info.
     */
    public static void handleDamagePacket(UUID entityUUID, float damage, boolean isKill) {
        handleDamagePacket(entityUUID, damage, isKill, false, false, 0L);
    }

    public static void handleDamagePacket(UUID entityUUID, float damage, boolean isKill, boolean isCritical) {
        handleDamagePacket(entityUUID, damage, isKill, isCritical, isCritical, 0L);
    }

    private static boolean shouldUsePacketHitFeedback(boolean handledByTaczGunEvents) {
        return !handledByTaczGunEvents
                && CrosshairConfig.INSTANCE.enabled.get()
                && CrosshairConfig.INSTANCE.vanillaHitFeedbackEnabled.get();
    }
}


