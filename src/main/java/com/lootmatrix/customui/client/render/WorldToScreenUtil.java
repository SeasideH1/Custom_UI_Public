package com.lootmatrix.customui.client.render;

import com.lootmatrix.customui.Main;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4d;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class WorldToScreenUtil {

    private static Matrix4f modelViewMatrix = new Matrix4f();
    private static Matrix4f projectionMatrix = new Matrix4f();
    private static double fov = 70.0;

    /**
     * 由 LevelRendererMatrixMixin 在 renderLevel 内第一次
     * {@code RenderSystem.applyModelViewMatrix()} 之后调用（与 Superbwarfare 同款注入点）。
     * 此刻 RenderSystem 的 model-view = 相机旋转，projection = 世界透视投影
     * （含 bobView/bobHurt 与当前帧实际 FOV）。深拷贝为本帧快照。
     */
    public static void captureMatrices() {
        modelViewMatrix.set(RenderSystem.getModelViewMatrix());
        projectionMatrix.set(RenderSystem.getProjectionMatrix());
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (event.usedConfiguredFov()) {
            fov = event.getFOV();
        }
    }

    public static double getFov() {
        return fov;
    }

    private static final Vector4d PROJECT_SCRATCH = new Vector4d();

    /**
     * 将世界坐标投影到 GUI 缩放坐标系（零分配版本，供每帧热路径使用）。
     * 仅渲染线程调用。写入 out = {screenX, screenY, w}，w <= 0 表示目标在相机后方。
     */
    public static void worldToScreen(double worldX, double worldY, double worldZ, double[] out) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();

        PROJECT_SCRATCH.set(worldX - cameraPos.x, worldY - cameraPos.y, worldZ - cameraPos.z, 1.0);
        PROJECT_SCRATCH.mul(modelViewMatrix);
        PROJECT_SCRATCH.mul(projectionMatrix);

        double w = PROJECT_SCRATCH.w;
        if (w != 0.0) {
            PROJECT_SCRATCH.div(w);
        }
        out[0] = window.getGuiScaledWidth() * (0.5 + PROJECT_SCRATCH.x * 0.5);
        out[1] = window.getGuiScaledHeight() * (0.5 - PROJECT_SCRATCH.y * 0.5);
        out[2] = w;
    }

    /**
     * 将世界坐标投影到 GUI 缩放坐标系。
     *
     * @param worldPos 世界空间坐标
     * @return Vec3(screenX, screenY, w)，w <= 0 表示目标在相机后方
     */
    public static Vec3 worldToScreen(Vec3 worldPos) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        Camera camera = mc.gameRenderer.getMainCamera();

        Vec3 relPos = worldPos.add(camera.getPosition().reverse());
        Vector4d vec4 = new Vector4d(relPos.toVector3f(), 1.0);

        vec4.mul(modelViewMatrix);
        vec4.mul(projectionMatrix);

        double w = vec4.w;
        if (w != 0.0) {
            vec4.div(w);
        }

        double screenX = window.getGuiScaledWidth() * (0.5 + vec4.x * 0.5);
        double screenY = window.getGuiScaledHeight() * (0.5 - vec4.y * 0.5);

        return new Vec3(screenX, screenY, w);
    }

    /**
     * 检查世界坐标是否大致在相机视野范围内（含 10° 余量）。
     */
    public static boolean canSee(Vec3 worldPos) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vec3 lookDir = new Vec3(camera.getLookVector());
        Vec3 toTarget = cameraPos.vectorTo(worldPos);
        double angle = angleBetween(toTarget, lookDir);
        return angle < fov + 10.0;
    }

    /**
     * 检查 {@link #worldToScreen} 的返回值是否在屏幕可见区域内。
     */
    public static boolean isOnScreen(Vec3 screenResult) {
        if (screenResult.z <= 0) return false;
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        return screenResult.x >= 0 && screenResult.x <= window.getGuiScaledWidth()
                && screenResult.y >= 0 && screenResult.y <= window.getGuiScaledHeight();
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        double dot = a.normalize().dot(b.normalize());
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }
}
