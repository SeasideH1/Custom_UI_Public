package com.lootmatrix.customui.client;

import com.atsuishio.superbwarfare.config.client.DisplayConfig;
import net.minecraftforge.common.ForgeConfigSpec;

public final class SuperbwarfareExplosionShakeLock {

    public static final int LOCKED_VALUE = 90;

    private SuperbwarfareExplosionShakeLock() {
    }

    public static int lockedValue() {
        return LOCKED_VALUE;
    }

    public static int minValue() {
        return LOCKED_VALUE;
    }

    public static int maxValue() {
        return LOCKED_VALUE;
    }

    public static boolean shouldLock(ForgeConfigSpec.ConfigValue<?> configValue) {
        return configValue == DisplayConfig.EXPLOSION_SCREEN_SHAKE;
    }

    public static Object coerceSet(ForgeConfigSpec.ConfigValue<?> configValue, Object requestedValue) {
        return shouldLock(configValue) ? Integer.valueOf(LOCKED_VALUE) : requestedValue;
    }
}
