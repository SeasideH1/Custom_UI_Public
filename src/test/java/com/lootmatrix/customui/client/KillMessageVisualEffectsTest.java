package com.lootmatrix.customui.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillMessageVisualEffectsTest {

    @Test
    void crossoutProgressWaitsForFlashAndShortDelayThenUsesCubicCurve() {
        assertEquals(0f, KillMessageVisualEffects.crossoutProgress(649f), 0.0001f);
        assertEquals(0f, KillMessageVisualEffects.crossoutProgress(650f), 0.0001f);
        assertEquals(0.5f, KillMessageVisualEffects.crossoutProgress(900f), 0.0001f);
        assertEquals(1f, KillMessageVisualEffects.crossoutProgress(1150f), 0.0001f);
    }

    @Test
    void grayOutNameColorPreservesAlphaAndDesaturatesTowardGray() {
        int color = KillMessageVisualEffects.grayOutNameColor(0xCC55FF55, 1f);

        assertEquals(0xCC, (color >>> 24) & 0xFF);
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        assertTrue(g >= r);
        assertTrue(g >= b);
        assertTrue(g - r < 64);
        assertTrue(g - b < 64);
    }

    @Test
    void strikeWidthFollowsProgressAndClampsToTextWidth() {
        assertEquals(0f, KillMessageVisualEffects.strikeWidth(80f, -1f), 0.0001f);
        assertEquals(40f, KillMessageVisualEffects.strikeWidth(80f, 0.5f), 0.0001f);
        assertEquals(80f, KillMessageVisualEffects.strikeWidth(80f, 2f), 0.0001f);
    }

    @Test
    void strikeCenterYUsesPlayerIdTextMidline() {
        assertEquals(10.5f, KillMessageVisualEffects.strikeCenterY(6f, 9), 0.0001f);
    }

    @Test
    void centeredTextTopYKeepsPlayerIdAlignedWhenMessageRowUsesFractionalY() {
        float boxTopY = 44.75f;
        float boxBottomY = 58.75f;

        float textTopY = KillMessageVisualEffects.centeredTextTopY(boxTopY, boxBottomY, 9);

        assertEquals(47.25f, textTopY, 0.0001f);
        assertEquals(51.75f, KillMessageVisualEffects.strikeCenterY(textTopY, 9), 0.0001f);
    }

    @Test
    void messageAlphaUsesExtendedHoldAndPerMessageLifetime() {
        assertEquals(1f, KillMessageVisualEffects.messageAlpha(6150f), 0.0001f);
        assertEquals(0.5f, KillMessageVisualEffects.messageAlpha(6400f), 0.0001f);
    }

    @Test
    void backgroundAlphaKeepsSoftHalfOpacityFadeStrength() {
        assertEquals(128, KillMessageVisualEffects.backgroundAlphaInt(1f));
        assertEquals(32, KillMessageVisualEffects.backgroundAlphaInt(0.5f));
        assertEquals(1, KillMessageVisualEffects.backgroundAlphaInt(0.1f));
        assertEquals(0, KillMessageVisualEffects.backgroundAlphaInt(0.03f));
    }

    @Test
    void localDeathBackgroundRgbUsesSeventyFivePercentRedMix() {
        assertEquals(0xC80808, KillMessageOverlayRenderer.backgroundRgb(true));
        assertEquals(0x202020, KillMessageOverlayRenderer.backgroundRgb(false));
    }

    @Test
    void strikeLineAlphaUsesNinetyPercentPeakOpacity() {
        assertEquals(255, KillMessageVisualEffects.strikeLineAlphaInt(1f));
        assertEquals(64, KillMessageVisualEffects.strikeLineAlphaInt(0.5f));
        assertEquals(3, KillMessageVisualEffects.strikeLineAlphaInt(0.1f));
        assertEquals(0, KillMessageVisualEffects.strikeLineAlphaInt(0.03f));
    }

    @Test
    void strikeLineAlphaTurnsTransparentWhenMessageWouldSkipRendering() {
        assertEquals(0, KillMessageVisualEffects.strikeLineAlphaInt(0f));
        assertEquals(0, KillMessageVisualEffects.strikeLineAlphaInt(0.01f));
    }

    @Test
    void messageExpiresAfterItsOwnFadeCompletes() {
        assertFalse(KillMessageVisualEffects.messageExpired(6649f));
        assertTrue(KillMessageVisualEffects.messageExpired(6651f));
    }
}
