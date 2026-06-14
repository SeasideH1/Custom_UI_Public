package com.lootmatrix.customui.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.LogicalSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Direct subscriber to TACZ hit and kill events.
 * Uses reflection so registration does not depend on TaCZ event method signatures.
 *
 * Based on TACZ's ClientHitMark:
 * - EntityHurtByGunEvent.Post fires when a gun hit lands (for crosshair hit feedback)
 * - EntityKillByGunEvent fires when player kills with a gun (for kill icon + crosshair kill feedback)
 */
public class TaczEventSubscriber {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaczEventSubscriber.class);
    private static boolean registered = false;
    private static final boolean EVENT_BRIDGE_ENABLED = Boolean.getBoolean("customui.tacz.eventBridge");

    private static Method hurtLogicalSideMethod;
    private static Method hurtAttackerMethod;
    private static Method hurtHeadShotMethod;
    private static Method killLogicalSideMethod;
    private static Method killAttackerMethod;
    private static Method killHeadShotMethod;
    private static boolean hurtReflectionInitialized;
    private static boolean killReflectionInitialized;

    /**
     * Register this subscriber to the Forge event bus.
     */
    public static boolean register() {
        if (registered) return true;
        if (!EVENT_BRIDGE_ENABLED) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> hurtEventClass =
                    (Class<? extends Event>) Class.forName("com.tacz.guns.api.event.common.EntityHurtByGunEvent$Post");
            @SuppressWarnings("unchecked")
            Class<? extends Event> killEventClass =
                    (Class<? extends Event>) Class.forName("com.tacz.guns.api.event.common.EntityKillByGunEvent");
            MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.NORMAL,
                    false,
                    hurtEventClass,
                    event -> TaczEventSubscriber.onEntityHurtByGun(event)
            );
            MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.NORMAL,
                    false,
                    killEventClass,
                    event -> TaczEventSubscriber.onEntityKillByGun(event)
            );
            registered = true;
            return true;
        } catch (ClassNotFoundException ignored) {
            // TaCZ not present or event API not available.
            return false;
        } catch (LinkageError | RuntimeException e) {
            LOGGER.debug("[TaczEventSubscriber] TaCZ client event bridge unavailable: {}", e.toString());
            return false;
        }
    }

    /**
     * Handle TACZ gun hit events (for crosshair hit feedback).
     * Fires on every successful hit, including headshots.
     */
    public static void onEntityHurtByGun(Object event) {
        if (!initHurtReflection(event)) {
            return;
        }

        try {
            if (hurtLogicalSideMethod.invoke(event) != LogicalSide.CLIENT) {
                return;
            }

            Object attacker = hurtAttackerMethod.invoke(event);
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.equals(attacker)) {
                CrosshairOverlayRenderer.onHit((Boolean) hurtHeadShotMethod.invoke(event));
            }
        } catch (ReflectiveOperationException ignored) {
            // Unsupported TaCZ build, skip client hit feedback.
        }
    }

    /**
     * Handle TACZ gun kill events.
     * Directly adds kill indicator with proper headshot detection.
     */
    public static void onEntityKillByGun(Object event) {
        if (!initKillReflection(event)) {
            return;
        }

        try {
            if (killLogicalSideMethod.invoke(event) != LogicalSide.CLIENT) {
                return;
            }

            Object attacker = killAttackerMethod.invoke(event);
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.equals(attacker)) {
                boolean isHeadshot = (Boolean) killHeadShotMethod.invoke(event);
                CrosshairOverlayRenderer.onKill(isHeadshot);
            }
        } catch (ReflectiveOperationException ignored) {
            // Unsupported TaCZ build, skip client kill feedback.
        }
    }

    private static boolean initHurtReflection(Object event) {
        if (hurtReflectionInitialized) {
            return hurtLogicalSideMethod != null
                    && hurtAttackerMethod != null
                    && hurtHeadShotMethod != null;
        }

        hurtReflectionInitialized = true;
        try {
            Class<?> eventClass = event.getClass();
            hurtLogicalSideMethod = eventClass.getMethod("getLogicalSide");
            hurtAttackerMethod = eventClass.getMethod("getAttacker");
            hurtHeadShotMethod = eventClass.getMethod("isHeadShot");
            return true;
        } catch (ReflectiveOperationException ignored) {
            hurtLogicalSideMethod = null;
            hurtAttackerMethod = null;
            hurtHeadShotMethod = null;
            return false;
        }
    }

    private static boolean initKillReflection(Object event) {
        if (killReflectionInitialized) {
            return killLogicalSideMethod != null
                    && killAttackerMethod != null
                    && killHeadShotMethod != null;
        }

        killReflectionInitialized = true;
        try {
            Class<?> eventClass = event.getClass();
            killLogicalSideMethod = eventClass.getMethod("getLogicalSide");
            killAttackerMethod = eventClass.getMethod("getAttacker");
            killHeadShotMethod = eventClass.getMethod("isHeadShot");
            return true;
        } catch (ReflectiveOperationException ignored) {
            killLogicalSideMethod = null;
            killAttackerMethod = null;
            killHeadShotMethod = null;
            return false;
        }
    }
}
