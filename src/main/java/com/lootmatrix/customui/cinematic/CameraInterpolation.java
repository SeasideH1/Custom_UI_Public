package com.lootmatrix.customui.cinematic;

import net.minecraft.world.phys.Vec3;

/**
 * Utility for interpolating between camera keyframes.
 * Supports easing, line interpolation, and multiple path spline modes.
 */
public final class CameraInterpolation {

    private CameraInterpolation() {}

    // ==================== Easing Functions ====================

    public static float applyEasing(CameraKeyframe.EasingType easing, float t) {
        return switch (easing) {
            case NONE -> t;
            case EASE_IN -> t * t;
            case EASE_OUT -> 1f - (1f - t) * (1f - t);
            case EASE_IN_OUT -> t < 0.5f ? 2f * t * t : 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f;
            case EASE_IN_CUBIC -> t * t * t;
            case EASE_OUT_CUBIC -> 1f - (1f - t) * (1f - t) * (1f - t);
            case EASE_IN_OUT_CUBIC -> t < 0.5f ? 4f * t * t * t : 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f;
            case EASE_IN_BACK -> {
                float c1 = 1.70158f;
                float c3 = c1 + 1f;
                yield c3 * t * t * t - c1 * t * t;
            }
            case EASE_OUT_BACK -> {
                float c1 = 1.70158f;
                float c3 = c1 + 1f;
                float tm1 = t - 1f;
                yield 1f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1;
            }
            case EASE_IN_OUT_BACK -> {
                float c1 = 1.70158f;
                float c2 = c1 * 1.525f;
                yield t < 0.5f
                        ? ((2f * t) * (2f * t) * ((c2 + 1f) * 2f * t - c2)) / 2f
                        : ((2f * t - 2f) * (2f * t - 2f) * ((c2 + 1f) * (t * 2f - 2f) + c2) + 2f) / 2f;
            }
        };
    }

    // ==================== Position Interpolation ====================

