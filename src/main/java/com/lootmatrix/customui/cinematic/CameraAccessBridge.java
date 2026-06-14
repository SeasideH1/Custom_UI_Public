package com.lootmatrix.customui.cinematic;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class CameraAccessBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Method SET_POSITION_METHOD = findMethod("setPosition", "m_90584_", double.class, double.class, double.class);
    private static final Field DETACHED_FIELD = findField("detached", "f_90560_");
    private static boolean setPositionWarningLogged;
    private static boolean detachedWarningLogged;

    private CameraAccessBridge() {
    }

    static void setPosition(Camera camera, Vec3 position) {
        if (SET_POSITION_METHOD == null) {
            logSetPositionWarning(null);
            return;
        }

        try {
            SET_POSITION_METHOD.invoke(camera, position.x, position.y, position.z);
        } catch (ReflectiveOperationException e) {
            logSetPositionWarning(e);
        }
    }

    static void setDetached(Camera camera, boolean detached) {
        if (DETACHED_FIELD == null) {
            logDetachedWarning(null);
            return;
        }

        try {
            DETACHED_FIELD.setBoolean(camera, detached);
        } catch (IllegalAccessException e) {
            logDetachedWarning(e);
        }
    }

    private static Method findMethod(String officialName, String srgName, Class<?>... parameterTypes) {
        for (String candidate : new String[]{officialName, srgName}) {
            try {
                Method method = Camera.class.getDeclaredMethod(candidate, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static Field findField(String officialName, String srgName) {
        for (String candidate : new String[]{officialName, srgName}) {
            try {
                Field field = Camera.class.getDeclaredField(candidate);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static void logSetPositionWarning(Exception exception) {
        if (setPositionWarningLogged) {
            return;
        }
        setPositionWarningLogged = true;
        if (exception == null) {
            LOGGER.warn("[CustomUI] Unable to resolve Camera#setPosition; cinematic camera position override is disabled.");
            return;
        }
        LOGGER.warn("[CustomUI] Failed to invoke Camera#setPosition for cinematic camera.", exception);
    }

    private static void logDetachedWarning(Exception exception) {
        if (detachedWarningLogged) {
            return;
        }
        detachedWarningLogged = true;
        if (exception == null) {
            LOGGER.warn("[CustomUI] Unable to resolve Camera.detached; cinematic showSelf mode is disabled.");
            return;
        }
        LOGGER.warn("[CustomUI] Failed to update Camera.detached for cinematic camera.", exception);
    }
}
