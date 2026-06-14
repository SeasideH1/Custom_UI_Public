package com.lootmatrix.customui.client;

public final class CursorEffectFrameRateHelper {

    private static final int MIN_MENU_FPS = 60;

    private CursorEffectFrameRateHelper() {
    }

    public static int resolveMenuFramerateLimit(int vanillaLimit, int configuredLimit,
                                                boolean effectsEnabled, boolean inWorld, boolean hasScreen) {
        if (!effectsEnabled || inWorld || !hasScreen) {
            return vanillaLimit;
        }
        return Math.max(Math.max(vanillaLimit, configuredLimit), MIN_MENU_FPS);
    }
}
