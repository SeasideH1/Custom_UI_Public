package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles TACZ gun kill events for the kill icon overlay.
 *
 * Based on TACZ's ClientHitMark class:
 * - EntityKillByGunEvent fires when player kills with a gun
 * - event.isHeadShot() returns true for headshot kills
 *
 * The actual event subscription is done in TaczEventSubscriber if TACZ is loaded.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class TaczKillEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaczKillEventHandler.class);
    private static boolean taczAvailable = false;
    private static boolean initialized = false;

    /**
     * Initialize TACZ event handling.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        try {
            // This flag means the TaCZ event bridge is active. When disabled,
            // normal damage packets still drive hit and kill feedback.
            taczAvailable = TaczEventSubscriber.register();
        } catch (Throwable e) {
            taczAvailable = false;
            LOGGER.debug("[TaczKillEventHandler] TaCZ event bridge unavailable: {}", e.toString());
        }
    }

    public static boolean isTaczAvailable() {
        init();
        return taczAvailable;
    }
}


