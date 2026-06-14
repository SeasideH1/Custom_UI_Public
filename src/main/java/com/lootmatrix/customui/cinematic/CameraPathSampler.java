package com.lootmatrix.customui.cinematic;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Shared path sampling logic used by both client playback and server-side chunk streaming.
 * <p>
 * Interpolation is now split into 4 independent channels per keyframe:
 * <ul>
 *   <li><b>positionPathInterp</b> + <b>positionPathMerge</b>  — curve shape for position</li>
 *   <li><b>positionMoveEasing</b>  + <b>positionMoveMerge</b>  — speed ramp for position</li>
 *   <li><b>orientationPathInterp</b> + <b>orientationPathMerge</b> — curve shape for yaw/pitch</li>
 *   <li><b>orientationMoveEasing</b>  + <b>orientationMoveMerge</b>  — speed ramp for yaw/pitch</li>
 * </ul>
 * A channel is "merged" (uses 4-point spline with neighbour control points) only when
 * the channel's merge flag is {@code true} <b>and</b> the current and next keyframe share
 * the same interpolation mode for that channel. Otherwise it falls back to 2-point linear.
 */
public final class CameraPathSampler {

    private CameraPathSampler() {}

    public static Sample sampleExact(CameraPath path, int keyframeIndex, Vec3 playerOrigin) {
        CameraKeyframe keyframe = path.getKeyframes().get(keyframeIndex);
        Vec3 position = resolvePosition(keyframe, playerOrigin);
        Orientation orientation = resolveOrientation(keyframe, position, playerOrigin);
        return new Sample(
                position,
                orientation.yaw,
                orientation.pitch,
                keyframe.roll,
                keyframe.fov,
                keyframe.hideHud,
                keyframe.nightVision,
                keyframe.showSelf,
                keyframe.sendChunks,
                keyframe.sendChunksRadius
        );
    }

