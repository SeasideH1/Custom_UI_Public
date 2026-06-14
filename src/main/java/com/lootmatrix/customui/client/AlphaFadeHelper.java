package com.lootmatrix.customui.client;

/**
 * Reusable utility for handling overlay alpha fade animations without flickering.
 * <p>
 * <b>Problem:</b> When overlay elements fade out with linear alpha interpolation,
 * GPU alpha blending at very low integer alpha values (especially around 25/255 ≈ 0.098)
 * causes visible flickering due to floating-point precision issues, frame-rate
 * inconsistency, and sub-pixel rendering artifacts.
 * <p>
 * <b>Solution:</b> Apply a non-linear easing curve so the alpha value "speeds through"
 * the problematic low range, and enforce a minimum integer alpha floor to avoid
 * the 1–3 alpha range where flicker is most visible.
 * <p>
 * All methods are static and thread-safe (stateless).
 *
 * @see DamageNumberRenderer — where this pattern was first applied ad-hoc
 */
public final class AlphaFadeHelper {

    // ======================== Tunable Constants ========================

    /**
     * Minimum float alpha below which rendering should be skipped entirely.
     * This is checked <em>before</em> easing is applied.
     */
    public static final float SKIP_RENDER_THRESHOLD = 0.02f;

    /**
     * Minimum integer alpha value (0–255) after easing + conversion.
     * Values 1–3 cause visible flicker on most GPUs; 4 is safe.
     */
    public static final int MIN_ALPHA_INT = 4;

    /**
     * Maximum integer alpha value (clamped to prevent overflow).
     */
    public static final int MAX_ALPHA_INT = 255;

    private AlphaFadeHelper() {}

    // ======================== Core Methods ========================

    /**
     * Apply a quadratic ease-out curve to a raw linear alpha.
     * <p>
     * The curve {@code alpha²} causes the value to spend less time in the
     * low-alpha "flicker zone" (0.02–0.15) and more time in the visually
     * stable mid-to-high range, producing a perceptually smoother fade.
     * <p>
     * Input and output are both in [0, 1].
     *
     * @param rawAlpha linear alpha in [0, 1]
     * @return eased alpha in [0, 1]
     */
    public static float smoothAlpha(float rawAlpha) {
        if (rawAlpha <= 0f) return 0f;
        if (rawAlpha >= 1f) return 1f;
        return rawAlpha * rawAlpha; // quadratic ease-out
    }

    /**
     * Apply a cubic ease-out curve — stronger than quadratic.
     * Use for overlays with longer fade durations (≥500 ms) where
     * the quadratic curve is not aggressive enough.
     *
     * @param rawAlpha linear alpha in [0, 1]
     * @return eased alpha in [0, 1]
     */
    public static float smoothAlphaCubic(float rawAlpha) {
        if (rawAlpha <= 0f) return 0f;
        if (rawAlpha >= 1f) return 1f;
        return rawAlpha * rawAlpha * rawAlpha;
    }

    /**
     * Smoothstep easing — provides an S-curve that eases both
     * the beginning and end of the transition. Best for fade-in
     * followed by fade-out sequences.
     *
     * @param rawAlpha linear alpha in [0, 1]
     * @return eased alpha in [0, 1]
     */
    public static float smoothStep(float rawAlpha) {
        if (rawAlpha <= 0f) return 0f;
        if (rawAlpha >= 1f) return 1f;
        // Hermite interpolation: 3t² - 2t³
        return rawAlpha * rawAlpha * (3f - 2f * rawAlpha);
    }

    /**
     * Whether the raw alpha is below the visibility threshold and
     * the overlay element should skip rendering entirely.
     * <p>
     * Call this <em>before</em> any easing to avoid wasting GPU
     * draw calls on invisible elements.
     *
     * @param rawAlpha the un-eased alpha in [0, 1]
     * @return true if rendering should be skipped
     */
    public static boolean shouldSkipRender(float rawAlpha) {
        return rawAlpha < SKIP_RENDER_THRESHOLD;
    }

    /**
     * Clamp an integer alpha value to the safe range [MIN_ALPHA_INT, MAX_ALPHA_INT].
     * Values below MIN_ALPHA_INT cause GPU flicker; values above 255 overflow.
     *
     * @param alphaInt unclamped integer alpha (0–255 scale)
     * @return clamped integer alpha
     */
    public static int clampAlphaInt(int alphaInt) {
        return Math.max(MIN_ALPHA_INT, Math.min(MAX_ALPHA_INT, alphaInt));
    }

    // ======================== Convenience Composites ========================

    /**
     * One-call method: apply easing, convert to int, clamp, and compose
     * into an ARGB color value.
     * <p>
     * This replaces the common pattern:
     * <pre>
     *   int alpha = (int)(rawAlpha * 255);
     *   int color = (alpha &lt;&lt; 24) | (baseRgb &amp; 0x00FFFFFF);
     * </pre>
     *
     * @param rawAlpha linear alpha in [0, 1]
     * @param baseRgb  the RGB portion of the color (alpha bits are ignored)
     * @return full ARGB color with eased + clamped alpha
     */
    public static int toColorWithAlpha(float rawAlpha, int baseRgb) {
        float eased = smoothAlpha(rawAlpha);
        int alphaInt = clampAlphaInt((int) (eased * 255f));
        return (alphaInt << 24) | (baseRgb & 0x00FFFFFF);
    }

    /**
     * Variant that uses cubic easing for longer fade durations.
     *
     * @param rawAlpha linear alpha in [0, 1]
     * @param baseRgb  the RGB portion of the color (alpha bits are ignored)
     * @return full ARGB color with cubic-eased + clamped alpha
     */
    public static int toColorWithAlphaCubic(float rawAlpha, int baseRgb) {
        float eased = smoothAlphaCubic(rawAlpha);
        int alphaInt = clampAlphaInt((int) (eased * 255f));
        return (alphaInt << 24) | (baseRgb & 0x00FFFFFF);
    }

    /**
     * Apply easing to an existing ARGB color's alpha channel.
     * Extracts the current alpha, applies quadratic easing and clamping,
     * then recomposes the color.
     *
     * @param argbColor original ARGB color
     * @return color with eased alpha
     */
    public static int easeExistingAlpha(int argbColor) {
        int originalAlpha = (argbColor >> 24) & 0xFF;
        float normalized = originalAlpha / 255f;
        float eased = smoothAlpha(normalized);
        int newAlpha = clampAlphaInt((int) (eased * 255f));
        return (newAlpha << 24) | (argbColor & 0x00FFFFFF);
    }

    /**
     * Compute a safe float alpha for use with {@code RenderSystem.setShaderColor()}.
     * Applies quadratic easing and ensures the result is never in the flicker zone.
     *
     * @param rawAlpha linear alpha in [0, 1]
     * @return eased alpha suitable for shader color, in [0, 1]
     */
    public static float safeShaderAlpha(float rawAlpha) {
        if (rawAlpha <= 0f) return 0f;
        if (rawAlpha >= 1f) return 1f;
        float eased = smoothAlpha(rawAlpha);
        // Ensure the eased value maps to at least MIN_ALPHA_INT/255 to avoid flicker
        float minFloat = MIN_ALPHA_INT / 255f;
        return Math.max(minFloat, eased);
    }
}
