package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.atmosphere.AtmosphereEngine;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles client connection lifecycle cleanup.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, value = Dist.CLIENT)
public final class ClientConnectionEvents {

    private ClientConnectionEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AtmosphereEngine.getInstance().forceStop();
        ImmediateRespawnTracker.clear();
    }
}
