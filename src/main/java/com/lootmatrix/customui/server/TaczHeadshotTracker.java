package com.lootmatrix.customui.server;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.LogicalSide;

import java.lang.reflect.Method;

/**
 * Server-side tracker for TaCZ headshot events.
 * Uses reflection so registration does not depend on TaCZ event signatures.
 */
public class TaczHeadshotTracker {

    private static Method getLogicalSideMethod;
    private static Method getHurtEntityMethod;
    private static Method isHeadShotMethod;
    private static boolean reflectionInitialized;

    public static void onHurtByGun(Object event) {
        if (!initReflection(event)) {
            return;
        }

        try {
            if (getLogicalSideMethod.invoke(event) != LogicalSide.SERVER) {
                return;
            }

            Object hurtEntity = getHurtEntityMethod.invoke(event);
            if (hurtEntity instanceof LivingEntity victim) {
                DamageEventHandler.setTaczHeadshot(victim, (Boolean) isHeadShotMethod.invoke(event));
            }
        } catch (ReflectiveOperationException ignored) {
            // Disable silently for signature mismatches on unsupported TaCZ builds.
        }
    }

    private static boolean initReflection(Object event) {
        if (reflectionInitialized) {
            return getLogicalSideMethod != null
                    && getHurtEntityMethod != null
                    && isHeadShotMethod != null;
        }

        reflectionInitialized = true;
        try {
            Class<?> eventClass = event.getClass();
            getLogicalSideMethod = eventClass.getMethod("getLogicalSide");
            getHurtEntityMethod = eventClass.getMethod("getHurtEntity");
            isHeadShotMethod = eventClass.getMethod("isHeadShot");
            return true;
        } catch (ReflectiveOperationException ignored) {
            getLogicalSideMethod = null;
            getHurtEntityMethod = null;
            isHeadShotMethod = null;
            return false;
        }
    }
}

