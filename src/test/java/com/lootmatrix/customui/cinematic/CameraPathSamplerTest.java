package com.lootmatrix.customui.cinematic;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPathSamplerTest {

    @Test
    @DisplayName("mergeWithNext should use adjacent keyframes as spline controls even when surrounding segments are not merged")
    void mergeWithNextUsesNeighborControlPointsWithoutChainingMergeFlags() {
        CameraPath upwardControls = new CameraPath("upward", List.of(
                keyframe(new Vec3(0.0, 80.0, 0.0), false),
                keyframe(new Vec3(10.0, 0.0, 0.0), true),
                keyframe(new Vec3(20.0, 0.0, 0.0), false),
                keyframe(new Vec3(30.0, 80.0, 0.0), false)
        ), false);
        CameraPath downwardControls = new CameraPath("downward", List.of(
                keyframe(new Vec3(0.0, -80.0, 0.0), false),
                keyframe(new Vec3(10.0, 0.0, 0.0), true),
                keyframe(new Vec3(20.0, 0.0, 0.0), false),
                keyframe(new Vec3(30.0, -80.0, 0.0), false)
        ), false);

        CameraPathSampler.Sample upwardSample = CameraPathSampler.sampleSegment(upwardControls, 1, 0.25f, Vec3.ZERO);
        CameraPathSampler.Sample downwardSample = CameraPathSampler.sampleSegment(downwardControls, 1, 0.25f, Vec3.ZERO);

        assertNotEquals(upwardSample.position().y, downwardSample.position().y,
                "Changing neighboring control points should change the merged sample");
        assertTrue(upwardSample.position().y * downwardSample.position().y < 0.0,
                "Opposite neighboring controls should bend the merged segment in opposite directions");
    }

    private static CameraKeyframe keyframe(Vec3 position, boolean mergeWithNext) {
        return new CameraKeyframe(
                position,
                null,
                0.0f,
                0.0f,
                0.0f,
                CameraKeyframe.InterpolationMode.CATMULL_ROM,
                mergeWithNext,
                CameraKeyframe.EasingType.NONE,
                false,
                CameraKeyframe.InterpolationMode.LINEAR,
                false,
                CameraKeyframe.EasingType.NONE,
                false,
                70.0f,
                20,
                true,
                false,
                false,
                false,
                false,
                0
        );
    }
}
