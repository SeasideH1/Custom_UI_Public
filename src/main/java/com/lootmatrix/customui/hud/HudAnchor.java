package com.lootmatrix.customui.hud;

/**
 * Nine-point anchor grid. Used both as the screen attachment point of an
 * element and as the element's own origin (pivot for offset/rotation/scale).
 */
public enum HudAnchor {
    TOP_LEFT(0f, 0f), TOP_CENTER(0.5f, 0f), TOP_RIGHT(1f, 0f),
    CENTER_LEFT(0f, 0.5f), CENTER(0.5f, 0.5f), CENTER_RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(0.5f, 1f), BOTTOM_RIGHT(1f, 1f);

    /** Horizontal/vertical fraction within the parent box (0=left/top, 1=right/bottom). */
    public final float fx;
    public final float fy;

    HudAnchor(float fx, float fy) {
        this.fx = fx;
        this.fy = fy;
    }

    private static final HudAnchor[] VALUES = values();

    public static HudAnchor byId(int id) {
        return (id >= 0 && id < VALUES.length) ? VALUES[id] : CENTER;
    }

    public static HudAnchor fromString(String s) {
        if (s == null || s.isEmpty()) return CENTER;
        switch (s.toLowerCase().replace("-", "_").replace(" ", "_")) {
            case "top_left": case "topleft": return TOP_LEFT;
            case "top": case "top_center": case "topcenter": return TOP_CENTER;
            case "top_right": case "topright": return TOP_RIGHT;
            case "left": case "center_left": case "centerleft": return CENTER_LEFT;
            case "center": case "middle": return CENTER;
            case "right": case "center_right": case "centerright": return CENTER_RIGHT;
            case "bottom_left": case "bottomleft": return BOTTOM_LEFT;
            case "bottom": case "bottom_center": case "bottomcenter": return BOTTOM_CENTER;
            case "bottom_right": case "bottomright": return BOTTOM_RIGHT;
            default: return CENTER;
        }
    }

    public String toJsonName() {
        return name().toLowerCase();
    }
}