    public static Vec3 lerpPosition(Vec3 a, Vec3 b, float t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t
        );
    }

    /**
     * Catmull-Rom spline interpolation through 4 points.
     * @param p0 point before the segment start
     * @param p1 segment start
     * @param p2 segment end
     * @param p3 point after the segment end
     * @param t  0..1 within the p1→p2 segment
     */
    public static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return new Vec3(
                catmullRom1D(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                catmullRom1D(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                catmullRom1D(p0.z, p1.z, p2.z, p3.z, t, t2, t3)
        );
    }

    private static double catmullRom1D(double p0, double p1, double p2, double p3,
                                        float t, float t2, float t3) {
        return 0.5 * ((2.0 * p1) +
                (-p0 + p2) * t +
                (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    public static float catmullRomScalar(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (float) catmullRom1D(p0, p1, p2, p3, t, t2, t3);
    }

    public static Vec3 centripetalCatmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        double t0 = 0.0;
        double t1 = t0 + centripetalDistance(p0, p1);
        double t2 = t1 + centripetalDistance(p1, p2);
        double t3 = t2 + centripetalDistance(p2, p3);
        double u = t1 + (t2 - t1) * clamp01(t);

        Vec3 a1 = blend(p0, p1, t0, t1, u);
        Vec3 a2 = blend(p1, p2, t1, t2, u);
        Vec3 a3 = blend(p2, p3, t2, t3, u);
        Vec3 b1 = blend(a1, a2, t0, t2, u);
        Vec3 b2 = blend(a2, a3, t1, t3, u);
        return blend(b1, b2, t1, t2, u);
    }

    public static Vec3 hermite(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        Vec3 m1 = p2.subtract(p0).scale(0.5);
        Vec3 m2 = p3.subtract(p1).scale(0.5);
        return hermiteSegment(p1, p2, m1, m2, t);
    }

    public static Vec3 cubicBezierAuto(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        Vec3 c1 = p1.add(p2.subtract(p0).scale(0.25));
        Vec3 c2 = p2.subtract(p3.subtract(p1).scale(0.25));
        return cubicBezier(p1, c1, c2, p2, t);
    }

    public static Vec3 cubicBezier(Vec3 p0, Vec3 c1, Vec3 c2, Vec3 p3, float t) {
        float clamped = clamp01(t);
        float oneMinusT = 1f - clamped;
        double a = oneMinusT * oneMinusT * oneMinusT;
        double b = 3.0 * oneMinusT * oneMinusT * clamped;
        double c = 3.0 * oneMinusT * clamped * clamped;
        double d = clamped * clamped * clamped;
        return new Vec3(
                p0.x * a + c1.x * b + c2.x * c + p3.x * d,
                p0.y * a + c1.y * b + c2.y * c + p3.y * d,
                p0.z * a + c1.z * b + c2.z * c + p3.z * d
        );
    }

    public static float hermiteAngle(float a0, float a1, float a2, float a3, float t) {
        float u0 = a1 + unwrapDelta(a0 - a1);
        float u2 = a1 + unwrapDelta(a2 - a1);
        float u3 = u2 + unwrapDelta(a3 - u2);
        float m1 = (u2 - u0) * 0.5f;
        float m2 = (u3 - a1) * 0.5f;
        return normalizeAngle(hermite1D(a1, u2, m1, m2, clamp01(t)));
    }

    public static float cubicBezierAngle(float a0, float a1, float a2, float a3, float t) {
        float u0 = a1 + unwrapDelta(a0 - a1);
        float u2 = a1 + unwrapDelta(a2 - a1);
        float u3 = u2 + unwrapDelta(a3 - u2);
        float c1 = a1 + (u2 - u0) * 0.25f;
        float c2 = u2 - (u3 - a1) * 0.25f;
        return normalizeAngle(cubicBezier1D(a1, c1, c2, u2, clamp01(t)));
    }

    // ==================== Angle Interpolation ====================

    /** Interpolate an angle (degrees) with shortest-path wrapping. */
    public static float lerpAngle(float a, float b, float t) {
        float diff = ((b - a) % 360f + 540f) % 360f - 180f;
        return a + diff * t;
    }

    /** Catmull-Rom for a single angle (degrees), with wrapping. */
    public static float catmullRomAngle(float a0, float a1, float a2, float a3, float t) {
        // Unwrap angles relative to a1
        float u0 = a1 + unwrapDelta(a0 - a1);
        float u2 = a1 + unwrapDelta(a2 - a1);
        float u3 = a1 + unwrapDelta(a3 - a1);
        float t2 = t * t;
        float t3 = t2 * t;
        float result = (float) (0.5 * ((2.0 * a1) +
                (-u0 + u2) * t +
                (2.0 * u0 - 5.0 * a1 + 4.0 * u2 - u3) * t2 +
                (-u0 + 3.0 * a1 - 3.0 * u2 + u3) * t3));
        return normalizeAngle(result);
    }

    private static float unwrapDelta(float delta) {
        return ((delta % 360f) + 540f) % 360f - 180f;
    }

    // ==================== FOV Interpolation ====================

    public static float lerpFov(float start, float end, float t) {
        return start + (end - start) * t;
    }

    public static float hermiteScalar(float p0, float p1, float p2, float p3, float t) {
        float m1 = (p2 - p0) * 0.5f;
        float m2 = (p3 - p1) * 0.5f;
        return hermite1D(p1, p2, m1, m2, clamp01(t));
    }

    public static float cubicBezierScalar(float p0, float p1, float p2, float p3, float t) {
        float c1 = p1 + (p2 - p0) * 0.25f;
        float c2 = p2 - (p3 - p1) * 0.25f;
        return cubicBezier1D(p1, c1, c2, p2, clamp01(t));
    }

    private static double centripetalDistance(Vec3 a, Vec3 b) {
        return Math.max(Math.pow(a.distanceTo(b), 0.5), 1.0e-4);
    }

    private static Vec3 blend(Vec3 a, Vec3 b, double ta, double tb, double t) {
        double denom = Math.max(tb - ta, 1.0e-6);
        double wa = (tb - t) / denom;
        double wb = (t - ta) / denom;
        return new Vec3(
                a.x * wa + b.x * wb,
                a.y * wa + b.y * wb,
                a.z * wa + b.z * wb
        );
    }

    private static Vec3 hermiteSegment(Vec3 p1, Vec3 p2, Vec3 m1, Vec3 m2, float t) {
        float clamped = clamp01(t);
        float t2 = clamped * clamped;
        float t3 = t2 * clamped;
        float h00 = 2f * t3 - 3f * t2 + 1f;
        float h10 = t3 - 2f * t2 + clamped;
        float h01 = -2f * t3 + 3f * t2;
        float h11 = t3 - t2;
        return new Vec3(
                p1.x * h00 + m1.x * h10 + p2.x * h01 + m2.x * h11,
                p1.y * h00 + m1.y * h10 + p2.y * h01 + m2.y * h11,
                p1.z * h00 + m1.z * h10 + p2.z * h01 + m2.z * h11
        );
    }

    private static float hermite1D(float p1, float p2, float m1, float m2, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (2f * t3 - 3f * t2 + 1f) * p1
                + (t3 - 2f * t2 + t) * m1
                + (-2f * t3 + 3f * t2) * p2
                + (t3 - t2) * m2;
    }

    private static float cubicBezier1D(float p0, float c1, float c2, float p3, float t) {
        float oneMinusT = 1f - t;
        return oneMinusT * oneMinusT * oneMinusT * p0
                + 3f * oneMinusT * oneMinusT * t * c1
                + 3f * oneMinusT * t * t * c2
                + t * t * t * p3;
    }

    private static float normalizeAngle(float angle) {
        return ((angle % 360f) + 360f) % 360f;
    }

    private static float clamp01(float t) {
        return Math.max(0f, Math.min(1f, t));
    }
}