    public static Sample sampleSegment(CameraPath path, int keyframeIndex, float rawT, Vec3 playerOrigin) {
        List<CameraKeyframe> keyframes = path.getKeyframes();
        int nextIndex = nextIndex(path, keyframeIndex);
        if (nextIndex < 0) {
            return sampleExact(path, keyframeIndex, playerOrigin);
        }

        CameraKeyframe current = keyframes.get(keyframeIndex);
        CameraKeyframe next = keyframes.get(nextIndex);

        // ---- Determine merge state per channel ----
        boolean posPathMerged = canMergeInterp(current.positionPathMerge,
                current.positionPathInterp, next.positionPathInterp);
        boolean posMoveMerged = canMergeEasing(current.positionMoveMerge,
                current.positionMoveEasing, next.positionMoveEasing);
        boolean oriPathMerged = canMergeInterp(current.orientationPathMerge,
                current.orientationPathInterp, next.orientationPathInterp);
        boolean oriMoveMerged = canMergeEasing(current.orientationMoveMerge,
                current.orientationMoveEasing, next.orientationMoveEasing);

        // ---- Resolve surrounding control-point indices ----
        int prevIndexPos = previousControlIndex(path, keyframeIndex, posPathMerged);
        int nextNextIndexPos = nextControlIndex(path, keyframeIndex, nextIndex, posPathMerged);
        int prevIndexOri = previousControlIndex(path, keyframeIndex, oriPathMerged);
        int nextNextIndexOri = nextControlIndex(path, keyframeIndex, nextIndex, oriPathMerged);

        // ---- Resolve positions ----
        Vec3 currentPos = resolvePosition(current, playerOrigin);
        Vec3 nextPos = resolvePosition(next, playerOrigin);

        // ---- Position channel ----
        float posT = clamp01(rawT);
        // Apply position move easing
        posT = posMoveMerged
                ? CameraInterpolation.applyEasing(current.positionMoveEasing, posT)
                : (current.positionMoveEasing != CameraKeyframe.EasingType.NONE
                        ? CameraInterpolation.applyEasing(current.positionMoveEasing, posT)
                        : posT);

        Vec3 prevPosCtrl = resolvePosition(keyframes.get(prevIndexPos), playerOrigin);
        Vec3 nextNextPosCtrl = resolvePosition(keyframes.get(nextNextIndexPos), playerOrigin);
        Vec3 cameraPos = interpolatePosition(current.positionPathInterp, posPathMerged,
                prevPosCtrl, currentPos, nextPos, nextNextPosCtrl, posT);

        // ---- Orientation channel ----
        float oriT = clamp01(rawT);
        // Apply orientation move easing
        oriT = oriMoveMerged
                ? CameraInterpolation.applyEasing(current.orientationMoveEasing, oriT)
                : (current.orientationMoveEasing != CameraKeyframe.EasingType.NONE
                        ? CameraInterpolation.applyEasing(current.orientationMoveEasing, oriT)
                        : oriT);

        // Resolve orientations at control points using their resolved positions
        Vec3 prevPosOri = resolvePosition(keyframes.get(prevIndexOri), playerOrigin);
        Vec3 nextNextPosOri = resolvePosition(keyframes.get(nextNextIndexOri), playerOrigin);
        Orientation o0 = resolveOrientation(keyframes.get(prevIndexOri), prevPosOri, playerOrigin);
        Orientation o1 = resolveOrientation(current, currentPos, playerOrigin);
        Orientation o2 = resolveOrientation(next, nextPos, playerOrigin);
        Orientation o3 = resolveOrientation(keyframes.get(nextNextIndexOri), nextNextPosOri, playerOrigin);

        float yaw = interpolateAngle(current.orientationPathInterp, oriPathMerged,
                o0.yaw, o1.yaw, o2.yaw, o3.yaw, oriT);
        float pitch = interpolateAngle(current.orientationPathInterp, oriPathMerged,
                o0.pitch, o1.pitch, o2.pitch, o3.pitch, oriT);

        // Roll / FOV follow the orientation path channel
        float roll = interpolateScalar(current.orientationPathInterp, oriPathMerged,
                keyframes.get(prevIndexOri).roll, current.roll, next.roll,
                keyframes.get(nextNextIndexOri).roll, oriT);
        float fov = interpolateScalar(current.orientationPathInterp, oriPathMerged,
                keyframes.get(prevIndexOri).fov, current.fov, next.fov,
                keyframes.get(nextNextIndexOri).fov, oriT);

        return new Sample(
                cameraPos,
                yaw,
                pitch,
                roll,
                fov,
                current.hideHud,
                current.nightVision,
                current.showSelf,
                current.sendChunks,
                current.sendChunksRadius
        );
    }

    // ==================== Merge checks ====================

    /**
     * A position/orientation path channel is merged when merge is requested AND
     * both keyframes use the same InterpolationMode.
     */
    private static boolean canMergeInterp(boolean mergeFlag,
                                          CameraKeyframe.InterpolationMode current,
                                          CameraKeyframe.InterpolationMode next) {
        return mergeFlag && current == next;
    }

    /**
     * An easing channel is merged when merge is requested AND both keyframes
     * use the same EasingType.
     */
    private static boolean canMergeEasing(boolean mergeFlag,
                                          CameraKeyframe.EasingType current,
                                          CameraKeyframe.EasingType next) {
        return mergeFlag && current == next;
    }

    // ==================== Public helpers ====================

    public static Vec3 resolvePosition(CameraKeyframe keyframe, Vec3 playerOrigin) {
        return keyframe.absolutePosition ? keyframe.position : playerOrigin.add(keyframe.position);
    }

    public static int nextIndex(CameraPath path, int index) {
        int next = index + 1;
        if (next < path.getKeyframes().size()) {
            return next;
        }
        return path.isLoop() && !path.getKeyframes().isEmpty() ? 0 : -1;
    }

    // ==================== Control point helpers ====================

    private static int previousIndex(CameraPath path, int index) {
        int prev = index - 1;
        if (prev >= 0) {
            return prev;
        }
        return path.isLoop() && !path.getKeyframes().isEmpty() ? path.getKeyframes().size() - 1 : -1;
    }

