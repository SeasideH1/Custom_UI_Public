package com.lootmatrix.customui.client;

import java.util.ArrayList;
import java.util.List;

public final class CursorEffectMath {

    private CursorEffectMath() {
    }

    public static float waveRadius(float baseRadius, float angle, float progress,
                                   float amplitude, int frequency, int seed) {
        float p = clamp01(progress);
        if (p >= 1f || amplitude <= 0f) {
            return baseRadius;
        }
        int freq = Math.max(1, frequency);
        float phase = p * 8f + seed * 0.01f;
        float envelope = (1f - p) * (0.45f + 0.55f * (float) Math.sin(p * Math.PI));
        float wave = ((float) Math.sin(angle * freq + phase)
                + 0.36f * (float) Math.sin(angle * (freq + 5) - phase * 0.7f)) / 1.36f;
        return baseRadius + wave * amplitude * envelope;
    }

    public static List<ParticleSeed> createRippleParticles(float centerX, float centerY, int requestedCount,
                                                           int maxCount, float baseSpeed, long lifetimeMs,
                                                           long startTimeMs, int seed) {
        int count = Math.max(0, Math.min(requestedCount, maxCount));
        ArrayList<ParticleSeed> result = new ArrayList<>(count);
        if (count == 0) {
            return result;
        }

        for (int i = 0; i < count; i++) {
            float jitter = (seededUnit(seed, i * 9 + 1) - 0.5f) * 0.18f;
            float angle = (float) (Math.PI * 2.0 * i / count) + jitter;
            float startRadius = 12f + seededUnit(seed, i * 5 + 2) * 6f;
            float speed = baseSpeed * (0.55f + seededUnit(seed, i * 13 + 3) * 0.9f);
            float tangent = (seededUnit(seed, i * 17 + 4) - 0.5f) * 18f;
            float size = 1.1f + seededUnit(seed, i * 21 + 5) * 2.2f;
            long life = lifetimeMs + Math.round(seededUnit(seed, i * 29 + 6) * lifetimeMs * 0.58f);
            float x = centerX + (float) Math.cos(angle) * startRadius;
            float y = centerY + (float) Math.sin(angle) * startRadius;
            result.add(new ParticleSeed(x, y, angle, speed, tangent, size, life, startTimeMs));
        }
        return result;
    }

    public static float easeOutCubic(float value) {
        float t = clamp01(value);
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    public static float smoothstep(float value) {
        float t = clamp01(value);
        return t * t * (3f - 2f * t);
    }

    public static float transitionToward(float current, float target, float elapsedMs,
                                         float riseMs, float fallMs) {
        float timeConstant = target > current ? Math.max(1f, riseMs) : Math.max(1f, fallMs);
        float factor = 1f - (float) Math.exp(-Math.max(0f, elapsedMs) / timeConstant);
        return clamp01(current + (target - current) * factor);
    }

    public static float adaptiveQuality(float frameMs) {
        if (frameMs <= 8.5f) {
            return 1.5f;
        }
        if (frameMs <= 12.5f) {
            return 1.28f;
        }
        if (frameMs <= 17.5f) {
            return 1.0f;
        }
        if (frameMs <= 26f) {
            return 0.86f;
        }
        return 0.68f;
    }

    public static int adaptiveSegments(int baseSegments, float quality, int minSegments, int maxSegments) {
        int min = Math.max(3, minSegments);
        int max = Math.max(min, maxSegments);
        int scaled = Math.round(baseSegments * Math.max(0.1f, quality));
        return Math.max(min, Math.min(max, scaled));
    }

    public static float seededUnit(int seed, int salt) {
        double x = Math.sin((seed * 31.0 + salt * 17.0) * 12.9898) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    public static float clamp01(float value) {
        if (value <= 0f) {
            return 0f;
        }
        if (value >= 1f) {
            return 1f;
        }
        return value;
    }

    public record ParticleSeed(float x, float y, float angle, float speed, float tangent,
                               float size, long lifetimeMs, long startTimeMs) {
    }
}
