package com.lootmatrix.customui.hud;

import java.util.List;

/**
 * Compiled single-property animation track: sorted (tick, value, easing) triples
 * extracted from an element's keyframe list. Evaluation is allocation-free.
 *
 * Segment semantics: the easing stored on the destination keyframe shapes the
 * segment leading into it. Two keyframes on the same tick yield an instant jump.
 */
public final class HudTrack {

    private final int[] ticks;
    private final float[] values;
    private final byte[] easings;

    private HudTrack(int[] ticks, float[] values, byte[] easings) {
        this.ticks = ticks;
        this.values = values;
        this.easings = easings;
    }

    public boolean isEmpty() {
        return ticks.length == 0;
    }

    public int keyframeCount() {
        return ticks.length;
    }

    public int lastTick() {
        return ticks.length == 0 ? 0 : ticks[ticks.length - 1];
    }

    /**
     * Evaluate the track at a (fractional) tick time.
     *
     * @param time     current time in ticks (partialTick already folded in)
     * @param fallback value to use when the track has no keyframes
     */
    public float evaluate(float time, float fallback) {
        int n = ticks.length;
        if (n == 0) return fallback;
        if (time <= ticks[0]) return values[0];
        if (time >= ticks[n - 1]) return values[n - 1];

        // Binary search for the segment: ticks[lo] <= time < ticks[hi]
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ticks[mid] <= time) lo = mid; else hi = mid;
        }
        // Same-tick double keyframe: zero-length segment -> instant change handled
        // by the comparisons above (time >= ticks[hi] returns the later value).
        int span = ticks[hi] - ticks[lo];
        if (span <= 0) return values[hi];

        float t = (time - ticks[lo]) / span;
        float eased = HudEasing.byId(easings[hi]).apply(t);
        return values[lo] + (values[hi] - values[lo]) * eased;
    }

    /**
     * Compile one property track from a tick-sorted keyframe list.
     * Keyframes that do not define the property are skipped.
     */
    public static HudTrack compile(List<HudKeyframe> keyframes, int prop) {
        int count = 0;
        for (HudKeyframe kf : keyframes) {
            if (kf.get(prop) != null) count++;
        }
        if (count == 0) return EMPTY;

        int[] ticks = new int[count];
        float[] values = new float[count];
        byte[] easings = new byte[count];
        int i = 0;
        for (HudKeyframe kf : keyframes) {
            Float v = kf.get(prop);
            if (v == null) continue;
            ticks[i] = kf.tick;
            values[i] = v;
            easings[i] = (byte) kf.easingFor(prop).ordinal();
            i++;
        }
        return new HudTrack(ticks, values, easings);
    }

    public static final HudTrack EMPTY = new HudTrack(new int[0], new float[0], new byte[0]);
}
