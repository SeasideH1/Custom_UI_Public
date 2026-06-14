package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.CrosshairConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Renders a predicted projectile trajectory line when holding or charging a bow/trident.
 * Style: LiquidBounce-inspired blue line with blue filled circle at impact point.
 *
 * Physics simulation matches vanilla arrow/trident mechanics:
 * - Each step: pos += vel, then vel *= drag, then vel.y -= gravity
 * - Arrow drag: 0.99 (air), gravity: 0.05
 * - Trident drag: 0.99 (air), gravity: 0.05
 *
 * Depth test is ENABLED so parts behind blocks are hidden.
 * Back-face culling is DISABLED for TRIANGLE_STRIP correctness.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ProjectileTrajectoryRenderer {

    private static final String SUPERBWARFARE_MOD_ID = "superbwarfare";

    // Arrow/trident physics constants (same as vanilla AbstractArrow)
    private static final float ARROW_GRAVITY = 0.05f;
    private static final float ARROW_DRAG_AIR = 0.99f;

    private static final int CURVE_SUBDIVISIONS = 4;
    private static final int TUBE_SIDES = 6;
    private static final double VISIBILITY_EPSILON_SQR = 0.01D;
    private static final int CENTERLINE_SEARCH_STEPS = 17;
    private static final int CENTERLINE_SEARCH_REFINEMENTS = 3;
    private static final float CENTERLINE_SEARCH_ANGLE_DEGREES = 20.0F;
    private static final double HAND_FORWARD_OFFSET = 0.45D;
    private static final double HAND_SIDE_OFFSET = 0.32D;
    private static final double HAND_EXTRA_SIDE_OFFSET = 0.6D;
    private static final double HAND_DOWN_OFFSET = 0.18D;

    // Circle rendering
    private static final int CIRCLE_SEGMENTS = 32;
    private static final double TRAJECTORY_CACHE_POS_EPSILON_SQ = 0.0004D;
    private static final double TRAJECTORY_CACHE_LOOK_EPSILON_SQ = 0.0001D;

    /** Whether the current trajectory render is in "idle hold" mode (not charging). */
    private static boolean isIdleHold = false;
    @Nullable
    private static TrajectoryCacheKey cachedTrajectoryKey = null;
    @Nullable
    private static TrajectoryRenderData cachedTrajectoryData = null;

    // ==================== GC Optimization: Reusable buffers ====================
    /** Reusable list for final trajectory simulation points */
    private static final List<Vec3> reusableSimPoints = new ArrayList<>(256);
    /** Reusable list for interpolated curve points */
    private static final List<Vec3> reusableCurvePoints = new ArrayList<>(1024);
    /** Reusable list for visible points */
    private static final List<Vec3> reusableVisiblePoints = new ArrayList<>(1024);
    /** Pre-computed tube side angles (cos, sin) for TUBE_SIDES */
    private static final double[] TUBE_COS = new double[TUBE_SIDES + 1];
    private static final double[] TUBE_SIN = new double[TUBE_SIDES + 1];
    /** Cached Vec3 constants for impact circle to avoid per-call allocation */
    private static final Vec3 AXIS_X = new Vec3(1, 0, 0);
    private static final Vec3 AXIS_Y = new Vec3(0, 1, 0);
    private static final Vec3 AXIS_Z = new Vec3(0, 0, 1);

    static {
        for (int i = 0; i <= TUBE_SIDES; i++) {
            double angle = (Math.PI * 2.0D * i) / TUBE_SIDES;
            TUBE_COS[i] = Math.cos(angle);
            TUBE_SIN[i] = Math.sin(angle);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        CrosshairConfig cfg = CrosshairConfig.INSTANCE;
        if (!cfg.enabled.get() || !cfg.trajectoryEnabled.get()) return;
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) return;

        TrajectoryParameters params = getTrajectoryParameters(player, event.getPartialTick());
        if (params == null || params.velocityMagnitude() <= 0f) return;

        isIdleHold = params.idleHold();

        float partialTick = event.getPartialTick();
        Camera camera = event.getCamera();
        Vec3 eyePos = player.getEyePosition(partialTick);
        Vec3 lookDir = player.getViewVector(partialTick).normalize();
        Vec3 baseLaunchDir = params.pitchOffsetDegrees() == 0.0F
                ? lookDir
                : Vec3.directionFromRotation(
                player.getViewXRot(partialTick) + params.pitchOffsetDegrees(),
                player.getViewYRot(partialTick)
        ).normalize();
        Vec3 launchPos = getHandLaunchPosition(player, camera, partialTick, lookDir, params.hand(), params.useProgress());
        int maxSteps = cfg.trajectoryMaxSteps.get();
        Level level = player.level();
        TrajectoryRenderData renderData = getOrBuildRenderData(
                level,
                player,
                camera,
                eyePos,
                launchPos,
                baseLaunchDir,
                params,
                maxSteps
        );
        if (renderData == null) return;

        List<Vec3> visiblePoints = renderData.visiblePoints();

        if (visiblePoints.size() < 2) return;

        // Render the trajectory
        Vec3 camPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        float lineR = cfg.trajectoryColorR.get().floatValue();
        float lineG = cfg.trajectoryColorG.get().floatValue();
        float lineB = cfg.trajectoryColorB.get().floatValue();
        float lineA = cfg.trajectoryAlpha.get().floatValue();

        // When idle-holding, reduce opacity
        if (isIdleHold) {
            lineA *= 0.5f;
        }

        // Set up rendering state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();       // Hide behind blocks
        RenderSystem.depthMask(false);        // Don't write to depth buffer (trajectory is overlay)
        RenderSystem.disableCull();           // TRIANGLE_STRIP alternates winding → must disable cull
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        try {
            // Draw trajectory line
            Matrix4f matrix = poseStack.last().pose();
            renderTrajectoryLine(matrix, visiblePoints, lineR, lineG, lineB, lineA);

            // Draw impact circle
            if (renderData.impactPoint() != null && endsAtPoint(visiblePoints, renderData.impactPoint())) {
                float circleRadius = cfg.trajectoryImpactCircleRadius.get().floatValue();
                renderImpactCircle(matrix, renderData.impactPoint(), renderData.impactFace(), circleRadius, lineR, lineG, lineB, lineA);
            }
        } finally {
            // Restore rendering state even on exception so depthMask doesn't leak to the rest of the frame.
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            poseStack.popPose();
        }
    }

    @Nullable
    private static TrajectoryRenderData getOrBuildRenderData(Level level, Player player, Camera camera,
                                                             Vec3 eyePos, Vec3 launchPos, Vec3 baseLaunchDir,
                                                             TrajectoryParameters params, int maxSteps) {
        Vec3 cameraPos = camera.getPosition();
        TrajectoryCacheKey key = new TrajectoryCacheKey(
                player.tickCount,
                player.getInventory().selected,
                params.velocityMagnitude(),
                params.drag(),
                params.gravity(),
                params.pitchOffsetDegrees(),
                params.idleHold(),
                params.hand(),
                params.useProgress(),
                launchPos,
                baseLaunchDir,
                cameraPos
        );

        if (cachedTrajectoryData != null
                && cachedTrajectoryKey != null
                && cachedTrajectoryKey.matches(key)) {
            return cachedTrajectoryData;
        }

        Vec3 shotDirection = solveCenterlineLaunchDirection(
                level,
                player,
                eyePos,
                launchPos,
                baseLaunchDir,
                params.velocityMagnitude(),
                maxSteps,
                params.drag(),
                params.gravity()
        );
        Vec3 velocity = shotDirection.scale(params.velocityMagnitude());
        TrajectorySimulation simulation = simulateTrajectory(level, player, launchPos, velocity, maxSteps, params.drag(), params.gravity());
        List<Vec3> points = interpolateCurve(simulation.points());
        List<Vec3> visiblePoints = collectVisiblePoints(level, player, cameraPos, points);
        cachedTrajectoryKey = key;
        cachedTrajectoryData = new TrajectoryRenderData(List.copyOf(visiblePoints), simulation.impactPoint(), simulation.impactFace());
        return cachedTrajectoryData;
    }

    /**
     * Determine the projectile velocity magnitude based on the item being used or held.
     * Returns <= 0 if no trajectory should be rendered.
     * Sets {@link #isIdleHold} flag to differentiate idle hold from active charging.
     */
    private static TrajectoryParameters getTrajectoryParameters(Player player, float partialTick) {
        ItemStack mainHand = player.getMainHandItem();

        // Skip if holding a TACZ gun
        if (GunAmmoHelper.isTaczGun(mainHand)) return null;

        if (player.isUsingItem()) {
            ItemStack useItem = player.getUseItem();
            UseAnim anim = useItem.getUseAnimation();
            int ticksUsing = player.getTicksUsingItem();
            InteractionHand usedHand = player.getUsedItemHand();

            SuperbwarfareThrowableProfile superbwarfareProfile = getSuperbwarfareThrowableProfile(useItem);
            if (superbwarfareProfile != null) {
                int effectiveTicks = Math.max(ticksUsing, superbwarfareProfile.minChargeTicks());
                float velocity = superbwarfareProfile.velocityForTicks(effectiveTicks);
                float useProgress = Mth.clamp((ticksUsing + partialTick) / (float) superbwarfareProfile.fullChargeTicks(), 0.0F, 1.0F);
                return new TrajectoryParameters(velocity, superbwarfareProfile.drag(), superbwarfareProfile.gravity(), 0.0F, false, usedHand, useProgress);
            }

            switch (anim) {
                case BOW: {
                    float power = BowItem.getPowerForTime(ticksUsing);
                    float useProgress = Mth.clamp((ticksUsing + partialTick) / 20.0F, 0.0F, 1.0F);
                    return new TrajectoryParameters(Math.max(power, 0.05f) * 3.0f, ARROW_DRAG_AIR, ARROW_GRAVITY, 0.0F, false, usedHand, useProgress);
                }
                case SPEAR: {
                    float useProgress = Mth.clamp((ticksUsing + partialTick) / 20.0F, 0.0F, 1.0F);
                    return new TrajectoryParameters(2.5f, ARROW_DRAG_AIR, ARROW_GRAVITY, 0.0F, false, usedHand, useProgress);
                }
                case CROSSBOW: {
                    return null; // Don't show during charge
                }
                default:
                    break;
            }
        }

        SuperbwarfareThrowableProfile heldSuperbwarfareProfile = getSuperbwarfareThrowableProfile(mainHand);
        if (heldSuperbwarfareProfile != null) {
            float velocity = heldSuperbwarfareProfile.velocityForTicks(heldSuperbwarfareProfile.minChargeTicks());
            float useProgress = heldSuperbwarfareProfile.minChargeTicks() / (float) heldSuperbwarfareProfile.fullChargeTicks();
            return new TrajectoryParameters(velocity, heldSuperbwarfareProfile.drag(), heldSuperbwarfareProfile.gravity(), 0.0F, true, InteractionHand.MAIN_HAND, useProgress);
        }

        VanillaThrowableProfile vanillaThrowableProfile = getVanillaThrowableProfile(mainHand);
        if (vanillaThrowableProfile != null) {
            return new TrajectoryParameters(
                    vanillaThrowableProfile.velocity(),
                    vanillaThrowableProfile.drag(),
                    vanillaThrowableProfile.gravity(),
                    vanillaThrowableProfile.pitchOffsetDegrees(),
                    true,
                    InteractionHand.MAIN_HAND,
                    0.0F
            );
        }

        // Not currently using an item — check if HOLDING a bow/crossbow/trident
        if (mainHand.getItem() instanceof BowItem) {
            return new TrajectoryParameters(BowItem.getPowerForTime(1) * 3.0f, ARROW_DRAG_AIR, ARROW_GRAVITY, 0.0F, true, InteractionHand.MAIN_HAND, 0.0F);
        }
        if (mainHand.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(mainHand)) {
            return new TrajectoryParameters(3.15f, ARROW_DRAG_AIR, ARROW_GRAVITY, 0.0F, false, InteractionHand.MAIN_HAND, 1.0F);
        }
        UseAnim holdAnim = mainHand.getUseAnimation();
        if (holdAnim == UseAnim.SPEAR) {
            return new TrajectoryParameters(2.5f, ARROW_DRAG_AIR, ARROW_GRAVITY, 0.0F, true, InteractionHand.MAIN_HAND, 0.0F);
        }

        return null;
    }

    private static Vec3 getHandLaunchPosition(Player player, Camera camera, float partialTick, Vec3 lookDir,
                                              InteractionHand hand, float useProgress) {
        Vec3 eyePos = player.getEyePosition(partialTick);
        Vector3f upVector = camera.getUpVector();
        Vec3 cameraUp = new Vec3(upVector.x, upVector.y, upVector.z).normalize();
        Vec3 cameraRight = cameraUp.cross(lookDir);
        if (cameraRight.lengthSqr() < 1.0E-6D) {
            cameraRight = new Vec3(1, 0, 0);
        } else {
            cameraRight = cameraRight.normalize();
        }

        double handSide = getFlippedHorizontalSide(player, hand);

        double forwardOffset = HAND_FORWARD_OFFSET;
        double sideOffset = (HAND_SIDE_OFFSET + HAND_EXTRA_SIDE_OFFSET) * handSide;
        double downOffset = HAND_DOWN_OFFSET;

        return eyePos.add(lookDir.scale(forwardOffset))
                .add(cameraRight.scale(sideOffset))
                .subtract(cameraUp.scale(downOffset));
    }

    private static double getFlippedHorizontalSide(Player player, InteractionHand hand) {
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : (player.getMainArm() == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT);
        return arm == HumanoidArm.RIGHT ? -1.0D : 1.0D;
    }

    private static SuperbwarfareThrowableProfile getSuperbwarfareThrowableProfile(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !GunAmmoHelper.isSuperbwarfareLoaded()) {
            return null;
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null || !SUPERBWARFARE_MOD_ID.equals(itemId.getNamespace())) {
            return null;
        }

        String path = itemId.getPath();
        return switch (path) {
            case "hand_grenade" -> new SuperbwarfareThrowableProfile(path, 4, 15, 10.0F, 1.5F, ARROW_DRAG_AIR, ARROW_GRAVITY);
            case "rgo_grenade" -> new SuperbwarfareThrowableProfile(path, 4, 15, 8.0F, 1.8F, ARROW_DRAG_AIR, ARROW_GRAVITY);
            case "m18_smoke_grenade" -> new SuperbwarfareThrowableProfile(path, 4, 15, 8.0F, 1.8F, ARROW_DRAG_AIR, 0.07F);
            default -> path.contains("grenade")
                    ? new SuperbwarfareThrowableProfile(path, 4, 15, 8.0F, 1.8F, ARROW_DRAG_AIR, ARROW_GRAVITY)
                    : null;
        };
    }

    private static VanillaThrowableProfile getVanillaThrowableProfile(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        if (stack.getItem() instanceof SnowballItem || stack.getItem() instanceof EggItem || stack.getItem() instanceof EnderpearlItem) {
            return new VanillaThrowableProfile(1.5F, 0.99F, 0.03F, 0.0F);
        }
        if (stack.getItem() instanceof ThrowablePotionItem) {
            return new VanillaThrowableProfile(0.5F, 0.99F, 0.05F, -20.0F);
        }
        if (stack.getItem() instanceof ExperienceBottleItem) {
            return new VanillaThrowableProfile(0.7F, 0.99F, 0.07F, -20.0F);
        }

        return null;
    }

    private static Vec3 solveCenterlineLaunchDirection(Level level, Player player, Vec3 eyePos, Vec3 launchPos,
                                                       Vec3 baseLookDir, float velocityMagnitude, int maxSteps,
                                                       float drag, float gravity) {
        // GC optimization: compute rightAxis inline
        double rax = -baseLookDir.z;
        double raz = baseLookDir.x;
        double raLenSq = rax * rax + raz * raz;
        if (raLenSq < 1.0E-6D) {
            rax = 1.0D;
            raz = 0.0D;
        } else {
            double raInvLen = 1.0D / Math.sqrt(raLenSq);
            rax *= raInvLen;
            raz *= raInvLen;
        }

        float bestYaw = 0.0F;
        double bestAbsError = Double.MAX_VALUE;
        float searchRadius = CENTERLINE_SEARCH_ANGLE_DEGREES;

        for (int refinement = 0; refinement < CENTERLINE_SEARCH_REFINEMENTS; refinement++) {
            float startYaw = bestYaw - searchRadius;
            float endYaw = bestYaw + searchRadius;

            for (int i = 0; i < CENTERLINE_SEARCH_STEPS; i++) {
                float yaw = Mth.lerp(i / (float) (CENTERLINE_SEARCH_STEPS - 1), startYaw, endYaw);
                // GC optimization: inline rotateYaw + scale for candidate direction
                double dirX, dirY, dirZ;
                if (Math.abs(yaw) < 1.0E-4F) {
                    dirX = baseLookDir.x;
                    dirY = baseLookDir.y;
                    dirZ = baseLookDir.z;
                } else {
                    double yawRad = Math.toRadians(yaw);
                    double cosY = Math.cos(yawRad);
                    double sinY = Math.sin(yawRad);
                    double rx = baseLookDir.x * cosY - baseLookDir.z * sinY;
                    double rz = baseLookDir.x * sinY + baseLookDir.z * cosY;
                    double rl = Math.sqrt(rx * rx + baseLookDir.y * baseLookDir.y + rz * rz);
                    if (rl < 1.0E-6D) rl = 1.0D;
                    dirX = rx / rl;
                    dirY = baseLookDir.y / rl;
                    dirZ = rz / rl;
                }

                // GC optimization: lightweight endpoint-only simulation (no ArrayList, no Vec3 allocs)
                double px = launchPos.x, py = launchPos.y, pz = launchPos.z;
                double epx = px, epy = py, epz = pz;
                double vx = dirX * velocityMagnitude, vy = dirY * velocityMagnitude, vz = dirZ * velocityMagnitude;
                boolean hitSomething = false;
                for (int step = 0; step < maxSteps; step++) {
                    double nx = px + vx, ny = py + vy, nz = pz + vz;
                    BlockHitResult hit = level.clip(new ClipContext(
                            new Vec3(px, py, pz), new Vec3(nx, ny, nz),
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                    if (hit.getType() != HitResult.Type.MISS) {
                        Vec3 loc = hit.getLocation();
                        epx = loc.x; epy = loc.y; epz = loc.z;
                        hitSomething = true;
                        break;
                    }
                    px = nx; py = ny; pz = nz;
                    vx *= drag; vy *= drag; vz *= drag;
                    vy -= gravity;
                }
                if (!hitSomething) { epx = px; epy = py; epz = pz; }

                // Compute lateral error inline (no Vec3.subtract/dot)
                double dx = epx - eyePos.x, dy = epy - eyePos.y, dz = epz - eyePos.z;
                double lateralError = Math.abs(dx * rax + dz * raz); // rightAxis.y is always 0
                if (lateralError < bestAbsError) {
                    bestAbsError = lateralError;
                    bestYaw = yaw;
                }
            }

            searchRadius *= 0.25F;
        }

        return rotateYaw(baseLookDir, bestYaw);
    }

    private static Vec3 rotateYaw(Vec3 direction, float yawDegrees) {
        if (Math.abs(yawDegrees) < 1.0E-4F) {
            return direction;
        }

        double yawRad = Math.toRadians(yawDegrees);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double x = direction.x * cos - direction.z * sin;
        double z = direction.x * sin + direction.z * cos;
        return new Vec3(x, direction.y, z).normalize();
    }

    private static Vec3 getSimulationEndPoint(TrajectorySimulation simulation) {
        if (simulation.impactPoint() != null) {
            return simulation.impactPoint();
        }
        List<Vec3> points = simulation.points();
        return points.get(points.size() - 1);
    }

    /**
     * Simulate trajectory into the reusable static list to avoid per-frame ArrayList allocation.
     * The returned list reference is only valid until the next call.
     */
    private static TrajectorySimulation simulateTrajectory(Level level, Player player, Vec3 start, Vec3 velocity,
                                                            int maxSteps, float drag, float gravity) {
        reusableSimPoints.clear();
        reusableSimPoints.add(start);

        double px = start.x;
        double py = start.y;
        double pz = start.z;
        double vx = velocity.x;
        double vy = velocity.y;
        double vz = velocity.z;

        Vec3 impactPoint = null;
        Direction impactFace = null;

        for (int i = 0; i < maxSteps; i++) {
            // ClipContext requires Vec3 args; these are unavoidable but reduced from 51x to 1x per frame
            Vec3 current = new Vec3(px, py, pz);
            double nx = px + vx, ny = py + vy, nz = pz + vz;
            Vec3 next = new Vec3(nx, ny, nz);

            BlockHitResult hit = level.clip(new ClipContext(
                    current, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

            if (hit.getType() != HitResult.Type.MISS) {
                impactPoint = hit.getLocation();
                impactFace = hit.getDirection();
                reusableSimPoints.add(impactPoint);
                break;
            }

            px = nx;
            py = ny;
            pz = nz;
            reusableSimPoints.add(next);

            vx *= drag;
            vy *= drag;
            vz *= drag;
            vy -= gravity;
        }

        // Copy to a new list since the caller needs to retain the result
        return new TrajectorySimulation(new ArrayList<>(reusableSimPoints), impactPoint, impactFace);
    }

    /**
     * GC optimization: uses reusable static list. Returned list is valid until next call.
     */
    private static List<Vec3> interpolateCurve(List<Vec3> controlPoints) {
        if (controlPoints.size() < 3) {
            return controlPoints;
        }

        reusableCurvePoints.clear();
        reusableCurvePoints.add(controlPoints.get(0));

        for (int i = 0; i < controlPoints.size() - 1; i++) {
            Vec3 p0 = i > 0 ? controlPoints.get(i - 1) : controlPoints.get(i);
            Vec3 p1 = controlPoints.get(i);
            Vec3 p2 = controlPoints.get(i + 1);
            Vec3 p3 = i + 2 < controlPoints.size() ? controlPoints.get(i + 2) : p2;

            int subdivisions = Math.max(CURVE_SUBDIVISIONS, (int) Math.ceil(p1.distanceTo(p2) * 2.0D));
            for (int step = 1; step < subdivisions; step++) {
                double t = step / (double) subdivisions;
                reusableCurvePoints.add(catmullRom(p0, p1, p2, p3, t));
            }
            reusableCurvePoints.add(p2);
        }

        return reusableCurvePoints;
    }

    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5D * ((2.0D * p1.x)
                + (-p0.x + p2.x) * t
                + (2.0D * p0.x - 5.0D * p1.x + 4.0D * p2.x - p3.x) * t2
                + (-p0.x + 3.0D * p1.x - 3.0D * p2.x + p3.x) * t3);
        double y = 0.5D * ((2.0D * p1.y)
                + (-p0.y + p2.y) * t
                + (2.0D * p0.y - 5.0D * p1.y + 4.0D * p2.y - p3.y) * t2
                + (-p0.y + 3.0D * p1.y - 3.0D * p2.y + p3.y) * t3);
        double z = 0.5D * ((2.0D * p1.z)
                + (-p0.z + p2.z) * t
                + (2.0D * p0.z - 5.0D * p1.z + 4.0D * p2.z - p3.z) * t2
                + (-p0.z + 3.0D * p1.z - 3.0D * p2.z + p3.z) * t3);

        return new Vec3(x, y, z);
    }

    /**
     * GC optimization: uses reusable static list. Returned list is valid until next call.
     */
    private static List<Vec3> collectVisiblePoints(Level level, Player player, Vec3 cameraPos, List<Vec3> points) {
        reusableVisiblePoints.clear();
        for (Vec3 point : points) {
            if (!isPointVisible(level, player, cameraPos, point)) {
                break;
            }
            reusableVisiblePoints.add(point);
        }
        return reusableVisiblePoints;
    }

    private static boolean isPointVisible(Level level, Player player, Vec3 cameraPos, Vec3 point) {
        BlockHitResult hit = level.clip(new ClipContext(
                cameraPos,
                point,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(point) <= VISIBILITY_EPSILON_SQR;
    }

    private static boolean endsAtPoint(List<Vec3> points, Vec3 target) {
        if (points.isEmpty() || target == null) {
            return false;
        }
        return points.get(points.size() - 1).distanceToSqr(target) <= VISIBILITY_EPSILON_SQR;
    }

    /**
     * GC optimization: all Vec3 operations expanded to inline double math.
     * Uses pre-computed TUBE_COS/TUBE_SIN arrays.
     * Renders the trajectory as a cylindrical/prismatic tube.
     */
    private static void renderTrajectoryLine(Matrix4f matrix, List<Vec3> points,
                                              float r, float g, float b, float baseAlpha) {
        int count = points.size();
        if (count < 2) return;

        float configWidth = CrosshairConfig.INSTANCE.trajectoryLineWidth.get().floatValue();
        float widthScale = isIdleHold ? 0.4f : 1.0f;
        float radius = configWidth * widthScale * 0.01f;

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float invCountM1 = 1.0f / Math.max(1, count - 1);

        for (int i = 0; i < count - 1; i++) {
            Vec3 start = points.get(i);
            Vec3 end = points.get(i + 1);
            // Inline: axis = end.subtract(start)
            double ax = end.x - start.x, ay = end.y - start.y, az = end.z - start.z;
            double axisLength = Math.sqrt(ax * ax + ay * ay + az * az);
            if (axisLength < 1.0E-6D) continue;

            double invAxisLen = 1.0D / axisLength;
            ax *= invAxisLen; ay *= invAxisLen; az *= invAxisLen;

            // Inline: basisA = axis.cross(upOrRight)
            double bax, bay, baz;
            if (Math.abs(ay) < 0.98D) {
                // cross with (0,1,0)
                bax = ay * 0 - az * 1; // = -az  (simplified: 0*az - 1*az... no)
                // cross(a, b) = (a.y*b.z - a.z*b.y, a.z*b.x - a.x*b.z, a.x*b.y - a.y*b.x)
                // a=(ax,ay,az), b=(0,1,0)
                bax = ay * 0 - az * 1;  // = -az
                bay = az * 0 - ax * 0;  // = 0
                baz = ax * 1 - ay * 0;  // = ax
                // Simplified:
                bax = -az; bay = 0; baz = ax;
            } else {
                // cross with (1,0,0)
                bax = ay * 0 - az * 0;  // = 0
                bay = az * 1 - ax * 0;  // = az
                baz = ax * 0 - ay * 1;  // = -ay
                bax = 0; bay = az; baz = -ay;
            }
            double baLen = Math.sqrt(bax * bax + bay * bay + baz * baz);
            if (baLen < 1.0E-6D) continue;

            double baScale = radius / baLen;
            bax *= baScale; bay *= baScale; baz *= baScale;

            // Inline: basisB = axis.cross(basisA).normalize().scale(radius)
            double bbx = ay * baz - az * bay;
            double bby = az * bax - ax * baz;
            double bbz = ax * bay - ay * bax;
            double bbLen = Math.sqrt(bbx * bbx + bby * bby + bbz * bbz);
            if (bbLen < 1.0E-6D) continue;
            double bbScale = radius / bbLen;
            bbx *= bbScale; bby *= bbScale; bbz *= bbScale;

            float startAlpha = baseAlpha * (1.0f - (i * invCountM1) * 0.7f);
            float endAlpha = baseAlpha * (1.0f - ((i + 1) * invCountM1) * 0.7f);

            for (int side = 0; side < TUBE_SIDES; side++) {
                double cos0 = TUBE_COS[side], sin0 = TUBE_SIN[side];
                double cos1 = TUBE_COS[side + 1], sin1 = TUBE_SIN[side + 1];

                // Inline: offset0 = basisA*cos0 + basisB*sin0
                double o0x = bax * cos0 + bbx * sin0;
                double o0y = bay * cos0 + bby * sin0;
                double o0z = baz * cos0 + bbz * sin0;
                double o1x = bax * cos1 + bbx * sin1;
                double o1y = bay * cos1 + bby * sin1;
                double o1z = baz * cos1 + bbz * sin1;

                buf.vertex(matrix, (float)(start.x + o0x), (float)(start.y + o0y), (float)(start.z + o0z)).color(r, g, b, startAlpha).endVertex();
                buf.vertex(matrix, (float)(start.x + o1x), (float)(start.y + o1y), (float)(start.z + o1z)).color(r, g, b, startAlpha).endVertex();
                buf.vertex(matrix, (float)(end.x + o1x), (float)(end.y + o1y), (float)(end.z + o1z)).color(r, g, b, endAlpha).endVertex();
                buf.vertex(matrix, (float)(end.x + o0x), (float)(end.y + o0y), (float)(end.z + o0z)).color(r, g, b, endAlpha).endVertex();
            }
        }

        BufferUploader.drawWithShader(buf.end());
    }

    /**
     * GC optimization: uses cached AXIS_X/AXIS_Y/AXIS_Z constants instead of per-call new Vec3.
     */
    private static void renderImpactCircle(Matrix4f matrix, Vec3 center, Direction face,
                                            float radius, float r, float g, float b, float alpha) {
        Vec3 axisU, axisV;
        if (face == null) {
            axisU = AXIS_X;
            axisV = AXIS_Z;
        } else {
            switch (face) {
                case UP, DOWN -> {
                    axisU = AXIS_X;
                    axisV = AXIS_Z;
                }
                case NORTH, SOUTH -> {
                    axisU = AXIS_X;
                    axisV = AXIS_Y;
                }
                case EAST, WEST -> {
                    axisU = AXIS_Z;
                    axisV = AXIS_Y;
                }
                default -> {
                    axisU = AXIS_X;
                    axisV = AXIS_Z;
                }
            }
        }

        // Slightly offset from the surface to avoid z-fighting
        Vec3 normal = face != null ? new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()) : Vec3.ZERO;
        Vec3 drawCenter = center.add(normal.scale(0.005));

        // Precompute circle points
        float[][] cp = new float[CIRCLE_SEGMENTS + 1][3];
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            float angle = (float)(i * 2.0 * Math.PI / CIRCLE_SEGMENTS);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            cp[i][0] = (float)(drawCenter.x + (axisU.x * cos + axisV.x * sin) * radius);
            cp[i][1] = (float)(drawCenter.y + (axisU.y * cos + axisV.y * sin) * radius);
            cp[i][2] = (float)(drawCenter.z + (axisU.z * cos + axisV.z * sin) * radius);
        }

        // 1) Filled disc (TRIANGLE_FAN)
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        float fillAlpha = alpha * 0.7f;
        buf.vertex(matrix, (float) drawCenter.x, (float) drawCenter.y, (float) drawCenter.z)
                .color(r, g, b, fillAlpha).endVertex();
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            buf.vertex(matrix, cp[i][0], cp[i][1], cp[i][2])
                    .color(r, g, b, fillAlpha).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());

        // 2) Outline ring (TRIANGLE_STRIP)
        float ringWidth = 0.02f;
        float outerRadius = radius + ringWidth;
        buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            float angle = (float)(i * 2.0 * Math.PI / CIRCLE_SEGMENTS);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            float ix = (float)(drawCenter.x + (axisU.x * cos + axisV.x * sin) * radius);
            float iy = (float)(drawCenter.y + (axisU.y * cos + axisV.y * sin) * radius);
            float iz = (float)(drawCenter.z + (axisU.z * cos + axisV.z * sin) * radius);

            float ox = (float)(drawCenter.x + (axisU.x * cos + axisV.x * sin) * outerRadius);
            float oy = (float)(drawCenter.y + (axisU.y * cos + axisV.y * sin) * outerRadius);
            float oz = (float)(drawCenter.z + (axisU.z * cos + axisV.z * sin) * outerRadius);

            buf.vertex(matrix, ix, iy, iz).color(r, g, b, alpha).endVertex();
            buf.vertex(matrix, ox, oy, oz).color(r, g, b, alpha).endVertex();
        }

        BufferUploader.drawWithShader(buf.end());
    }

    private record TrajectorySimulation(List<Vec3> points, Vec3 impactPoint, Direction impactFace) {
    }

    private record TrajectoryParameters(float velocityMagnitude, float drag, float gravity, float pitchOffsetDegrees,
                                        boolean idleHold, InteractionHand hand, float useProgress) {
    }

    private record SuperbwarfareThrowableProfile(String itemPath, int minChargeTicks, int fullChargeTicks,
                                                 float ticksPerVelocityUnit, float maxVelocity,
                                                 float drag, float gravity) {
        float velocityForTicks(int ticksUsing) {
            return Math.min(ticksUsing / ticksPerVelocityUnit, maxVelocity);
        }
    }

    private record VanillaThrowableProfile(float velocity, float drag, float gravity, float pitchOffsetDegrees) {
    }

    private record TrajectoryRenderData(List<Vec3> visiblePoints, @Nullable Vec3 impactPoint, @Nullable Direction impactFace) {
    }

    private record TrajectoryCacheKey(int playerTick, int selectedSlot, float velocityMagnitude, float drag, float gravity,
                                      float pitchOffsetDegrees, boolean idleHold, InteractionHand hand, float useProgress,
                                      Vec3 launchPos, Vec3 baseLaunchDir, Vec3 cameraPos) {
        private boolean matches(TrajectoryCacheKey other) {
            return playerTick == other.playerTick
                    && selectedSlot == other.selectedSlot
                    && Float.compare(velocityMagnitude, other.velocityMagnitude) == 0
                    && Float.compare(drag, other.drag) == 0
                    && Float.compare(gravity, other.gravity) == 0
                    && Float.compare(pitchOffsetDegrees, other.pitchOffsetDegrees) == 0
                    && idleHold == other.idleHold
                    && hand == other.hand
                    && Math.abs(useProgress - other.useProgress) < 0.01F
                    && launchPos.distanceToSqr(other.launchPos) <= TRAJECTORY_CACHE_POS_EPSILON_SQ
                    && baseLaunchDir.distanceToSqr(other.baseLaunchDir) <= TRAJECTORY_CACHE_LOOK_EPSILON_SQ
                    && cameraPos.distanceToSqr(other.cameraPos) <= TRAJECTORY_CACHE_POS_EPSILON_SQ;
        }
    }
}
