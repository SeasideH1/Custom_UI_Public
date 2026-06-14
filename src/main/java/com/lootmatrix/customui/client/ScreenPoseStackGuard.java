package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.mixin.PoseStackAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Deque;
import java.lang.reflect.Constructor;

/**
 * Screen rendering shares the same GuiGraphics pose stack with other GUI hooks.
 * If another render path underflows or leaks the stack, Forge can crash later in
 * drawScreen() when it reads the last pose. This guard restores the entry depth
 * around screen rendering so a broken downstream renderer does not take down the client.
 */
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ScreenPoseStackGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenPoseStackGuard.class);
    private static final long LOG_THROTTLE_MS = 5000L;
    private static final Constructor<PoseStack.Pose> POSE_CONSTRUCTOR = resolvePoseConstructor();

    private static int expectedDepth = -1;
    private static long lastLogAtMs = 0L;

    private ScreenPoseStackGuard() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        Deque<PoseStack.Pose> stack = getStack(event.getGuiGraphics().pose());
        if (stack.isEmpty()) {
            restoreMissingEntries(stack, 1);
            logRepair("pre", event.getScreen().getClass().getName(), 0, stack.size());
        }
        expectedDepth = Math.max(1, stack.size());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Deque<PoseStack.Pose> stack = getStack(event.getGuiGraphics().pose());
        int before = stack.size();
        int targetDepth = Math.max(1, expectedDepth);

        if (before < targetDepth) {
            restoreMissingEntries(stack, targetDepth - before);
        } else {
            while (stack.size() > targetDepth) {
                event.getGuiGraphics().pose().popPose();
            }
        }

        if (before != stack.size()) {
            logRepair("post", event.getScreen().getClass().getName(), before, stack.size());
        }

        expectedDepth = -1;
    }

    @SuppressWarnings("unchecked")
    private static Deque<PoseStack.Pose> getStack(PoseStack poseStack) {
        return ((PoseStackAccessor) (Object) poseStack).customui$getPoseStack();
    }

    private static void restoreMissingEntries(Deque<PoseStack.Pose> stack, int count) {
        for (int i = 0; i < count; i++) {
            stack.addLast(newIdentityPose());
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

    private static void logRepair(String phase, String screenClass, int before, int after) {
        long now = System.currentTimeMillis();
        if (now - lastLogAtMs < LOG_THROTTLE_MS) {
            return;
        }
        lastLogAtMs = now;
        LOGGER.warn("[ScreenPoseStackGuard] Repaired pose stack during {} for {} (depth {} -> {})",
                phase, screenClass, before, after);
    }
}
