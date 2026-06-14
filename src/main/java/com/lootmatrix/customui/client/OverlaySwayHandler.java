package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.mixin.PoseStackAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Handles sway effect for Boss Bar and Action Bar overlays.
 * Only applies sway in Adventure mode to match AdventureHotbarRenderer behavior.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class OverlaySwayHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverlaySwayHandler.class);
    private static final long LOG_THROTTLE_MS = 5000L;
    private static final Constructor<PoseStack.Pose> POSE_CONSTRUCTOR = resolvePoseConstructor();
    private static final Set<ResourceLocation> loggedOverlayIds = new HashSet<>();

    // Track the safe baseline depth to restore after each overlay render.
    private static final Map<ResourceLocation, Integer> activeTransformDepths = new HashMap<>();
    private static long lastRepairLogAtMs = 0L;

    /**
     * Apply sway transformation before rendering Boss Bar and Action Bar in Adventure mode.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (BackgroundGuard.shouldSkip()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        // Only apply sway in Adventure mode
        if (mc.gameMode.getPlayerMode() != GameType.ADVENTURE) return;

        ResourceLocation overlayId = event.getOverlay().id();
        String overlayPath = overlayId.getPath();

        loggedOverlayIds.add(overlayId);

        boolean isActionbar = overlayPath.equals("chat_panel")
                || overlayPath.equals("title_text")
                || overlayPath.equals("record_overlay")
                || overlayPath.equals("subtitles");

        boolean shouldApply = overlayId.equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id()) || isActionbar;

        if (!shouldApply) return;

        // Ensure UISwayHelper is updated
        UISwayHelper swayHelper = UISwayHelper.getInstance();
        swayHelper.update(event.getPartialTick());

        // Get sway offsets
        float swayOffsetX = swayHelper.getOffsetXAdventureOnly();
        float swayOffsetY = swayHelper.getOffsetYAdventureOnly();

        if (isActionbar) {
            swayOffsetX *= 0.5f;
            swayOffsetY *= 0.5f;
        }

        // Skip if no sway
        if (swayOffsetX == 0f && swayOffsetY == 0f) return;

        PoseStack poseStack = event.getGuiGraphics().pose();
        int safeBaseDepth = ensureMinimumDepth(poseStack, 1);
        if (safeBaseDepth < 1) {
            logRepair("pre", overlayId, safeBaseDepth, getDepth(poseStack));
        }

        // Apply sway transformation via matrix
        poseStack.pushPose();
        poseStack.translate(swayOffsetX, swayOffsetY, 0);

        // Track the depth we must restore once this overlay finishes rendering.
        activeTransformDepths.put(overlayId, Math.max(1, safeBaseDepth));
    }

    /**
     * Remove sway transformation after rendering.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderOverlayPost(RenderGuiOverlayEvent.Post event) {
        ResourceLocation overlayId = event.getOverlay().id();

        Integer targetDepth = activeTransformDepths.remove(overlayId);
        if (targetDepth == null) return;

        PoseStack poseStack = event.getGuiGraphics().pose();
        int before = getDepth(poseStack);
        restoreDepth(poseStack, targetDepth);

        if (before != getDepth(poseStack)) {
            logRepair("post", overlayId, before, getDepth(poseStack));
        }
    }

    @SuppressWarnings("unchecked")
    private static Deque<PoseStack.Pose> getStack(PoseStack poseStack) {
        return ((PoseStackAccessor) (Object) poseStack).customui$getPoseStack();
    }

    private static int getDepth(PoseStack poseStack) {
        return getStack(poseStack).size();
    }

    private static int ensureMinimumDepth(PoseStack poseStack, int minDepth) {
        Deque<PoseStack.Pose> stack = getStack(poseStack);
        int before = stack.size();
        while (stack.size() < minDepth) {
            stack.addLast(newIdentityPose());
        }
        return before;
    }

    private static void restoreDepth(PoseStack poseStack, int targetDepth) {
        Deque<PoseStack.Pose> stack = getStack(poseStack);
        while (stack.size() < targetDepth) {
            stack.addLast(newIdentityPose());
        }
        while (stack.size() > targetDepth) {
            poseStack.popPose();
        }
    }

    private static Constructor<PoseStack.Pose> resolvePoseConstructor() {
        try {
            Constructor<PoseStack.Pose> constructor = PoseStack.Pose.class
                    .getDeclaredConstructor(Matrix4f.class, Matrix3f.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access PoseStack.Pose constructor", e);
        }
    }

    private static PoseStack.Pose newIdentityPose() {
        try {
            return POSE_CONSTRUCTOR.newInstance(new Matrix4f(), new Matrix3f());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create identity PoseStack entry", e);
        }
    }

    private static void logRepair(String phase, ResourceLocation overlayId, int before, int after) {
        long now = System.currentTimeMillis();
        if (now - lastRepairLogAtMs < LOG_THROTTLE_MS) {
            return;
        }
        lastRepairLogAtMs = now;
        LOGGER.debug("[OverlaySway] Repaired pose stack during {} for {} (depth {} -> {})",
                phase, overlayId, before, after);
    }
}
