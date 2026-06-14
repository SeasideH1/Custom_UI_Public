package com.lootmatrix.customui.cinematic;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a complete cinematic camera path consisting of ordered keyframes.
 * Can be serialized to/from a network buffer for server→client transmission.
 * <p>
 * All interpolation control is now per-keyframe (4 independent channels).
 * Global easing overrides have been removed.
 */
public class CameraPath {

    private final String id;
    private final List<CameraKeyframe> keyframes;
    private final boolean loop;

    public CameraPath(String id, List<CameraKeyframe> keyframes, boolean loop) {
        this.id = id;
        this.keyframes = Collections.unmodifiableList(new ArrayList<>(keyframes));
        this.loop = loop;
    }

    public String getId() { return id; }
    public List<CameraKeyframe> getKeyframes() { return keyframes; }
    public boolean isLoop() { return loop; }

    /** Total duration in ticks across all playable segments. */
    public int getTotalDurationTicks() {
        int total = 0;
        for (CameraKeyframe kf : keyframes) {
            total += Math.max(kf.durationTicks, 0);
        }
        return total;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id, 256);
        buf.writeBoolean(loop);
        buf.writeVarInt(keyframes.size());
        for (CameraKeyframe kf : keyframes) {
            encodeKeyframe(buf, kf);
        }
    }

    public static CameraPath decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(256);
        boolean loop = buf.readBoolean();
        int count = buf.readVarInt();
        List<CameraKeyframe> keyframes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keyframes.add(decodeKeyframe(buf));
        }
        return new CameraPath(id, keyframes, loop);
    }

    private static void encodeKeyframe(FriendlyByteBuf buf, CameraKeyframe kf) {
        buf.writeDouble(kf.position.x);
        buf.writeDouble(kf.position.y);
        buf.writeDouble(kf.position.z);
        buf.writeBoolean(kf.lookAt != null);
        if (kf.lookAt != null) {
            buf.writeDouble(kf.lookAt.x);
            buf.writeDouble(kf.lookAt.y);
            buf.writeDouble(kf.lookAt.z);
        }
        buf.writeFloat(kf.yaw);
        buf.writeFloat(kf.pitch);
        buf.writeFloat(kf.roll);

        // 4-channel interpolation
        buf.writeVarInt(kf.positionPathInterp.ordinal());
        buf.writeBoolean(kf.positionPathMerge);
        buf.writeVarInt(kf.positionMoveEasing.ordinal());
        buf.writeBoolean(kf.positionMoveMerge);
        buf.writeVarInt(kf.orientationPathInterp.ordinal());
        buf.writeBoolean(kf.orientationPathMerge);
        buf.writeVarInt(kf.orientationMoveEasing.ordinal());
        buf.writeBoolean(kf.orientationMoveMerge);

        buf.writeFloat(kf.fov);
        buf.writeVarInt(Math.max(kf.durationTicks, 0));
        buf.writeBoolean(kf.absolutePosition);
        buf.writeBoolean(kf.nightVision);
        buf.writeBoolean(kf.sendChunks);
        buf.writeBoolean(kf.hideHud);
        buf.writeBoolean(kf.showSelf);
        buf.writeVarInt(Math.max(kf.sendChunksRadius, 0));
    }

    private static CameraKeyframe decodeKeyframe(FriendlyByteBuf buf) {
        Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 lookAt = null;
        if (buf.readBoolean()) {
            lookAt = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        float yaw = buf.readFloat();
        float pitch = buf.readFloat();
        float roll = buf.readFloat();

        // 4-channel interpolation
        CameraKeyframe.InterpolationMode positionPathInterp = CameraKeyframe.InterpolationMode.values()[
                buf.readVarInt() % CameraKeyframe.InterpolationMode.values().length];
        boolean positionPathMerge = buf.readBoolean();
        CameraKeyframe.EasingType positionMoveEasing = CameraKeyframe.EasingType.values()[
                buf.readVarInt() % CameraKeyframe.EasingType.values().length];
        boolean positionMoveMerge = buf.readBoolean();
        CameraKeyframe.InterpolationMode orientationPathInterp = CameraKeyframe.InterpolationMode.values()[
                buf.readVarInt() % CameraKeyframe.InterpolationMode.values().length];
        boolean orientationPathMerge = buf.readBoolean();
        CameraKeyframe.EasingType orientationMoveEasing = CameraKeyframe.EasingType.values()[
                buf.readVarInt() % CameraKeyframe.EasingType.values().length];
        boolean orientationMoveMerge = buf.readBoolean();

        float fov = buf.readFloat();
        int durationTicks = buf.readVarInt();
        boolean absolutePosition = buf.readBoolean();
        boolean nightVision = buf.readBoolean();
        boolean sendChunks = buf.readBoolean();
        boolean hideHud = buf.readBoolean();
        boolean showSelf = buf.readBoolean();
        int sendChunksRadius = buf.readVarInt();

        return new CameraKeyframe(position, lookAt, yaw, pitch, roll,
                positionPathInterp, positionPathMerge,
                positionMoveEasing, positionMoveMerge,
                orientationPathInterp, orientationPathMerge,
                orientationMoveEasing, orientationMoveMerge,
                fov, durationTicks, absolutePosition, nightVision, sendChunks, hideHud,
                showSelf, sendChunksRadius);
    }
}
