package com.lootmatrix.customui.cinematic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Client-side engine that drives cinematic camera playback.
 */
@OnlyIn(Dist.CLIENT)
public class CinematicCameraEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(CinematicCameraEngine.class);
    private static final CinematicCameraEngine INSTANCE = new CinematicCameraEngine();

    public static CinematicCameraEngine getInstance() { return INSTANCE; }

    private CameraPath currentPath;
    private boolean playing;
    private int currentKeyframeIndex;
    private int ticksInSegment;
    private Vec3 playerOrigin = Vec3.ZERO;

    private Vec3 cameraPos = Vec3.ZERO;
    private float cameraYaw;
    private float cameraPitch;
    private float cameraRoll;
    private float cameraFov = 70f;
    private boolean hideHud;
    private boolean nightVision;
    private boolean showSelf;

    private Vec3 prevCameraPos = Vec3.ZERO;
    private float prevYaw;
    private float prevPitch;
    private float prevRoll;
    private float prevFov = 70f;

    private CinematicCameraEngine() {}

    public boolean isPlaying() { return playing; }
    public Vec3 getCameraPos() { return cameraPos; }
    public float getCameraYaw() { return cameraYaw; }
    public float getCameraPitch() { return cameraPitch; }
    public float getCameraRoll() { return cameraRoll; }
    public float getCameraFov() { return cameraFov; }
    public boolean shouldHideHud() { return playing && hideHud; }
    public boolean shouldApplyNightVision() { return playing && nightVision; }
    public boolean shouldShowSelf() { return playing && showSelf; }

    public Vec3 getInterpolatedPos(float partialTick) {
        return playing ? CameraInterpolation.lerpPosition(prevCameraPos, cameraPos, partialTick) : cameraPos;
    }

    public float getInterpolatedYaw(float partialTick) {
        return playing ? CameraInterpolation.lerpAngle(prevYaw, cameraYaw, partialTick) : cameraYaw;
    }

    public float getInterpolatedPitch(float partialTick) {
        return playing ? CameraInterpolation.lerpAngle(prevPitch, cameraPitch, partialTick) : cameraPitch;
    }

    public float getInterpolatedRoll(float partialTick) {
        return playing ? prevRoll + (cameraRoll - prevRoll) * partialTick : cameraRoll;
    }

    public float getInterpolatedFov(float partialTick) {
        return playing ? prevFov + (cameraFov - prevFov) * partialTick : cameraFov;
    }

    public void startPath(CameraPath path) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || path == null || path.getKeyframes().isEmpty()) return;

        this.currentPath = path;
        this.playing = true;
        this.currentKeyframeIndex = 0;
        this.ticksInSegment = 0;
        this.playerOrigin = mc.player.position();

        applySample(CameraPathSampler.sampleExact(path, 0, playerOrigin));
        prevCameraPos = cameraPos;
        prevYaw = cameraYaw;
        prevPitch = cameraPitch;
        prevRoll = cameraRoll;
        prevFov = cameraFov;

        LOGGER.info("[CustomUI] Started cinematic path '{}' with {} keyframes, total {} ticks",
                path.getId(), path.getKeyframes().size(), path.getTotalDurationTicks());
    }

    public void stop() {
        if (!playing) return;
        
        // Only remove night vision if we were actively applying it
        boolean wasApplyingNightVision = nightVision;
        
        playing = false;
        currentPath = null;
        hideHud = false;
        nightVision = false;
        showSelf = false;

        // Only remove night vision if we were actively applying it
        if (wasApplyingNightVision) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                MobEffectInstance effect = mc.player.getEffect(MobEffects.NIGHT_VISION);
                // Only remove if it's our effect with the specific duration range (350-400 ticks)
                if (effect != null && !effect.isAmbient() 
                        && effect.getDuration() >= 350 && effect.getDuration() <= 400) {
                    mc.player.removeEffect(MobEffects.NIGHT_VISION);
                }
            }
        }
        LOGGER.info("[CustomUI] Stopped cinematic camera");
    }

    public void tick() {
        if (!playing || currentPath == null) return;

        List<CameraKeyframe> keyframes = currentPath.getKeyframes();
        if (keyframes.isEmpty()) {
            stop();
            return;
        }

        prevCameraPos = cameraPos;
        prevYaw = cameraYaw;
        prevPitch = cameraPitch;
        prevRoll = cameraRoll;
        prevFov = cameraFov;

        int nextIndex = CameraPathSampler.nextIndex(currentPath, currentKeyframeIndex);
        if (nextIndex < 0) {
            stop();
            return;
        }

        CameraKeyframe current = keyframes.get(currentKeyframeIndex);
        if (current.durationTicks <= 0) {
            applySample(CameraPathSampler.sampleExact(currentPath, currentKeyframeIndex, playerOrigin));
            stop();
            return;
        }

        ticksInSegment++;
        float rawT = Math.min(1.0f, (float) ticksInSegment / current.durationTicks);

        applySample(CameraPathSampler.sampleSegment(currentPath, currentKeyframeIndex, rawT, playerOrigin));

        if (rawT >= 1.0f) {
            currentKeyframeIndex = nextIndex;
            ticksInSegment = 0;

            int nextPlayable = CameraPathSampler.nextIndex(currentPath, currentKeyframeIndex);
            if (!currentPath.isLoop() && (nextPlayable < 0 || keyframes.get(currentKeyframeIndex).durationTicks <= 0)) {
                applySample(CameraPathSampler.sampleExact(currentPath, currentKeyframeIndex, playerOrigin));
                stop();
                return;
            }
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && nightVision) {
            MobEffectInstance nightVisionEffect = mc.player.getEffect(MobEffects.NIGHT_VISION);
            // Check if we already have our cinematic night vision effect
            boolean hasOurEffect = nightVisionEffect != null && !nightVisionEffect.isAmbient()
                    && nightVisionEffect.getDuration() >= 350 && nightVisionEffect.getDuration() <= 400;
            
            if (!hasOurEffect) {
                // Apply night vision with non-ambient flag and specific duration range
                // Duration 400 ticks = 20 seconds, refreshed every tick
                mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, false, false, false));
            }
        }
    }

    private void applySample(CameraPathSampler.Sample sample) {
        cameraPos = sample.position();
        cameraYaw = sample.yaw();
        cameraPitch = sample.pitch();
        cameraRoll = sample.roll();
        cameraFov = sample.fov();
        hideHud = sample.hideHud();
        nightVision = sample.nightVision();
        showSelf = sample.showSelf();
    }
}
