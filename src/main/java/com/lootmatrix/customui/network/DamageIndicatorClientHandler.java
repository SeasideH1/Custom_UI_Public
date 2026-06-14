package com.lootmatrix.customui.network;

import com.lootmatrix.customui.client.DamageIndicatorOverlay;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side handler for damage indicator packets.
 * This class is only loaded on the client side to avoid class loading issues on servers.
 *
 * This pattern is necessary because some server implementations (like Mohist) may
 * attempt to load imported classes even if the code path is not executed.
 */
@OnlyIn(Dist.CLIENT)
public class DamageIndicatorClientHandler {

    /**
     * Handle a damage indicator packet on the client side.
     *
     * @param sourcePos The position of the damage source, or null if unknown
     * @param damageAmount The amount of damage taken
     */
    public static void handleDamagePacket(Vec3 sourcePos, float damageAmount) {
        DamageIndicatorOverlay.getInstance().addDamageIndicator(sourcePos, damageAmount);
    }
}

