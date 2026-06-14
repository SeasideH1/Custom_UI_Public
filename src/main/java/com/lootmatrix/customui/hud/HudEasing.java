package com.lootmatrix.customui.hud;

/**
 * Easing curves for HUD keyframe interpolation.
 * Each animated property can pick its own curve per keyframe segment;
 * the curve stored on the destination keyframe shapes the segment leading into it.
 */
public enum HudEasing {
    LINEAR,
    STEP,            // hold previous value until destination tick (instant change)
    EASE_IN_QUAD,
    EASE_OUT_QUAD,
    EASE_IN_OUT_QUAD,
    EASE_IN_CUBIC,
    EASE_OUT_CUBIC,
    EASE_IN_OUT_CUBIC,
    EASE_OUT_BACK,   // overshoot then settle
    EASE_OUT_ELASTIC,
    EASE_OUT_BOUNCE;

    private static final HudEasing[] VALUES = values();

    public static HudEasing byId(int id) {
        return (id >= 0 && id < VALUES.length) ? VALUES[id] : LINEAR;
    }

    public static HudEasing fromString(String s) {
        if (s == null || s.isEmpty()) return LINEAR;
        switch (s.toLowerCase().replace("-", "").replace("_", "")) {
            case "step": case "instant": return STEP;
            case "easein": case "easeinquad": return EASE_IN_QUAD;
            case "easeout": case "easeoutquad": return EASE_OUT_QUAD;
            case "easeinout": case "easeinoutquad": return EASE_IN_OUT_QUAD;
            case "easeincubic": return EASE_IN_CUBIC;
            case "easeoutcubic": return EASE_OUT_CUBIC;
            case "easeinoutcubic": return EASE_IN_OUT_CUBIC;
            case "easeoutback": case "back": return EASE_OUT_BACK;
            case "easeoutelastic": case "elastic": return EASE_OUT_ELASTIC;
            case "easeoutbounce": case "bounce": return EASE_OUT_BOUNCE;
            default: return LINEAR;
        }
    }

    public String toJsonName() {
        switch (this) {
            case STEP: return "step";
            case EASE_IN_QUAD: return "easeInQuad";
            case EASE_OUT_QUAD: return "easeOutQuad";
            case EASE_IN_OUT_QUAD: return "easeInOutQuad";
            case EASE_IN_CUBIC: return "easeInCubic";
            case EASE_OUT_CUBIC: return "easeOutCubic";
            case EASE_IN_OUT_CUBIC: return "easeInOutCubic";
            case EASE_OUT_BACK: return "easeOutBack";
            case EASE_OUT_ELASTIC: return "easeOutElastic";
            case EASE_OUT_BOUNCE: return "easeOutBounce";
            default: return "linear";
        }
    }

    /** Map normalized time t in [0,1] to eased progress in [0,1] (BACK/ELASTIC may overshoot). */
    public float apply(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f && this != STEP) return 1f;
        switch (this) {
            case STEP:
                return t >= 1f ? 1f : 0f;
            case EASE_IN_QUAD:
                return t * t;
            case EASE_OUT_QUAD:
                return 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT_QUAD:
                return t < 0.5f ? 2f * t * t : 1f - 2f * (1f - t) * (1f - t);
            case EASE_IN_CUBIC:
                return t * t * t;
            case EASE_OUT_CUBIC: {
                float u = 1f - t;
                return 1f - u * u * u;
            }
            case EASE_IN_OUT_CUBIC: {
                if (t < 0.5f) return 4f * t * t * t;
                float u = 1f - t;
                return 1f - 4f * u * u * u;
            }
            case EASE_OUT_BACK: {
                float c1 = 1.70158f;
                float c3 = c1 + 1f;
                float u = t - 1f;
                return 1f + c3 * u * u * u + c1 * u * u;
            }
            case EASE_OUT_ELASTIC: {
                float c4 = (float) (2.0 * Math.PI / 3.0);
                return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0);
            }
            case EASE_OUT_BOUNCE: {
                float n1 = 7.5625f, d1 = 2.75f;
                if (t < 1f / d1) return n1 * t * t;
                if (t < 2f / d1) { t -= 1.5f / d1; return n1 * t * t + 0.75f; }
                if (t < 2.5f / d1) { t -= 2.25f / d1; return n1 * t * t + 0.9375f; }
                t -= 2.625f / d1;
                return n1 * t * t + 0.984375f;
            }
            default:
                return t;
        }
    }
}
