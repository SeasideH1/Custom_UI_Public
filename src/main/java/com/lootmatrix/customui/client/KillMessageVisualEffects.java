package com.lootmatrix.customui.client;

final class KillMessageVisualEffects {

    static final float FLASH_DURATION_MS = 600f;
    static final float CROSSOUT_DELAY_AFTER_FLASH_MS = 50f;
    static final float CROSSOUT_START_MS = FLASH_DURATION_MS + CROSSOUT_DELAY_AFTER_FLASH_MS;
    static final float CROSSOUT_DURATION_MS = 500f;
    static final float FADE_IN_MS = 150f;
    static final float STAY_MS = 6000f;
    static final float FADE_OUT_MS = 500f;
    static final int STRIKE_LINE_MAX_ALPHA = Math.round(255f * 1.0f);

    private KillMessageVisualEffects() {
    }

    static float crossoutProgress(float elapsedMs) {
        return easeInOutCubic((elapsedMs - CROSSOUT_START_MS) / CROSSOUT_DURATION_MS);
    }

    static int grayOutNameColor(int argbColor, float progress) {
        float t = clamp01(progress);
        if (t <= 0f) {
            return argbColor;
        }

        int a = (argbColor >>> 24) & 0xFF;
        int r = (argbColor >>> 16) & 0xFF;
        int g = (argbColor >>> 8) & 0xFF;
        int b = argbColor & 0xFF;
        int gray = Math.round((r + g + b) / 3f);
        int targetR = Math.round((r * 0.18f + gray * 0.82f) * 0.92f);
        int targetG = Math.round((g * 0.18f + gray * 0.82f) * 0.92f);
        int targetB = Math.round((b * 0.18f + gray * 0.82f) * 0.92f);

        int outR = Math.round(lerp(r, targetR, t));
        int outG = Math.round(lerp(g, targetG, t));
        int outB = Math.round(lerp(b, targetB, t));
        return (a << 24) | (clamp255(outR) << 16) | (clamp255(outG) << 8) | clamp255(outB);
    }

    static float strikeWidth(float textWidth, float progress) {
        return Math.max(0f, textWidth) * clamp01(progress);
    }

    static float strikeCenterY(float textTopY, int lineHeight) {
        return textTopY + Math.max(0, lineHeight) * 0.5f;
    }

    static float centeredTextTopY(float boxTopY, float boxBottomY, int lineHeight) {
        float boxHeight = Math.max(0f, boxBottomY - boxTopY);
        return boxTopY + (boxHeight - Math.max(0, lineHeight)) * 0.5f;
    }

    static float messageAlpha(float elapsedMs) {
        if (elapsedMs < FADE_IN_MS) {
            return clamp01(elapsedMs / FADE_IN_MS);
        }
        float fadeStartMs = FADE_IN_MS + STAY_MS;
        if (elapsedMs < fadeStartMs) {
            return 1f;
        }
        return 1f - clamp01((elapsedMs - fadeStartMs) / FADE_OUT_MS);
    }

    static int overlayAlphaInt(float rawAlpha) {
        return AlphaFadeHelper.clampAlphaInt((int) (AlphaFadeHelper.smoothAlpha(rawAlpha) * 255f));
    }

    static int backgroundAlphaInt(float rawAlpha) {
        if (AlphaFadeHelper.shouldSkipRender(rawAlpha)) {
            return 0;
        }
        return (int) (AlphaFadeHelper.smoothAlpha(rawAlpha) * 128f);
    }

    static int strikeLineAlphaInt(float rawAlpha) {
        if (AlphaFadeHelper.shouldSkipRender(rawAlpha)) {
            return 0;
        }
        return Math.round(AlphaFadeHelper.smoothAlpha(rawAlpha) * STRIKE_LINE_MAX_ALPHA);
    }

    static boolean messageExpired(float elapsedMs) {
        return elapsedMs > FADE_IN_MS + STAY_MS + FADE_OUT_MS;
    }

    private static float easeInOutCubic(float value) {
        float t = clamp01(value);
        return t < 0.5f
                ? 4f * t * t * t
                : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp01(float value) {
        if (value <= 0f) {
            return 0f;
        }
        if (value >= 1f) {
            return 1f;
        }
        return value;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
