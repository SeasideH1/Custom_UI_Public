package com.lootmatrix.customui.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CursorEffectFrameRateHelperTest {

    @Test
    void menuWithoutWorldUsesUserConfiguredLimitWhenHigherThanSixty() {
        int result = CursorEffectFrameRateHelper.resolveMenuFramerateLimit(
                30, 144, true, false, true);

        assertEquals(144, result);
    }

    @Test
    void menuWithoutWorldKeepsSixtyAsMinimum() {
        int result = CursorEffectFrameRateHelper.resolveMenuFramerateLimit(
                30, 30, true, false, true);

        assertEquals(60, result);
    }

    @Test
    void worldOrMissingScreenKeepsVanillaLimit() {
        assertEquals(30, CursorEffectFrameRateHelper.resolveMenuFramerateLimit(
                30, 144, true, true, true));
        assertEquals(30, CursorEffectFrameRateHelper.resolveMenuFramerateLimit(
                30, 144, true, false, false));
    }

    @Test
    void disabledCursorEffectsKeepVanillaLimit() {
        int result = CursorEffectFrameRateHelper.resolveMenuFramerateLimit(
                30, 144, false, false, true);

        assertEquals(30, result);
    }
}
