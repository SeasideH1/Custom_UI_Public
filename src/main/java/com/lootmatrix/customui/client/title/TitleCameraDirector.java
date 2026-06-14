package com.lootmatrix.customui.client.title;

import com.lootmatrix.customui.cinematic.CameraKeyframe;
import com.lootmatrix.customui.cinematic.CameraPath;
import com.lootmatrix.customui.cinematic.CameraPathSampler;
import net.minecraft.Util;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Camera choreography for the title scene, mirroring LabyMod's dynamic
 * background: an entrance flight out of the loading screen, an idle drift on
 * the main menu and eased transitions to a per-submenu anchor whenever the
 * screen changes.
 *
 * Time advances on wall-clock millis (50 ms = 1 keyframe tick) so motion is
 * frame-rate independent; all path math is delegated to the existing
 * {@link CameraPathSampler} (scene-local coordinates, origin {@link Vec3#ZERO}).
 */
@OnlyIn(Dist.CLIENT)
public final class TitleCameraDirector {

    private enum Mode { ENTRANCE, IDLE_PATH, TRANSITION, HOLD }

    private static final Vec3 ORIGIN = Vec3.ZERO;
    private static final String TITLE_ANCHOR = "title";

    private final TitleSceneAssets.SceneConfig config;

    private Mode mode = Mode.HOLD;
    @Nullable private CameraPath activePath;
    private long pathStartMillis;
    private String currentAnchor = TITLE_ANCHOR;
    /** Sample to ease back into IDLE/HOLD once a transition path completes. */
    @Nullable private CameraPathSampler.Sample lastSample;
    /** Anchor pose resolved once on settle; sway is applied on top per frame. */
    @Nullable private CameraPathSampler.Sample holdBase;

    public TitleCameraDirector(TitleSceneAssets.SceneConfig config) {
        this.config = config;
        if (config.entrance != null) {
            this.mode = Mode.ENTRANCE;
            this.activePath = config.entrance;
            this.pathStartMillis = Util.getMillis();
        } else {
            settleAt(TITLE_ANCHOR);
        }
    }

    /** Restarts the entrance flight (called when the loading overlay fades out). */
    public void beginEntrance() {
        if (config.entrance != null) {
            mode = Mode.ENTRANCE;
            activePath = config.entrance;
            pathStartMillis = Util.getMillis();
        }
    }

    /** Eases the camera to a submenu anchor; no-op when already there/heading there. */
    public void moveToAnchor(String anchorKey) {
        String resolved = config.anchors.containsKey(anchorKey) ? anchorKey : TITLE_ANCHOR;
        if (resolved.equals(currentAnchor)) {
            return; // covers ENTRANCE too: the entrance already flies to "title"
        }
        CameraKeyframe target = config.anchors.get(resolved);
        if (target == null) {
            return;
        }
        CameraPathSampler.Sample from = lastSample;
        currentAnchor = resolved;
        if (from == null) {
            settleAt(resolved);
            return;
        }
        CameraKeyframe start = transitionStart(from, config.transitionTicks);
        activePath = new CameraPath("customui:title_transition", List.of(start, target), false);
        mode = Mode.TRANSITION;
        pathStartMillis = Util.getMillis();
    }

    /** Samples the camera for this frame. Never returns null. */
    public CameraPathSampler.Sample sample() {
        long now = Util.getMillis();
        CameraPathSampler.Sample sample;
        switch (mode) {
            case ENTRANCE, TRANSITION -> {
                sample = samplePath(activePath, now, false);
                if (sample == null || pathFinished(activePath, now)) {
                    if (sample == null) {
                        sample = fallbackSample();
                    }
                    lastSample = sample;
                    settleAt(currentAnchor);
                }
            }
            case IDLE_PATH -> {
                sample = samplePath(activePath, now, true);
                if (sample == null) {
                    sample = fallbackSample();
                }
            }
            default -> sample = holdSample(now);
        }
        lastSample = sample;
        return sample;
    }

    // ==================== Internals ====================

    /** After a path ends: idle drift on the title anchor, static hold elsewhere. */
    private void settleAt(String anchorKey) {
        currentAnchor = anchorKey;
        CameraKeyframe anchor = config.anchors.get(anchorKey);
        holdBase = anchor != null
                ? CameraPathSampler.sampleExact(
                        new CameraPath("customui:title_hold", List.of(anchor), false), 0, ORIGIN)
                : null;
        if (TITLE_ANCHOR.equals(anchorKey) && config.idle != null) {
            mode = Mode.IDLE_PATH;
            activePath = config.idle;
            pathStartMillis = Util.getMillis();
        } else {
            mode = Mode.HOLD;
            activePath = null;
        }
    }

    @Nullable
    private CameraPathSampler.Sample samplePath(@Nullable CameraPath path, long now, boolean loop) {
        if (path == null || path.getKeyframes().isEmpty()) {
            return null;
        }
        float elapsedTicks = (now - pathStartMillis) / 50f;
        int total = path.getTotalDurationTicks();
        if (total <= 0) {
            return CameraPathSampler.sampleExact(path, 0, ORIGIN);
        }
        if (loop && path.isLoop()) {
            elapsedTicks = elapsedTicks % total;
        } else if (elapsedTicks >= total) {
            return CameraPathSampler.sampleExact(path, path.getKeyframes().size() - 1, ORIGIN);
        }

        List<CameraKeyframe> keyframes = path.getKeyframes();
        float accumulated = 0f;
        for (int i = 0; i < keyframes.size(); i++) {
            int duration = keyframes.get(i).durationTicks;
            if (duration <= 0) {
                continue;
            }
            if (elapsedTicks < accumulated + duration) {
                float rawT = (elapsedTicks - accumulated) / duration;
                return CameraPathSampler.sampleSegment(path, i, rawT, ORIGIN);
            }
            accumulated += duration;
        }
        return CameraPathSampler.sampleExact(path, keyframes.size() - 1, ORIGIN);
    }

    private boolean pathFinished(@Nullable CameraPath path, long now) {
        if (path == null) {
            return true;
        }
        int total = path.getTotalDurationTicks();
        return total <= 0 || (now - pathStartMillis) / 50f >= total;
    }

    /** Anchor hold with a subtle breathing sway so the scene never looks frozen. */
    private CameraPathSampler.Sample holdSample(long now) {
        CameraPathSampler.Sample base = holdBase != null
                ? holdBase
                : (lastSample != null ? lastSample : fallbackSample());
        float swayT = (now % 100000L) / 1000f;
        float yawSway = (float) Math.sin(swayT * 0.35) * 0.8f;
        float pitchSway = (float) Math.sin(swayT * 0.23 + 1.7) * 0.35f;
        return new CameraPathSampler.Sample(
                base.position(), base.yaw() + yawSway, base.pitch() + pitchSway,
                base.roll(), base.fov(),
                base.hideHud(), base.nightVision(), base.showSelf(),
                base.sendChunks(), base.sendChunksRadius());
    }

    private CameraPathSampler.Sample fallbackSample() {
        return new CameraPathSampler.Sample(
                new Vec3(0, 8, -12), 0f, 15f, 0f, 70f,
                true, false, false, false, 0);
    }

    /** Converts the current sample into an absolute keyframe used as a transition start. */
    private static CameraKeyframe transitionStart(CameraPathSampler.Sample from, int durationTicks) {
        return new CameraKeyframe(
                from.position(), null,
                from.yaw(), from.pitch(), from.roll(),
                CameraKeyframe.InterpolationMode.LINEAR, false,
                CameraKeyframe.EasingType.EASE_IN_OUT_CUBIC, false,
                CameraKeyframe.InterpolationMode.LINEAR, false,
                CameraKeyframe.EasingType.EASE_IN_OUT_CUBIC, false,
                from.fov(), Math.max(1, durationTicks),
                true, false, false, true, false, 0);
    }
}