    private static int previousControlIndex(CameraPath path, int index, boolean merged) {
        if (!merged) return index;
        int prev = previousIndex(path, index);
        return prev >= 0 ? prev : index;
    }

    private static int nextControlIndex(CameraPath path, int index, int nextIndex, boolean merged) {
        if (!merged) return nextIndex;
        int nextNext = nextIndex(path, nextIndex);
        return nextNext >= 0 ? nextNext : nextIndex;
    }

    // ==================== Interpolation ====================

    private static Vec3 interpolatePosition(CameraKeyframe.InterpolationMode mode, boolean merged,
                                            Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
        if (mode == CameraKeyframe.InterpolationMode.INSTANT) {
            return p1;
        }
        if (!merged) {
            return CameraInterpolation.lerpPosition(p1, p2, t);
        }
        return switch (mode) {
            case LINEAR -> CameraInterpolation.lerpPosition(p1, p2, t);
            case CATMULL_ROM -> CameraInterpolation.catmullRom(p0, p1, p2, p3, t);
            case CENTRIPETAL_CATMULL_ROM -> CameraInterpolation.centripetalCatmullRom(p0, p1, p2, p3, t);
            case HERMITE -> CameraInterpolation.hermite(p0, p1, p2, p3, t);
            case CUBIC_BEZIER -> CameraInterpolation.cubicBezierAuto(p0, p1, p2, p3, t);
            case INSTANT -> p1;
        };
    }

    private static float interpolateAngle(CameraKeyframe.InterpolationMode mode, boolean merged,
                                          float a0, float a1, float a2, float a3, float t) {
        if (mode == CameraKeyframe.InterpolationMode.INSTANT) {
            return a1;
        }
        if (!merged) {
            return CameraInterpolation.lerpAngle(a1, a2, t);
        }
        return switch (mode) {
            case LINEAR -> CameraInterpolation.lerpAngle(a1, a2, t);
            case CATMULL_ROM, CENTRIPETAL_CATMULL_ROM -> CameraInterpolation.catmullRomAngle(a0, a1, a2, a3, t);
            case HERMITE -> CameraInterpolation.hermiteAngle(a0, a1, a2, a3, t);
            case CUBIC_BEZIER -> CameraInterpolation.cubicBezierAngle(a0, a1, a2, a3, t);
            case INSTANT -> a1;
        };
    }

    private static float interpolateScalar(CameraKeyframe.InterpolationMode mode, boolean merged,
                                           float s0, float s1, float s2, float s3, float t) {
        if (mode == CameraKeyframe.InterpolationMode.INSTANT) {
            return s1;
        }
        if (!merged) {
            return s1 + (s2 - s1) * t;
        }
        return switch (mode) {
            case LINEAR -> s1 + (s2 - s1) * t;
            case CATMULL_ROM, CENTRIPETAL_CATMULL_ROM -> CameraInterpolation.catmullRomScalar(s0, s1, s2, s3, t);
            case HERMITE -> CameraInterpolation.hermiteScalar(s0, s1, s2, s3, t);
            case CUBIC_BEZIER -> CameraInterpolation.cubicBezierScalar(s0, s1, s2, s3, t);
            case INSTANT -> s1;
        };
    }

    // ==================== Orientation ====================

    private static Orientation resolveOrientation(CameraKeyframe keyframe, Vec3 cameraPosition, Vec3 playerOrigin) {
        if (keyframe.lookAt != null) {
            Vec3 target = keyframe.absolutePosition ? keyframe.lookAt : playerOrigin.add(keyframe.lookAt);
            return computeLookAtAngles(cameraPosition, target);
        }
        return new Orientation(keyframe.yaw, keyframe.pitch);
    }

    private static Orientation computeLookAtAngles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDist)));
        return new Orientation(yaw, pitch);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public record Sample(Vec3 position, float yaw, float pitch, float roll, float fov,
                         boolean hideHud, boolean nightVision, boolean showSelf,
                         boolean sendChunks, int sendChunksRadius) {
    }

    private record Orientation(float yaw, float pitch) {
    }
}
