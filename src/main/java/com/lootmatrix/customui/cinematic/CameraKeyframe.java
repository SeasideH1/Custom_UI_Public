package com.lootmatrix.customui.cinematic;

import net.minecraft.world.phys.Vec3;

/**
 * A single keyframe (node) on a cinematic camera path.
 * Defines where the camera is, where it looks, its orientation, FOV,
 * and various flags that control playback behaviour.
 * <p>
 * Interpolation is split into 4 independent channels, each with its own
 * curve/easing type and merge flag:
 * <ul>
 *   <li><b>positionPathInterp</b> – curve shape for camera x/y/z along the path</li>
 *   <li><b>positionMoveEasing</b> – easing (speed ramp) for camera x/y/z within the segment</li>
 *   <li><b>orientationPathInterp</b> – curve shape for yaw/pitch along the path</li>
 *   <li><b>orientationMoveEasing</b> – easing (speed ramp) for yaw/pitch within the segment</li>
 * </ul>
 * When a channel's merge flag is {@code true} <b>and</b> the next keyframe's
 * corresponding interpolation mode is identical, the two keyframes are merged
 * into a continuous spline span using surrounding control points. Otherwise
 * the channel falls back to simple 2-point linear interpolation.
 */
public class CameraKeyframe {

    /** World position of the camera. */
    public final Vec3 position;

    /** World position the camera looks at (used to compute yaw/pitch). Nullable. */
    public final Vec3 lookAt;

    /** Explicit yaw (degrees, 0=south, 90=west). Only used when lookAt is null. */
    public final float yaw;

    /** Explicit pitch (degrees, negative=up). Only used when lookAt is null. */
    public final float pitch;

    /** Camera roll / tilt (degrees, positive=clockwise when facing forward). */
    public final float roll;

    // ==================== 4-Channel Interpolation ====================

    /** Curve shape for camera position (x/y/z) along the path. */
    public final InterpolationMode positionPathInterp;
    /** If true, attempt to merge position path curve with the next keyframe. */
    public final boolean positionPathMerge;

    /** Easing function for camera position within the segment. */
    public final EasingType positionMoveEasing;
    /** If true, attempt to merge position easing with the next keyframe. */
    public final boolean positionMoveMerge;

    /** Curve shape for camera orientation (yaw/pitch) along the path. */
    public final InterpolationMode orientationPathInterp;
    /** If true, attempt to merge orientation path curve with the next keyframe. */
    public final boolean orientationPathMerge;

    /** Easing function for camera orientation within the segment. */
    public final EasingType orientationMoveEasing;
    /** If true, attempt to merge orientation easing with the next keyframe. */
    public final boolean orientationMoveMerge;

    // ==================== Other Parameters ====================

    /** FOV at this keyframe. */
    public final float fov;

    /**
     * Duration of the outgoing segment in ticks.
     * 0 means this keyframe only participates as a control/end point and does not create a hold segment.
     */
    public final int durationTicks;

    /** If true, camera is placed at absolute world coordinates. */
    public final boolean absolutePosition;

    /** If true, apply night vision effect to the player during this segment. */
    public final boolean nightVision;

    /** If true, the server sends chunk data around the camera position to the client. */
    public final boolean sendChunks;

    /** If true, hide the player's HUD during this segment. */
    public final boolean hideHud;

    /** If true, render the player's own entity at their actual position during this segment. */
    public final boolean showSelf;

    /** Radius (in chunks) for sending chunk data around the camera position. */
    public final int sendChunksRadius;

    public CameraKeyframe(Vec3 position, Vec3 lookAt,
                          float yaw, float pitch, float roll,
                          InterpolationMode positionPathInterp, boolean positionPathMerge,
                          EasingType positionMoveEasing, boolean positionMoveMerge,
                          InterpolationMode orientationPathInterp, boolean orientationPathMerge,
                          EasingType orientationMoveEasing, boolean orientationMoveMerge,
                          float fov, int durationTicks,
                          boolean absolutePosition, boolean nightVision,
                          boolean sendChunks, boolean hideHud,
                          boolean showSelf, int sendChunksRadius) {
        this.position = position;
        this.lookAt = lookAt;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.positionPathInterp = positionPathInterp;
        this.positionPathMerge = positionPathMerge;
        this.positionMoveEasing = positionMoveEasing;
        this.positionMoveMerge = positionMoveMerge;
        this.orientationPathInterp = orientationPathInterp;
        this.orientationPathMerge = orientationPathMerge;
        this.orientationMoveEasing = orientationMoveEasing;
        this.orientationMoveMerge = orientationMoveMerge;
        this.fov = fov;
        this.durationTicks = durationTicks;
        this.absolutePosition = absolutePosition;
        this.nightVision = nightVision;
        this.sendChunks = sendChunks;
        this.hideHud = hideHud;
        this.showSelf = showSelf;
        this.sendChunksRadius = sendChunksRadius;
    }

    /** Interpolation method between keyframes (curve shape). */
    public enum InterpolationMode {
        LINEAR,
        CATMULL_ROM,
        CUBIC_BEZIER,
        INSTANT,
        CENTRIPETAL_CATMULL_ROM,
        HERMITE
    }

    /** Easing functions for time remapping. */
    public enum EasingType {
        NONE,
        EASE_IN,
        EASE_OUT,
        EASE_IN_OUT,
        EASE_IN_CUBIC,
        EASE_OUT_CUBIC,
        EASE_IN_OUT_CUBIC,
        EASE_IN_BACK,
        EASE_OUT_BACK,
        EASE_IN_OUT_BACK
    }
}
