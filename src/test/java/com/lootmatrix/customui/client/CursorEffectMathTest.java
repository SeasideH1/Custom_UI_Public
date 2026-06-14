package com.lootmatrix.customui.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorEffectMathTest {

    @Test
    void waveRadiusStaysWithinAmplitudeBounds() {
        float baseRadius = 44f;
        float amplitude = 4f;

        for (int i = 0; i < 128; i++) {
            float angle = (float) (Math.PI * 2.0 * i / 128.0);
            float radius = CursorEffectMath.waveRadius(baseRadius, angle, 0.5f, amplitude, 6, 12345);

            assertTrue(radius >= baseRadius - amplitude - 0.001f);
            assertTrue(radius <= baseRadius + amplitude + 0.001f);
        }
    }

    @Test
    void waveRadiusReturnsBaseAtEndOfRipple() {
        float radius = CursorEffectMath.waveRadius(44f, 1.25f, 1f, 4f, 6, 12345);

        assertEquals(44f, radius, 0.001f);
    }

    @Test
    void generatedParticlesRespectCountLimitAndStartNearRing() {
        List<CursorEffectMath.ParticleSeed> particles = CursorEffectMath.createRippleParticles(
                100f, 200f, 30, 12, 46f, 520L, 1000L, 12345
        );

        assertEquals(12, particles.size());
        for (CursorEffectMath.ParticleSeed particle : particles) {
            double distance = Math.hypot(particle.x() - 100f, particle.y() - 200f);
            assertTrue(distance >= 12.0);
            assertTrue(distance <= 18.1);
            assertTrue(particle.speed() >= 46f * 0.55f);
            assertTrue(particle.speed() <= 46f * 1.45f);
            assertTrue(particle.lifetimeMs() >= 520L);
        }
    }

    @Test
    void transitionApproachesTargetWithoutJumping() {
        float rising = CursorEffectMath.transitionToward(0f, 1f, 16f, 70f, 180f);
        float falling = CursorEffectMath.transitionToward(1f, 0f, 16f, 70f, 180f);

        assertTrue(rising > 0f);
        assertTrue(rising < 1f);
        assertTrue(falling > 0f);
        assertTrue(falling < 1f);
        assertTrue(rising > 1f - falling, "rise should be faster than fall with these time constants");
    }

    @Test
    void adaptiveQualityIncreasesForFastFramesAndDropsForSlowFrames() {
        float fast = CursorEffectMath.adaptiveQuality(6f);
        float normal = CursorEffectMath.adaptiveQuality(16.7f);
        float slow = CursorEffectMath.adaptiveQuality(40f);

        assertTrue(fast > normal);
        assertTrue(normal > slow);
        assertTrue(fast <= 1.6f);
        assertTrue(slow >= 0.65f);
    }

    @Test
    void adaptiveSegmentsAreClampedAndQualityScaled() {
        int low = CursorEffectMath.adaptiveSegments(96, 0.7f, 48, 160);
        int high = CursorEffectMath.adaptiveSegments(96, 1.5f, 48, 160);

        assertTrue(low >= 48);
        assertTrue(high <= 160);
        assertTrue(high > low);
    }
}
